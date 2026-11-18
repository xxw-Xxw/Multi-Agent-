package com.docreview.service;

import com.docreview.config.AppProperties;
import com.docreview.domain.entity.ChatMessage;
import com.docreview.domain.entity.ChatSession;
import com.docreview.domain.entity.DocumentBlock;
import com.docreview.domain.entity.DocumentEntity;
import com.docreview.domain.entity.ReviewTask;
import com.docreview.orchestration.AgentServiceClient;
import com.docreview.repository.ChatMessageRepository;
import com.docreview.repository.ChatSessionRepository;
import com.docreview.repository.DocumentBlockRepository;
import com.docreview.repository.DocumentRepository;
import com.docreview.repository.ReviewTaskRepository;
import com.docreview.web.dto.ChatMessageDto;
import com.docreview.web.dto.CitationDto;
import com.docreview.web.dto.SessionDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** 会话状态存 Java；每个上游 SSE 事件立即转发，不缓冲完整回答。 */
@Service
public class ChatService {

    private static final long STREAM_TIMEOUT_MS = 180_000;

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final DocumentRepository documentRepository;
    private final ReviewTaskRepository taskRepository;
    private final DocumentBlockRepository blockRepository;
    private final AgentServiceClient agentClient;
    private final AppProperties props;
    private final ObjectMapper mapper;

    public ChatService(ChatSessionRepository sessionRepository,
                       ChatMessageRepository messageRepository,
                       DocumentRepository documentRepository,
                       ReviewTaskRepository taskRepository,
                       DocumentBlockRepository blockRepository,
                       AgentServiceClient agentClient,
                       AppProperties props,
                       ObjectMapper mapper) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.documentRepository = documentRepository;
        this.taskRepository = taskRepository;
        this.blockRepository = blockRepository;
        this.agentClient = agentClient;
        this.props = props;
        this.mapper = mapper;
    }

    public SessionDto create(String documentId, String taskId, String userId) {
        DocumentEntity document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: " + documentId));
        if (!userId.equals(document.getUserId())) {
            throw new IllegalArgumentException("无权访问该文档: " + documentId);
        }
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("创建问答会话需要审查任务 ID");
        }
        ReviewTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));
        if (!documentId.equals(task.getDocumentId()) || !userId.equals(task.getUserId())) {
            throw new IllegalArgumentException("任务不属于当前文档");
        }
        if (!task.getStatus().finished() || task.getStatus().name().equals("FAILED")) {
            throw new IllegalArgumentException("审查完成后才能发起文档问答");
        }

        ChatSession existing = sessionRepository.findFirstByTaskIdAndUserIdOrderByCreatedAtDesc(taskId, userId)
                .orElse(null);
        if (existing != null && documentId.equals(existing.getDocumentId())) {
            return new SessionDto(existing.getId(), documentId, taskId, existing.getCreatedAt().toString());
        }

        ChatSession session = new ChatSession();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setDocumentId(documentId);
        session.setTaskId(taskId);
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        sessionRepository.save(session);
        return new SessionDto(session.getId(), documentId, taskId, session.getCreatedAt().toString());
    }

    public List<ChatMessageDto> messages(String sessionId, String userId) {
        requireSession(sessionId, userId);
        return messageRepository.findBySessionIdOrderByCreatedAtAscIdAsc(sessionId).stream()
                .map(this::toDto)
                .toList();
    }

    public SseEmitter stream(String sessionId, String text, String userId) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("消息不能为空");
        }
        ChatSession session = requireSession(sessionId, userId);
        List<ChatMessage> previous = messageRepository.findBySessionIdOrderByCreatedAtAscIdAsc(sessionId);
        int from = Math.max(0, previous.size() - 12);
        List<Map<String, String>> history = new ArrayList<>();
        for (ChatMessage message : previous.subList(from, previous.size())) {
            if (message.getContent() != null && !message.getContent().isBlank()
                    && "DONE".equals(message.getStatus())) {
                history.add(Map.of("role", message.getRole().toLowerCase(), "content", message.getContent()));
            }
        }

        ChatMessage user = newMessage(sessionId, "USER", text.trim(), "DONE");
        messageRepository.save(user);
        ChatMessage assistant = newMessage(sessionId, "ASSISTANT", "", "STREAMING");
        messageRepository.save(assistant);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("messageId", assistant.getId());
        request.put("taskId", session.getTaskId());
        request.put("documentId", session.getDocumentId());
        request.put("message", text.trim());
        request.put("history", history);
        request.put("javaBaseUrl", props.getAgent().getJavaBaseUrl());

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        StringBuilder answer = new StringBuilder();
        AtomicBoolean finished = new AtomicBoolean();
        send(emitter, "start", Map.of("messageId", assistant.getId(), "userMessageId", user.getId()));

        Disposable subscription = agentClient.chat(request).subscribe(
                event -> onEvent(event, session, assistant, answer, emitter, finished),
                error -> fail(session, assistant, answer, emitter, finished, error.getMessage()),
                () -> {
                    if (!finished.get()) {
                        fail(session, assistant, answer, emitter, finished, "LangGraph 服务未返回 done 事件");
                    }
                });
        emitter.onTimeout(subscription::dispose);
        emitter.onError(error -> subscription.dispose());
        return emitter;
    }

    private void onEvent(ServerSentEvent<String> event, ChatSession session, ChatMessage assistant,
                         StringBuilder answer, SseEmitter emitter, AtomicBoolean finished) {
        if (finished.get()) {
            return;
        }
        try {
            JsonNode data = mapper.readTree(event.data() == null ? "{}" : event.data());
            switch (event.event() == null ? "" : event.event()) {
                case "token" -> {
                    answer.append(data.path("delta").asText());
                    send(emitter, "token", data);
                }
                case "tool_call" -> send(emitter, "tool_call", data);
                case "done" -> {
                    List<CitationDto> citations = validateCitations(session.getDocumentId(), data.path("citations"));
                    assistant.setContent(answer.toString());
                    assistant.setCitationsJson(mapper.writeValueAsString(citations));
                    assistant.setStatus("DONE");
                    messageRepository.save(assistant);
                    session.setUpdatedAt(LocalDateTime.now());
                    sessionRepository.save(session);

                    Map<String, Object> done = new LinkedHashMap<>();
                    done.put("messageId", assistant.getId());
                    done.put("citations", citations);
                    done.put("usage", data.path("usage"));
                    send(emitter, "done", done);
                    finished.set(true);
                    emitter.complete();
                }
                case "error" -> fail(session, assistant, answer, emitter, finished,
                        data.path("message").asText("LangGraph 问答失败"));
                default -> {
                    // 聊天协议只透传上述事件。
                }
            }
        } catch (Exception e) {
            fail(session, assistant, answer, emitter, finished, e.getMessage());
        }
    }

    private List<CitationDto> validateCitations(String documentId, JsonNode node) {
        List<CitationDto> citations = new ArrayList<>();
        if (!node.isArray()) {
            return citations;
        }
        for (JsonNode item : node) {
            String blockId = item.path("blockId").asText();
            String quote = item.path("quote").asText();
            DocumentBlock block = blockRepository.findById(blockId).orElse(null);
            if (block != null && documentId.equals(block.getDocId()) && !quote.isEmpty()
                    && block.getText().contains(quote)) {
                citations.add(new CitationDto(blockId, quote));
            }
        }
        return citations;
    }

    private void fail(ChatSession session, ChatMessage assistant, StringBuilder answer,
                      SseEmitter emitter, AtomicBoolean finished, String error) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        assistant.setContent(answer.toString());
        assistant.setStatus("FAILED");
        messageRepository.save(assistant);
        session.setUpdatedAt(LocalDateTime.now());
        sessionRepository.save(session);
        try {
            send(emitter, "error", Map.of("messageId", assistant.getId(),
                    "message", error == null ? "问答失败" : error, "retryable", true));
            emitter.complete();
        } catch (Exception ignored) {
            // 客户端已断开。
        }
    }

    private ChatSession requireSession(String sessionId, String userId) {
        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionId));
        if (!userId.equals(session.getUserId())) {
            throw new IllegalArgumentException("无权访问该会话: " + sessionId);
        }
        return session;
    }

    private ChatMessage newMessage(String sessionId, String role, String content, String status) {
        ChatMessage message = new ChatMessage();
        message.setId(UUID.randomUUID().toString());
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        message.setCitationsJson("[]");
        message.setStatus(status);
        message.setCreatedAt(LocalDateTime.now());
        return message;
    }

    private ChatMessageDto toDto(ChatMessage message) {
        List<CitationDto> citations = List.of();
        try {
            citations = mapper.readValue(message.getCitationsJson() == null ? "[]" : message.getCitationsJson(),
                    new TypeReference<>() {
                    });
        } catch (Exception ignored) {
            // 历史脏数据不影响正文展示。
        }
        return new ChatMessageDto(message.getId(), message.getRole().toLowerCase(), message.getContent(),
                citations, message.getStatus(), message.getCreatedAt().toString());
    }

    private void send(SseEmitter emitter, String name, Object data) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
            }
        } catch (IOException | IllegalStateException e) {
            throw new IllegalStateException("SSE 客户端连接已断开", e);
        }
    }
}
