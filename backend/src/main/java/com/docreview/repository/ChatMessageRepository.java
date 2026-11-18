package com.docreview.repository;

import com.docreview.domain.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, String> {

    List<ChatMessage> findBySessionIdOrderByCreatedAtAscIdAsc(String sessionId);
}
