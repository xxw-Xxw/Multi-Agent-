package com.docreview.repository;

import com.docreview.domain.entity.Finding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface FindingRepository extends JpaRepository<Finding, String> {

    List<Finding> findByTaskIdOrderByCreatedAtAsc(String taskId);

    List<Finding> findByTaskIdIn(Collection<String> taskIds);

    List<Finding> findByTaskIdAndVerdictNot(String taskId, com.docreview.domain.Verdict verdict);

    void deleteByTaskId(String taskId);
}
