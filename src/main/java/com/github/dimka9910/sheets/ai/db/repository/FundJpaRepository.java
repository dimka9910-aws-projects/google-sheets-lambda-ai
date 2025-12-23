package com.github.dimka9910.sheets.ai.db.repository;

import com.github.dimka9910.sheets.ai.db.entity.FundJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for FundJpaEntity.
 */
@Repository
public interface FundJpaRepository extends JpaRepository<FundJpaEntity, UUID> {

    /**
     * Find all funds for a user.
     */
    List<FundJpaEntity> findByUserId(UUID userId);

    /**
     * Find fund by user ID and external ID (e.g., FOOD, TRANSPORT).
     */
    Optional<FundJpaEntity> findByUserIdAndExternalId(UUID userId, String externalId);

    /**
     * Delete all funds for a user.
     */
    void deleteByUserId(UUID userId);
}

