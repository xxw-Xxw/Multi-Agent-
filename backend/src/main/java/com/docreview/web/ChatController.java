package com.docreview.web;

import com.docreview.security.CurrentUser;
import com.docreview.service.ChatService;
import com.docreview.web.dto.ChatMessageDto;
import com.docreview.web.dto.SessionDto;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/sessions")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public SessionDto create(@RequestBody CreateSessionRequest request,
                             @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser currentUser) {
        return chatService.create(request.documentId(), request.taskId(), currentUser.userId());
    }

    @GetMapping("/{id}/messages")
    public List<ChatMessageDto> messages(@PathVariable String id,
                                         @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser currentUser) {
        return chatService.messages(id, currentUser.userId());
    }

    @PostMapping(value = "/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter send(@PathVariable String id, @RequestBody SendMessageRequest request,
                           @RequestAttribute(CurrentUser.ATTRIBUTE) CurrentUser currentUser) {
        return chatService.stream(id, request.message(), currentUser.userId());
    }

    public record CreateSessionRequest(String documentId, String taskId) {
    }

    public record SendMessageRequest(String message) {
    }
}
