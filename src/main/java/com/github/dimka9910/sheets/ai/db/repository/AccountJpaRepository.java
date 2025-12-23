package com.github.dimka9910.sheets.ai.db.repository;

import com.github.dimka9910.sheets.ai.db.entity.AccountJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for AccountJpaEntity.
 */
@Repository
public interface AccountJpaRepository extends JpaRepository<AccountJpaEntity, UUID> {

    /**
     * Find all accounts for a user.
     */
    List<AccountJpaEntity> findByUserId(UUID userId);

    /**
     * Find account by user ID and external ID (e.g., CARD_DIMA_VISA).
     */
    Optional<AccountJpaEntity> findByUserIdAndExternalId(UUID userId, String externalId);

    /**
     * Delete all accounts for a user.
     */
    void deleteByUserId(UUID userId);
}

