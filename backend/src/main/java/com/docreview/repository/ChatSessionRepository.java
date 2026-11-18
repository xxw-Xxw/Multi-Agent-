package com.docreview.repository;

import com.docreview.domain.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {

    Optional<ChatSession> findFirstByTaskIdAndUserIdOrderByCreatedAtDesc(String taskId, String userId);

    List<ChatSession> findByTaskIdIn(Collection<String> taskIds);
}
