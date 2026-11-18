package com.docreview.repository;

import com.docreview.domain.entity.ReviewReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReviewReportRepository extends JpaRepository<ReviewReport, String> {

    Optional<ReviewReport> findByTaskId(String taskId);
}
