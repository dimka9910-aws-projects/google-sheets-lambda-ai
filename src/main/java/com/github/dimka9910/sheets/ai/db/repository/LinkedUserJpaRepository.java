package com.github.dimka9910.sheets.ai.db.repository;

import com.github.dimka9910.sheets.ai.db.entity.LinkedUserJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository for LinkedUserJpaEntity.
 */
@Repository
public interface LinkedUserJpaRepository extends JpaRepository<LinkedUserJpaEntity, UUID> {

    /**
     * Find all linked users for an owner.
     */
    List<LinkedUserJpaEntity> findByOwnerUserId(UUID ownerUserId);

    /**
     * Find linked user by owner and target.
     */
    Optional<LinkedUserJpaEntity> findByOwnerUserIdAndTargetUserId(UUID ownerUserId, UUID targetUserId);

    /**
     * Delete all linked users for an owner.
     */
    void deleteByOwnerUserId(UUID ownerUserId);
}

