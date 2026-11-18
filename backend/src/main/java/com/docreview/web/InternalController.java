package com.docreview.web;

import com.docreview.domain.entity.DocumentBlock;
import com.docreview.repository.DocumentBlockRepository;
import com.docreview.repository.DocumentRepository;
import com.docreview.service.ReviewTaskService;
import com.docreview.web.dto.BlockDto;
import com.docreview.web.dto.FindingDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/** 仅供 Python 工具调用的只读接口，Python 不接触数据库。 */
@RestController
@RequestMapping("/internal")
public class InternalController {

    private static final List<String> DOMAIN_TERMS = List.of(
            "违约金", "单方解除", "单方面解除", "预付款", "尾款", "验收", "发票", "税率",
            "个人信息", "保密", "数据出境", "境外", "跨境", "管辖", "仲裁", "诉讼");

    private final DocumentRepository documentRepository;
    private final DocumentBlockRepository blockRepository;
    private final ReviewTaskService taskService;

    public InternalController(DocumentRepository documentRepository,
                              DocumentBlockRepository blockRepository,
                              ReviewTaskService taskService) {
        this.documentRepository = documentRepository;
        this.blockRepository = blockRepository;
        this.taskService = taskService;
    }

    @GetMapping("/docs/{docId}/blocks/search")
    public List<BlockDto> searchBlocks(@PathVariable String docId,
                                       @RequestParam(defaultValue = "") String q,
                                       @RequestParam(defaultValue = "8") int limit) {
        documentRepository.findById(docId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: " + docId));
        int size = Math.max(1, Math.min(limit, 20));
        return blockRepository.findByDocIdOrderBySeqAsc(docId).stream()
                .filter(block -> !"HEADING".equals(block.getType()))
                .map(block -> new ScoredBlock(block, score(block.getText(), q)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingInt(ScoredBlock::score).reversed()
                        .thenComparingInt(scored -> scored.block().getSeq()))
                .limit(size)
                .map(scored -> taskService.toBlockDto(scored.block()))
                .toList();
    }

    @GetMapping("/docs/{docId}/blocks/{blockId}")
    public BlockDto getBlock(@PathVariable String docId, @PathVariable String blockId) {
        DocumentBlock block = blockRepository.findById(blockId)
                .orElseThrow(() -> new IllegalArgumentException("文档片段不存在: " + blockId));
        if (!docId.equals(block.getDocId())) {
            throw new IllegalArgumentException("文档片段不属于当前文档");
        }
        return taskService.toBlockDto(block);
    }

    @GetMapping("/tasks/{taskId}/findings")
    public List<FindingDto> findings(@PathVariable String taskId) {
        return taskService.findings(taskId);
    }

    private int score(String text, String query) {
        if (query == null || query.isBlank()) {
            return 1;
        }
        String compact = query.replaceAll("[\\s\\p{P}\\p{S}]+", "");
        if (compact.isEmpty()) {
            return 1;
        }
        int score = text.contains(compact) ? 20 : 0;
        for (String term : DOMAIN_TERMS) {
            if (query.contains(term) && text.contains(term)) {
                score += 8;
            }
        }
        // 中文没有自然空格，使用二元词片段保证自然语言问题也能召回相关原文。
        for (int i = 0; i + 1 < compact.length(); i++) {
            if (text.contains(compact.substring(i, i + 2))) {
                score++;
            }
        }
        return score;
    }

    private record ScoredBlock(DocumentBlock block, int score) {
    }
}
