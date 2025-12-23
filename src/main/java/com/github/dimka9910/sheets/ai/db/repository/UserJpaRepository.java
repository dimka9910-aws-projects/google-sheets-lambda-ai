package com.github.dimka9910.sheets.ai.db.repository;

import com.github.dimka9910.sheets.ai.db.entity.UserJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for UserJpaEntity.
 */
@Repository
public interface UserJpaRepository extends JpaRepository<UserJpaEntity, UUID> {

    /**
     * Find user by system username (e.g., "DIMA").
     */
    Optional<UserJpaEntity> findByUsername(String username);

    /**
     * Find user by Telegram ID.
     */
    Optional<UserJpaEntity> findByTelegramId(String telegramId);
    
    /**
     * Find user with accounts and funds in ONE query (JOIN FETCH).
     * Избегаем N+1 problem, но не грузим chat messages (они большие).
     */
    @Query("""
        SELECT DISTINCT u FROM UserJpaEntity u
        LEFT JOIN FETCH u.accounts
        LEFT JOIN FETCH u.funds
        WHERE u.username = :username
    """)
    Optional<UserJpaEntity> findByUsernameWithAccountsAndFunds(@Param("username") String username);
    
    /**
     * Find user with accounts and funds by Telegram ID in ONE query.
     */
    @Query("""
        SELECT DISTINCT u FROM UserJpaEntity u
        LEFT JOIN FETCH u.accounts
        LEFT JOIN FETCH u.funds
        WHERE u.telegramId = :telegramId
    """)
    Optional<UserJpaEntity> findByTelegramIdWithAccountsAndFunds(@Param("telegramId") String telegramId);

    /**
     * Check if username exists.
     */
    boolean existsByUsername(String username);
}

