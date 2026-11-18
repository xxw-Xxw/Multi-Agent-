package com.docreview.web;

import com.docreview.security.CurrentUser;
import com.docreview.service.ReviewTaskService;
import com.docreview.web.dto.FindingDto;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/findings")
public class FindingController {

    private final ReviewTaskService taskService;

    public FindingController(ReviewTaskService taskService) {
        this.taskService = taskService;
    }

    public record UpdateRequest(String humanStatus) {
    }

    @PatchMapping("/{id}")
    public FindingDto update(@PathVariable String id, @RequestBody UpdateRequest request,
                             @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser currentUser) {
        return taskService.updateHumanStatus(id, request.humanStatus(), currentUser.userId());
    }
}
