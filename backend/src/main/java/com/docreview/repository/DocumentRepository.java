package com.docreview.repository;

import com.docreview.domain.entity.DocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DocumentRepository extends JpaRepository<DocumentEntity, String> {

    Optional<DocumentEntity> findFirstByContentHash(String contentHash);
}
