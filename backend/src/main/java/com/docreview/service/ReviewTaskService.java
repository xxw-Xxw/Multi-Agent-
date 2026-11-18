package com.docreview.service;

import com.docreview.domain.TaskStatus;
import com.docreview.domain.entity.AgentRun;
import com.docreview.domain.entity.ChatSession;
import com.docreview.domain.entity.DocumentBlock;
import com.docreview.domain.entity.DocumentEntity;
import com.docreview.domain.entity.Finding;
import com.docreview.domain.entity.ReviewReport;
import com.docreview.domain.entity.ReviewTask;
import com.docreview.orchestration.ReviewOrchestrator;
import com.docreview.repository.AgentRunRepository;
import com.docreview.repository.ChatSessionRepository;
import com.docreview.repository.DocumentBlockRepository;
import com.docreview.repository.DocumentRepository;
import com.docreview.repository.FindingRepository;
import com.docreview.repository.ReviewReportRepository;
import com.docreview.repository.ReviewTaskRepository;
import com.docreview.web.dto.BlockDto;
import com.docreview.web.dto.FindingDto;
import com.docreview.web.dto.HistoryItemDto;
import com.docreview.web.dto.ReportDto;
import com.docreview.web.dto.TaskDto;
import com.docreview.web.dto.TraceDto;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ReviewTaskService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ReviewTaskRepository taskRepository;
    private final DocumentRepository documentRepository;
    private final DocumentBlockRepository blockRepository;
    private final FindingRepository findingRepository;
    private final ReviewReportRepository reportRepository;
    private final AgentRunRepository runRepository;
    private final ChatSessionRepository sessionRepository;
    private final ReviewOrchestrator orchestrator;

    public ReviewTaskService(ReviewTaskRepository taskRepository,
                             DocumentRepository documentRepository,
                             DocumentBlockRepository blockRepository,
                             FindingRepository findingRepository,
                             ReviewReportRepository reportRepository,
                             AgentRunRepository runRepository,
                             ChatSessionRepository sessionRepository,
                             ReviewOrchestrator orchestrator) {
        this.taskRepository = taskRepository;
        this.documentRepository = documentRepository;
        this.blockRepository = blockRepository;
        this.findingRepository = findingRepository;
        this.reportRepository = reportRepository;
        this.runRepository = runRepository;
        this.sessionRepository = sessionRepository;
        this.orchestrator = orchestrator;
    }

    public TaskDto create(String documentId, String userId) {
        DocumentEntity document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: " + documentId));
        if (!userId.equals(document.getUserId())) {
            throw new IllegalArgumentException("无权访问该文档: " + documentId);
        }

        ReviewTask task = new ReviewTask();
        task.setId(UUID.randomUUID().toString());
        task.setUserId(userId);
        task.setDocumentId(documentId);
        task.setStatus(TaskStatus.CREATED);
        task.setProgress(0);
        task.setStageMessage("任务已创建");
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        taskRepository.save(task);

        orchestrator.run(task.getId());
        return toDto(task);
    }

    public TaskDto get(String taskId, String userId) {
        return toDto(requireTask(taskId, userId));
    }

    public List<BlockDto> blocks(String taskId, String userId) {
        ReviewTask task = requireTask(taskId, userId);
        return blockRepository.findByDocIdOrderBySeqAsc(task.getDocumentId()).stream()
                .map(this::toBlockDto)
                .toList();
    }

    /** 仅内部接口使用：Python 工具按 taskId 取结论，不做用户归属校验。 */
    public List<FindingDto> findings(String taskId) {
        requireTask(taskId);
        return findingRepository.findByTaskIdOrderByCreatedAtAsc(taskId).stream()
                .map(this::toFindingDto)
                .toList();
    }

    public List<TraceDto> trace(String taskId, String userId) {
        requireTask(taskId, userId);
        return runRepository.findByTaskIdOrderByCreatedAtAsc(taskId).stream()
                .map(this::toTraceDto)
                .toList();
    }

    public ReportDto report(String taskId, String userId) {
        ReviewTask task = requireTask(taskId, userId);
        ReviewReport report = reportRepository.findByTaskId(taskId).orElse(null);
        List<FindingDto> findings = findingRepository.findByTaskIdOrderByCreatedAtAsc(taskId).stream()
                .map(this::toFindingDto)
                .toList();
        List<BlockDto> blocks = blockRepository.findByDocIdOrderBySeqAsc(task.getDocumentId()).stream()
                .map(this::toBlockDto)
                .toList();
        return new ReportDto(toDto(task),
                report == null ? null : report.getSummary(),
                report == null ? 0 : report.getHighCount(),
                report == null ? 0 : report.getMediumCount(),
                report == null ? 0 : report.getLowCount(),
                findings,
                blocks);
    }

    /** 历史审查记录：当前用户 review_task 最近 30 条，附带文档名、风险统计与会话。 */
    public List<HistoryItemDto> history(String userId) {
        List<ReviewTask> tasks = taskRepository.findTop30ByUserIdOrderByCreatedAtDesc(userId);
        if (tasks.isEmpty()) {
            return List.of();
        }
        List<String> taskIds = tasks.stream().map(ReviewTask::getId).toList();
        Map<String, String> documentNames = documentRepository
                .findAllById(tasks.stream().map(ReviewTask::getDocumentId).distinct().toList())
                .stream()
                .filter(document -> userId.equals(document.getUserId()))
                .collect(Collectors.toMap(DocumentEntity::getId, DocumentEntity::getName));
        Map<String, List<Finding>> findingsByTask = findingRepository.findByTaskIdIn(taskIds).stream()
                .collect(Collectors.groupingBy(Finding::getTaskId));
        Map<String, String> sessionByTask = sessionRepository.findByTaskIdIn(taskIds).stream()
                .filter(session -> userId.equals(session.getUserId()))
                .collect(Collectors.toMap(ChatSession::getTaskId, ChatSession::getId, (first, ignored) -> first));

        return tasks.stream().map(task -> {
            List<Finding> findings = findingsByTask.getOrDefault(task.getId(), List.of());
            int high = (int) findings.stream().filter(f -> f.getLevel() == com.docreview.domain.RiskLevel.HIGH).count();
            int medium = (int) findings.stream().filter(f -> f.getLevel() == com.docreview.domain.RiskLevel.MEDIUM).count();
            int low = (int) findings.stream().filter(f -> f.getLevel() == com.docreview.domain.RiskLevel.LOW).count();
            return new HistoryItemDto(
                    task.getId(),
                    task.getDocumentId(),
                    documentNames.getOrDefault(task.getDocumentId(), "未知文档"),
                    task.getStatus().name(),
                    task.getDocType(),
                    task.getProgress(),
                    findings.size(),
                    high,
                    medium,
                    low,
                    sessionByTask.get(task.getId()),
                    task.getCreatedAt() == null ? null : task.getCreatedAt().format(FORMATTER)
            );
        }).toList();
    }

    public FindingDto updateHumanStatus(String findingId, String humanStatus, String userId) {
        Finding finding = findingRepository.findById(findingId)
                .orElseThrow(() -> new IllegalArgumentException("结论不存在: " + findingId));
        requireTask(finding.getTaskId(), userId);
        finding.setHumanStatus(com.docreview.domain.HumanStatus.valueOf(humanStatus.toUpperCase()));
        findingRepository.save(finding);
        return toFindingDto(finding);
    }

    private ReviewTask requireTask(String taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));
    }

    private ReviewTask requireTask(String taskId, String userId) {
        ReviewTask task = requireTask(taskId);
        if (!userId.equals(task.getUserId())) {
            throw new IllegalArgumentException("无权访问该任务: " + taskId);
        }
        return task;
    }

    public TaskDto toDto(ReviewTask task) {
        return new TaskDto(
                task.getId(),
                task.getDocumentId(),
                task.getStatus().name(),
                task.getDocType(),
                task.getProgress(),
                task.getStageMessage(),
                task.getErrorMessage(),
                task.getLatencyMs(),
                task.getCreatedAt() == null ? null : task.getCreatedAt().format(FORMATTER)
        );
    }

    public BlockDto toBlockDto(DocumentBlock block) {
        return new BlockDto(block.getId(), block.getPage(), block.getSectionPath(),
                block.getSeq(), block.getText(), block.getType());
    }

    public FindingDto toFindingDto(Finding finding) {
        return new FindingDto(
                finding.getId(),
                finding.getAgentName(),
                finding.getRuleId(),
                finding.getLevel().name(),
                finding.getBlockId(),
                finding.getPage(),
                finding.getQuote(),
                finding.getReason(),
                finding.getSuggestion(),
                finding.isConflict(),
                finding.getVerdict() == null ? null : finding.getVerdict().name(),
                finding.getHumanStatus() == null ? null : finding.getHumanStatus().name()
        );
    }

    private TraceDto toTraceDto(AgentRun run) {
        return new TraceDto(run.getId(), run.getAgentName(),
                run.getStatus().name(), run.getInputText(), run.getOutputText(),
                run.getTokens(), run.getLatencyMs(), run.getErrorMessage(),
                run.getCreatedAt() == null ? null : run.getCreatedAt().format(FORMATTER));
    }
}
