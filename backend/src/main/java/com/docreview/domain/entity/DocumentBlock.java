package com.docreview.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 解析后的文档块，是"证据定位"的基本单位。
 */
@Entity
@Table(name = "document_block", indexes = @Index(name = "idx_block_doc", columnList = "doc_id"))
@Getter
@Setter
public class DocumentBlock {

    @Id
    private String id;

    @Column(name = "doc_id")
    private String docId;

    private int page;

    /** 章节路径，例如 "第三章 违约责任" */
    private String sectionPath;

    private int seq;

    @Column(columnDefinition = "TEXT")
    private String text;

    /** TEXT / HEADING */
    private String type;
}
