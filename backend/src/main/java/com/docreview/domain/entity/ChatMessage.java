package com.docreview.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "chat_message", indexes = @Index(name = "idx_message_session", columnList = "session_id"))
@Getter
@Setter
public class ChatMessage {

    @Id
    private String id;

    @Column(name = "session_id")
    private String sessionId;

    /** USER / ASSISTANT */
    @Column(name = "message_role")
    private String role;

    @Column(columnDefinition = "TEXT")
    private String content;

    /** JSON 数组：[{blockId, quote}] */
    @Column(columnDefinition = "TEXT")
    private String citationsJson;

    /** STREAMING / DONE / FAILED */
    private String status;

    private LocalDateTime createdAt;
}
