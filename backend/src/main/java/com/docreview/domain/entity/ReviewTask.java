package com.docreview.domain.entity;

import com.docreview.domain.TaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "review_task", indexes = @Index(name = "idx_task_user", columnList = "user_id"))
@Getter
@Setter
public class ReviewTask {

    @Id
    private String id;

    /** 任务归属用户，历史列表按此过滤 */
    @Column(name = "user_id")
    private String userId;

    private String documentId;

    @Enumerated(EnumType.STRING)
    private TaskStatus status;

    /** Planner 识别出的文档类型 */
    private String docType;

    private int progress;

    private String stageMessage;

    @Column(length = 2000)
    private String errorMessage;

    private long tokenUsed;

    private long latencyMs;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
