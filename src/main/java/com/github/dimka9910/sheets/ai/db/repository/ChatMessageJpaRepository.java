package com.github.dimka9910.sheets.ai.db.repository;

import com.github.dimka9910.sheets.ai.db.entity.ChatMessageJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA Repository for ChatMessageJpaEntity.
 */
@Repository
public interface ChatMessageJpaRepository extends JpaRepository<ChatMessageJpaEntity, UUID> {

    /**
     * Find last N messages for a user, ordered by created_at DESC.
     */
    @Query(value = "SELECT * FROM chat_messages WHERE user_id = :userId ORDER BY created_at DESC LIMIT :limit", 
           nativeQuery = true)
    List<ChatMessageJpaEntity> findLastNMessages(@Param("userId") UUID userId, @Param("limit") int limit);

    /**
     * Find all messages for a user, ordered by created_at ASC (for migration/export).
     */
    List<ChatMessageJpaEntity> findByUserIdOrderByCreatedAtAsc(UUID userId);

    /**
     * Delete all messages for a user.
     */
    void deleteByUserId(UUID userId);
}

