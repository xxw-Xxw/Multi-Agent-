package com.docreview.repository;

import com.docreview.domain.entity.AgentRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentRunRepository extends JpaRepository<AgentRun, String> {

    List<AgentRun> findByTaskIdOrderByCreatedAtAsc(String taskId);
}
