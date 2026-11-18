package com.docreview.web.dto;

import java.util.List;

public record ReportDto(
        TaskDto task,
        String summary,
        int highCount,
        int mediumCount,
        int lowCount,
        List<FindingDto> findings,
        List<BlockDto> blocks
) {
}
