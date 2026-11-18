package com.docreview.orchestration;

import com.docreview.config.AppProperties;
import com.docreview.domain.AgentStatus;
import com.docreview.domain.HumanStatus;
import com.docreview.domain.RiskLevel;
import com.docreview.domain.TaskStatus;
import com.docreview.domain.Verdict;
import com.docreview.domain.entity.AgentRun;
import com.docreview.domain.entity.DocumentBlock;
import com.docreview.domain.entity.DocumentEntity;
import com.docreview.domain.entity.Finding;
import com.docreview.domain.entity.ReviewReport;
import com.docreview.domain.entity.ReviewTask;
import com.docreview.parser.DocumentParseService;
import com.docreview.repository.AgentRunRepository;
import com.docreview.repository.DocumentRepository;
import com.docreview.repository.FindingRepository;
import com.docreview.repository.ReviewReportRepository;
import com.docreview.repository.ReviewTaskRepository;
import com.docreview.rule.RuleCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Java 只负责解析、任务状态与结果落库；模型推理由 Python LangGraph 完成。
 */
@Service
public class ReviewOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ReviewOrchestrator.class);

    private final ReviewTaskRepository taskRepository;
    private final DocumentRepository documentRepository;
    private final FindingRepository findingRepository;
    private final ReviewReportRepository reportRepository;
    private final AgentRunRepository runRepository;
    private final DocumentParseService parseService;
    private final RuleCatalog ruleCatalog;
    private final TaskEventHub eventHub;
    private final ObjectMapper mapper;
    private final AppProperties props;
    private final AgentServiceClient agentClient;
    private final TransactionTemplate transactionTemplate;

    public ReviewOrchestrator(ReviewTaskRepository taskRepository,
                              DocumentRepository documentRepository,
                              FindingRepository findingRepository,
                              ReviewReportRepository reportRepository,
                              AgentRunRepository runRepository,
                              DocumentParseService parseService,
                              RuleCatalog ruleCatalog,
                              TaskEventHub eventHub,
                              ObjectMapper mapper,
                              AppProperties props,
                              AgentServiceClient agentClient,
                              TransactionTemplate transactionTemplate) {
        this.taskRepository = taskRepository;
        this.documentRepository = documentRepository;
        this.findingRepository = findingRepository;
        this.reportRepository = reportRepository;
        this.runRepository = runRepository;
        this.parseService = parseService;
        this.ruleCatalog = ruleCatalog;
        this.eventHub = eventHub;
        this.mapper = mapper;
        this.props = props;
        this.agentClient = agentClient;
        this.transactionTemplate = transactionTemplate;
    }

    @Async("reviewExecutor")
    public void run(String taskId) {
        ReviewTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        long start = System.currentTimeMillis();
        try {
            DocumentEntity document = documentRepository.findById(task.getDocumentId())
                    .orElseThrow(() -> new IllegalStateException("文档不存在"));

            stage(task, TaskStatus.PARSING, 5, "解析文档结构");
            List<DocumentBlock> blocks = parseService.parseAndSave(
                    document.getId(), new File(document.getStoragePath()), document.getName());
            if (blocks.isEmpty()) {
                throw new IllegalStateException("未能从文档中解析出正文内容");
            }
            document.setBlockCount(blocks.size());
            documentRepository.save(document);
            publish(taskId, 12, "解析完成，共 " + blocks.size() + " 个片段", null);

            Map<String, Object> request = new LinkedHashMap<>();
            request.put("taskId", taskId);
            request.put("documentName", document.getName());
            request.put("blocks", blocks);
            request.put("rules", ruleCatalog.all());
            request.put("sectionMaxChars", props.getReview().getSectionMaxChars());

            AtomicBoolean completed = new AtomicBoolean();
            agentClient.review(request)
                    .doOnNext(event -> handleEvent(event, task, blocks, completed, start))
                    .blockLast();
            if (!completed.get()) {
                throw new IllegalStateException("LangGraph 服务未返回 done 事件");
            }
            eventHub.complete(taskId);
        } catch (Exception e) {
            log.error("审查任务失败 taskId={}", taskId, e);
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage(truncate(e.getMessage(), 1800));
            task.setUpdatedAt(LocalDateTime.now());
            task.setLatencyMs(System.currentTimeMillis() - start);
            taskRepository.save(task);
            publish(taskId, task.getProgress(), "审查失败：" + e.getMessage(), null);
            eventHub.complete(taskId);
        }
    }

    private void handleEvent(ServerSentEvent<String> event, ReviewTask task, List<DocumentBlock> blocks,
                             AtomicBoolean completed, long start) {
        try {
            JsonNode data = mapper.readTree(event.data() == null ? "{}" : event.data());
            switch (event.event() == null ? "" : event.event()) {
                case "stage" -> handleStage(task, data);
                case "trace" -> recordRun(task.getId(), data);
                case "done" -> {
                    ReviewResult result = mapper.treeToValue(data.path("result"), ReviewResult.class);
                    long tokens = data.path("usage").path("tokens").asLong(0);
                    persistResult(task, result, blocks, tokens, start);
                    completed.set(true);
                }
                case "error" -> throw new IllegalStateException(
                        data.path("message").asText("LangGraph 审查失败"));
                default -> log.debug("忽略未知审查事件: {}", event.event());
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("解析 LangGraph 审查事件失败: " + e.getMessage(), e);
        }
    }

    private void handleStage(ReviewTask task, JsonNode data) {
        String stageName = data.path("stage").asText();
        TaskStatus status;
        try {
            status = TaskStatus.valueOf(stageName);
        } catch (IllegalArgumentException e) {
            return;
        }
        JsonNode extra = data.path("data");
        if (extra.isObject() && !extra.path("docType").asText().isBlank()) {
            task.setDocType(extra.path("docType").asText());
        }
        stage(task, status, data.path("progress").asInt(task.getProgress()),
                data.path("message").asText(status.name()));
    }

    private void persistResult(ReviewTask task, ReviewResult result, List<DocumentBlock> blocks,
                               long tokens, long start) {
        Map<String, DocumentBlock> blockMap = new LinkedHashMap<>();
        for (DocumentBlock block : blocks) {
            blockMap.put(block.getId(), block);
        }

        List<Finding> findings = new ArrayList<>();
        int invalidEvidence = 0;
        for (FindingDraft draft : result.findings() == null ? List.<FindingDraft>of() : result.findings()) {
            DocumentBlock block = blockMap.get(draft.blockId());
            if (block == null || "HEADING".equals(block.getType()) || draft.quote() == null
                    || !block.getText().contains(draft.quote())) {
                invalidEvidence++;
                continue;
            }
            RiskLevel level;
            try {
                level = RiskLevel.valueOf(draft.level());
            } catch (IllegalArgumentException | NullPointerException e) {
                invalidEvidence++;
                continue;
            }
            Finding finding = new Finding();
            finding.setId(java.util.UUID.randomUUID().toString());
            finding.setTaskId(task.getId());
            finding.setAgentName(draft.agentName());
            finding.setRuleId(draft.ruleId());
            finding.setLevel(level);
            finding.setBlockId(block.getId());
            finding.setPage(block.getPage());
            finding.setQuote(draft.quote());
            finding.setReason(draft.reason());
            finding.setSuggestion(draft.suggestion());
            finding.setVerdict(Verdict.KEEP);
            finding.setConflict(draft.conflict());
            finding.setHumanStatus(HumanStatus.PENDING);
            finding.setCreatedAt(LocalDateTime.now());
            findings.add(finding);
        }

        String summary = result.summary() == null || result.summary().isBlank()
                ? "审查完成，共保留 " + findings.size() + " 条风险。"
                : result.summary();
        if (invalidEvidence > 0) {
            summary += " Java 网关复核另丢弃 " + invalidEvidence + " 条无效证据。";
        }
        String finalSummary = summary;
        transactionTemplate.executeWithoutResult(transaction -> {
            findingRepository.deleteByTaskId(task.getId());
            findingRepository.saveAll(findings);

            reportRepository.findByTaskId(task.getId()).ifPresent(reportRepository::delete);
            ReviewReport report = new ReviewReport();
            report.setId(java.util.UUID.randomUUID().toString());
            report.setTaskId(task.getId());
            report.setSummary(finalSummary);
            report.setHighCount(countLevel(findings, RiskLevel.HIGH));
            report.setMediumCount(countLevel(findings, RiskLevel.MEDIUM));
            report.setLowCount(countLevel(findings, RiskLevel.LOW));
            report.setCreatedAt(LocalDateTime.now());
            reportRepository.save(report);

            task.setDocType(result.docType());
            task.setTokenUsed(tokens);
            task.setLatencyMs(System.currentTimeMillis() - start);
            task.setProgress(100);
            task.setStageMessage("审查完成");
            task.setStatus(result.degraded() ? TaskStatus.PARTIAL : TaskStatus.DONE);
            task.setErrorMessage(null);
            task.setUpdatedAt(LocalDateTime.now());
            taskRepository.save(task);
        });
        publish(task.getId(), 100, result.degraded() ? "审查完成（部分分片降级）" : "审查完成", null);
    }

    private int countLevel(List<Finding> findings, RiskLevel level) {
        return (int) findings.stream().filter(finding -> finding.getLevel() == level).count();
    }

    private void stage(ReviewTask task, TaskStatus status, int progress, String message) {
        task.setStatus(status);
        task.setProgress(progress);
        task.setStageMessage(message);
        task.setUpdatedAt(LocalDateTime.now());
        taskRepository.save(task);
        publish(task.getId(), progress, message, null);
    }

    private void publish(String taskId, int progress, String message, Object data) {
        ReviewTask task = taskRepository.findById(taskId).orElse(null);
        String stage = task == null ? "" : task.getStatus().name();
        eventHub.publish(taskId, new TaskEventHub.TaskEvent(stage, progress, message, data));
    }

    private void recordRun(String taskId, JsonNode data) {
        try {
            String input = jsonText(data.path("input"));
            String output = jsonText(data.path("output"));
            AgentRun run = new AgentRun();
            run.setId(java.util.UUID.randomUUID().toString());
            run.setTaskId(taskId);
            run.setAgentName(data.path("agentName").asText("UnknownAgent"));
            run.setStatus(AgentStatus.valueOf(data.path("status").asText("SUCCESS")));
            run.setInputText(truncate(input, 8000));
            run.setOutputText(truncate(output, 8000));
            run.setLatencyMs(data.path("latencyMs").asLong(0));
            run.setErrorMessage(truncate(data.path("error").asText(null), 900));
            run.setTokens(data.path("tokens").asLong(
                    ((input == null ? 0 : input.length()) + (output == null ? 0 : output.length())) / 2L));
            run.setCreatedAt(LocalDateTime.now());
            runRepository.save(run);
        } catch (Exception e) {
            log.warn("记录 agent_run 失败: {}", e.getMessage());
        }
    }

    private String jsonText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.asText() : node.toString();
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    public record ReviewResult(String docType, List<String> experts, String summary,
                               boolean degraded, List<FindingDraft> findings) {
    }

    public record FindingDraft(String agentName, String ruleId, String level, String blockId, int page,
                               String quote, String reason, String suggestion, boolean conflict) {
        // page 由 Java 按 blockId 回填，避免模型伪造页码。
    }
}
