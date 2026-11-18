package com.docreview.web;

import com.docreview.orchestration.TaskEventHub;
import com.docreview.security.CurrentUser;
import com.docreview.service.ReviewTaskService;
import com.docreview.web.dto.BlockDto;
import com.docreview.web.dto.ReportDto;
import com.docreview.web.dto.TaskDto;
import com.docreview.web.dto.TraceDto;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class ReviewTaskController {

    private final ReviewTaskService taskService;
    private final TaskEventHub eventHub;

    public ReviewTaskController(ReviewTaskService taskService, TaskEventHub eventHub) {
        this.taskService = taskService;
        this.eventHub = eventHub;
    }

    public record CreateTaskRequest(String documentId) {
    }

    @PostMapping
    public TaskDto create(@RequestBody CreateTaskRequest request,
                          @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser currentUser) {
        return taskService.create(request.documentId(), currentUser.userId());
    }

    @GetMapping("/{id}")
    public TaskDto get(@PathVariable String id,
                       @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser currentUser) {
        return taskService.get(id, currentUser.userId());
    }

    @GetMapping("/{id}/blocks")
    public List<BlockDto> blocks(@PathVariable String id,
                                 @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser currentUser) {
        return taskService.blocks(id, currentUser.userId());
    }

    @GetMapping("/{id}/report")
    public ReportDto report(@PathVariable String id,
                            @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser currentUser) {
        return taskService.report(id, currentUser.userId());
    }

    @GetMapping("/{id}/trace")
    public List<TraceDto> trace(@PathVariable String id,
                                @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser currentUser) {
        return taskService.trace(id, currentUser.userId());
    }

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id,
                             @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser currentUser) {
        TaskDto task = taskService.get(id, currentUser.userId());
        SseEmitter emitter = eventHub.subscribe(id);
        try {
            emitter.send(SseEmitter.event().name("progress")
                    .data(new TaskEventHub.TaskEvent(task.status(), task.progress(), task.stageMessage(), null),
                            MediaType.APPLICATION_JSON));
        } catch (IOException ignored) {
            // 客户端已断开
        }
        return emitter;
    }
}
