package com.github.dimka9910.sheets.ai.db.repository;

import com.github.dimka9910.sheets.ai.db.entity.FinancialOperation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository для финансовых операций.
 * 
 * Использует query methods и @Query для custom запросов.
 * Намного проще чем ручной JdbcTemplate!
 */
@Repository
public interface FinancialOperationRepository extends JpaRepository<FinancialOperation, UUID> {

    /**
     * Найти активную операцию по ID (не удалённую)
     */
    @Query("SELECT f FROM FinancialOperation f WHERE f.id = :id AND f.deletedAt IS NULL")
    Optional<FinancialOperation> findActiveById(@Param("id") UUID id);

    /**
     * Найти все операции пользователя (не удалённые, с пагинацией)
     */
    @Query("SELECT f FROM FinancialOperation f WHERE f.userId = :userId AND f.deletedAt IS NULL ORDER BY f.transactionDate DESC")
    Page<FinancialOperation> findByUserIdAndNotDeleted(@Param("userId") UUID userId, Pageable pageable);

    /**
     * Найти операции пользователя за период
     */
    @Query("SELECT f FROM FinancialOperation f WHERE f.userId = :userId " +
           "AND f.transactionDate BETWEEN :startDate AND :endDate " +
           "AND f.deletedAt IS NULL " +
           "ORDER BY f.transactionDate DESC")
    List<FinancialOperation> findByUserIdAndDateRange(
        @Param("userId") UUID userId,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );

    /**
     * Найти операции по типу
     */
    @Query("SELECT f FROM FinancialOperation f WHERE f.userId = :userId " +
           "AND f.operationType = :operationType " +
           "AND f.deletedAt IS NULL " +
           "ORDER BY f.transactionDate DESC")
    List<FinancialOperation> findByUserIdAndOperationType(
        @Param("userId") UUID userId,
        @Param("operationType") String operationType,
        Pageable pageable
    );

    /**
     * Найти операции по linkId (для transfers - две связанные записи)
     */
    @Query("SELECT f FROM FinancialOperation f WHERE f.linkId = :linkId AND f.deletedAt IS NULL")
    List<FinancialOperation> findByLinkId(@Param("linkId") UUID linkId);

    /**
     * Soft delete операции
     */
    @Modifying
    @Query("UPDATE FinancialOperation f SET f.deletedAt = CURRENT_TIMESTAMP WHERE f.id = :id")
    int softDelete(@Param("id") UUID id);

    /**
     * Soft delete связанных операций (по linkId)
     */
    @Modifying
    @Query("UPDATE FinancialOperation f SET f.deletedAt = CURRENT_TIMESTAMP WHERE f.linkId = :linkId")
    int softDeleteByLinkId(@Param("linkId") UUID linkId);

    /**
     * Подсчёт активных операций пользователя
     */
    @Query("SELECT COUNT(f) FROM FinancialOperation f WHERE f.userId = :userId AND f.deletedAt IS NULL")
    long countByUserIdAndNotDeleted(@Param("userId") UUID userId);

    /**
     * Найти последнюю операцию пользователя
     * Returns List instead of Optional because Pageable requires collection return type
     * Use Pageable.ofSize(1) to get single result
     */
    @Query("SELECT f FROM FinancialOperation f WHERE f.userId = :userId " +
           "AND f.deletedAt IS NULL " +
           "ORDER BY f.createdAt DESC")
    List<FinancialOperation> findLatestByUserId(@Param("userId") UUID userId, Pageable pageable);

    /**
     * Найти все операции пользователя (не удалённые), sorted by transaction date
     */
    @Query("SELECT f FROM FinancialOperation f WHERE f.userId = :userId AND f.deletedAt IS NULL ORDER BY f.transactionDate DESC")
    List<FinancialOperation> findByUserIdOrderByTransactionDateDesc(@Param("userId") UUID userId);
}


