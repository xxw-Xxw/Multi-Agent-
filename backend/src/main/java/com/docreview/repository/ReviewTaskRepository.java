package com.docreview.repository;

import com.docreview.domain.entity.ReviewTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReviewTaskRepository extends JpaRepository<ReviewTask, String> {

    List<ReviewTask> findTop30ByUserIdOrderByCreatedAtDesc(String userId);
}
