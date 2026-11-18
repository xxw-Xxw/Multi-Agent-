package com.docreview.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 单文档问答会话，历史只由 Java 持久化。 */
@Entity
@Table(name = "chat_session", indexes = {
        @Index(name = "idx_session_document", columnList = "document_id"),
        @Index(name = "idx_session_user", columnList = "user_id")
})
@Getter
@Setter
public class ChatSession {

    @Id
    private String id;

    /** 会话归属用户，读写消息时校验 */
    @Column(name = "user_id")
    private String userId;

    @Column(name = "document_id")
    private String documentId;

    private String taskId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
