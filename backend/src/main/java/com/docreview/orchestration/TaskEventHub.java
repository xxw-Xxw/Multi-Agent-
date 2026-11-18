package com.docreview.orchestration;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 任务事件推送（SSE）。前端订阅后可实时看到每个 Agent 的进度。
 */
@Component
public class TaskEventHub {

    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String taskId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.computeIfAbsent(taskId, key -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> remove(taskId, emitter));
        emitter.onError(error -> remove(taskId, emitter));
        return emitter;
    }

    public void publish(String taskId, TaskEvent event) {
        List<SseEmitter> targets = emitters.get(taskId);
        if (targets == null) {
            return;
        }
        for (SseEmitter emitter : targets) {
            try {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event()
                            .name("progress")
                            .data(event, MediaType.APPLICATION_JSON));
                }
            } catch (IOException | IllegalStateException e) {
                remove(taskId, emitter);
            }
        }
    }

    public void complete(String taskId) {
        List<SseEmitter> targets = emitters.remove(taskId);
        if (targets == null) {
            return;
        }
        for (SseEmitter emitter : targets) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 客户端已断开
            }
        }
    }

    private void remove(String taskId, SseEmitter emitter) {
        List<SseEmitter> targets = emitters.get(taskId);
        if (targets != null) {
            targets.remove(emitter);
        }
    }

    /**
     * @param stage    当前阶段
     * @param progress 0-100
     * @param message  展示给用户的说明
     * @param data     附加数据（可为 null）
     */
    public record TaskEvent(String stage, int progress, String message, Object data) {
    }
}
