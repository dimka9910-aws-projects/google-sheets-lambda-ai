package com.github.dimka9910.sheets.ai.db.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * JPA Entity representing a financial operation in the database.
 * Supports fiat currencies and cryptocurrencies with DECIMAL(32, 18) precision.
 * 
 * Transfers are stored as two linked records with the same linkId.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "financial_operations", indexes = {
    @Index(name = "idx_financial_operations_user_id", columnList = "user_id"),
    @Index(name = "idx_financial_operations_operation_type", columnList = "operation_type"),
    @Index(name = "idx_financial_operations_transaction_date", columnList = "transaction_date"),
    @Index(name = "idx_financial_operations_deleted_at", columnList = "deleted_at"),
    @Index(name = "idx_financial_operations_link_id", columnList = "link_id")
})
public class FinancialOperation {

    /**
     * Unique identifier (UUID)
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    /**
     * User ID (FK to users.id)
     */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * Operation type: EXPENSE, INCOME, INTERNAL_TRANSFER, TRANSFER, EXCHANGE
     */
    @Column(name = "operation_type", nullable = false, length = 20)
    private String operationType;

    /**
     * Account ID (FK to accounts.id)
     */
    @Column(name = "account_id")
    private UUID accountId;

    /**
     * Fund ID (FK to funds.id)
     */
    @Column(name = "fund_id")
    private UUID fundId;

    /**
     * Amount in currency units (DECIMAL(32, 18) for crypto support)
     * Negative for debits (expenses, outgoing transfers)
     * Positive for credits (income, incoming transfers)
     */
    @Column(name = "amount", nullable = false, precision = 32, scale = 18)
    private BigDecimal amount;

    /**
     * Currency code (USD, EUR, RSD, BTC, ETH, etc.)
     */
    @Column(name = "currency", nullable = false, length = 10)
    private String currency;

    /**
     * Date and time of the transaction
     */
    @Column(name = "transaction_date", nullable = false)
    private LocalDateTime transactionDate;

    /**
     * Optional description/comment
     */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * Link to related transaction (for INTERNAL_TRANSFER, TRANSFER, EXCHANGE)
     * Two records with the same linkId represent opposite sides of a transfer
     */
    @Column(name = "link_id", columnDefinition = "UUID")
    private UUID linkId;

    /**
     * Creation timestamp (auto-generated)
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Last update timestamp (auto-updated)
     */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Soft delete timestamp (NULL if not deleted)
     */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}

