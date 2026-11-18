package com.docreview.repository;

import com.docreview.domain.entity.DocumentBlock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentBlockRepository extends JpaRepository<DocumentBlock, String> {

    List<DocumentBlock> findByDocIdOrderBySeqAsc(String docId);

    void deleteByDocId(String docId);
}
