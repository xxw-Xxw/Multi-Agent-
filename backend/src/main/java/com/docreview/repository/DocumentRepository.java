package com.docreview.repository;

import com.docreview.domain.entity.DocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DocumentRepository extends JpaRepository<DocumentEntity, String> {

    Optional<DocumentEntity> findFirstByContentHash(String contentHash);

    @Query("select coalesce(sum(d.sizeBytes), 0) from DocumentEntity d where d.userId = :userId")
    long sumSizeByUserId(@Param("userId") String userId);
}
