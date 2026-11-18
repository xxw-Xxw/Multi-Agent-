package com.docreview.web;

import com.docreview.security.CurrentUser;
import com.docreview.service.ReviewTaskService;
import com.docreview.web.dto.HistoryItemDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/history")
public class HistoryController {

    private final ReviewTaskService taskService;

    public HistoryController(ReviewTaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    public List<HistoryItemDto> list(@RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser currentUser) {
        return taskService.history(currentUser.userId());
    }
}
