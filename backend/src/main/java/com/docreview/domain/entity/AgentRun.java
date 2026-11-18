package com.docreview.domain.entity;

import com.docreview.domain.AgentStatus;
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

/**
 * 单次 Agent 调用的执行轨迹，支持回放。
 */
@Entity
@Table(name = "agent_run", indexes = @Index(name = "idx_run_task", columnList = "task_id"))
@Getter
@Setter
public class AgentRun {

    @Id
    private String id;

    @Column(name = "task_id")
    private String taskId;

    /** 例如 LegalExpertAgent#1 */
    private String agentName;

    @Enumerated(EnumType.STRING)
    private AgentStatus status;

    @Column(columnDefinition = "TEXT")
    private String inputText;

    @Column(columnDefinition = "TEXT")
    private String outputText;

    private long tokens;

    private long latencyMs;

    @Column(length = 1000)
    private String errorMessage;

    private LocalDateTime createdAt;
}
