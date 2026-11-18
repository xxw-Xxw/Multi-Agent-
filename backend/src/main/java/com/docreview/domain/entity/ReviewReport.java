package com.docreview.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "review_report")
@Getter
@Setter
public class ReviewReport {

    @Id
    private String id;

    private String taskId;

    @Column(columnDefinition = "TEXT")
    private String summary;

    private int highCount;

    private int mediumCount;

    private int lowCount;

    private LocalDateTime createdAt;
}
