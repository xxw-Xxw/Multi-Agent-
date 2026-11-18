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
@Table(name = "documents", indexes = @Index(name = "idx_document_user", columnList = "user_id"))
@Getter
@Setter
public class DocumentEntity {

    @Id
    private String id;

    /** 文档归属用户，历史与会话按此隔离 */
    @Column(name = "user_id")
    private String userId;

    private String name;

    private String contentType;

    private long sizeBytes;

    /** 原文件落盘路径 */
    private String storagePath;

    /** 文件内容 hash，用于重复审查缓存 */
    private String contentHash;

    private int blockCount;

    private LocalDateTime createdAt;
}
