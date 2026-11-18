package com.docreview.domain.entity;

import com.docreview.domain.HumanStatus;
import com.docreview.domain.RiskLevel;
import com.docreview.domain.Verdict;
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
@Table(name = "finding", indexes = @Index(name = "idx_finding_task", columnList = "task_id"))
@Getter
@Setter
public class Finding {

    @Id
    private String id;

    @Column(name = "task_id")
    private String taskId;

    /** 产出该结论的 Agent */
    private String agentName;

    private String ruleId;

    @Column(name = "risk_level")
    @Enumerated(EnumType.STRING)
    private RiskLevel level;

    private String blockId;

    private int page;

    @Column(columnDefinition = "TEXT")
    private String quote;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(columnDefinition = "TEXT")
    private String suggestion;

    @Enumerated(EnumType.STRING)
    private Verdict verdict;

    /** 同一位置存在不同判定时置 true，提示人工重点看 */
    private boolean conflict;

    @Enumerated(EnumType.STRING)
    private HumanStatus humanStatus;

    private LocalDateTime createdAt;
}
