package com.github.dimka9910.sheets.ai.db.mapper;

import com.github.dimka9910.sheets.ai.db.entity.FinancialOperation;
import com.github.dimka9910.sheets.ai.dto.actions.FinancialAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Mapper to convert DTOs to FinancialOperation entities for database persistence.
 * 
 * Handles different operation types:
 * - EXPENSE: Single record with negative amount
 * - INCOME: Single record with positive amount
 * - TRANSFER: Two linked records (debit + credit) with link_id
 */
@Slf4j
@Component
public class FinancialOperationMapper {

    /**
     * Convert EXPENSE to database entity.
     * Amount is stored as negative value.
     */
    public FinancialOperation toExpenseEntity(FinancialAction action, String userId) {
        FinancialOperation entity = new FinancialOperation();
        entity.setId(UUID.randomUUID());
        entity.setUserId(userId);
        entity.setOperationType("EXPENSE");
        entity.setAmount(BigDecimal.valueOf(action.getAmount()).negate()); // NEGATIVE for expenses
        entity.setCurrency(action.getCurrency());
        entity.setAccount(action.getAccount());
        entity.setFund(action.getFund());
        entity.setTransactionDate(parseDate(action.getDate()));
        entity.setDescription(buildDescription(action));
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        
        log.debug("Mapped EXPENSE: amount={}, account={}, fund={}", 
            entity.getAmount(), entity.getAccount(), entity.getFund());
        
        return entity;
    }

    /**
     * Convert INCOME to database entity.
     * Amount is stored as positive value.
     */
    public FinancialOperation toIncomeEntity(FinancialAction action, String userId) {
        FinancialOperation entity = new FinancialOperation();
        entity.setId(UUID.randomUUID());
        entity.setUserId(userId);
        entity.setOperationType("INCOME");
        entity.setAmount(BigDecimal.valueOf(action.getAmount())); // POSITIVE for income
        entity.setCurrency(action.getCurrency());
        entity.setAccount(action.getAccount());
        entity.setFund(action.getFund());
        entity.setTransactionDate(parseDate(action.getDate()));
        entity.setDescription(buildDescription(action));
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        
        log.debug("Mapped INCOME: amount={}, account={}, fund={}", 
            entity.getAmount(), entity.getAccount(), entity.getFund());
        
        return entity;
    }

    /**
     * Convert TRANSFER to two linked database entities.
     * Returns array: [debit_record, credit_record]
     * Both records share the same link_id.
     */
    public FinancialOperation[] toTransferEntities(FinancialAction action, String userId) {
        UUID linkId = UUID.randomUUID();
        LocalDateTime transactionDate = parseDate(action.getDate());
        String description = buildDescription(action);
        LocalDateTime now = LocalDateTime.now();
        BigDecimal amount = BigDecimal.valueOf(action.getAmount());

        // Debit record (source account, negative amount)
        FinancialOperation debit = new FinancialOperation();
        debit.setId(UUID.randomUUID());
        debit.setUserId(userId);
        debit.setOperationType("TRANSFER");
        debit.setAmount(amount.negate()); // NEGATIVE (money OUT)
        debit.setCurrency(action.getCurrency());
        debit.setAccount(action.getAccount()); // Source account
        debit.setFund(action.getFund());
        debit.setTransactionDate(transactionDate);
        debit.setDescription(description + " (from)");
        debit.setLinkId(linkId);
        debit.setCreatedAt(now);
        debit.setUpdatedAt(now);

        // Credit record (target account, positive amount)
        FinancialOperation credit = new FinancialOperation();
        credit.setId(UUID.randomUUID());
        credit.setUserId(userId);
        credit.setOperationType("TRANSFER");
        credit.setAmount(amount); // POSITIVE (money IN)
        credit.setCurrency(action.getCurrency());
        credit.setAccount(action.getTargetAccount()); // Target account
        credit.setFund(action.getFund()); // Same fund for now (targetFund doesn't exist yet)
        credit.setTransactionDate(transactionDate);
        credit.setDescription(description + " (to)");
        credit.setLinkId(linkId);
        credit.setCreatedAt(now);
        credit.setUpdatedAt(now);

        log.debug("Mapped TRANSFER: amount={}, from={} to={}, link_id={}", 
            action.getAmount(), action.getAccount(), action.getTargetAccount(), linkId);

        return new FinancialOperation[] { debit, credit };
    }

    /**
     * Build description from action comment and metadata.
     */
    private String buildDescription(FinancialAction action) {
        StringBuilder desc = new StringBuilder();
        
        if (action.getComment() != null && !action.getComment().isBlank()) {
            desc.append(action.getComment());
        }
        
        // Add third-party info if present
        if (action.getTargetPerson() != null && !action.getTargetPerson().isBlank()) {
            if (desc.length() > 0) desc.append(" | ");
            desc.append("Person: ").append(action.getTargetPerson());
        }
        
        // Add userName if present (for transfers from linked users)
        if (action.getUserName() != null && !action.getUserName().isBlank()) {
            if (desc.length() > 0) desc.append(" | ");
            desc.append("From: ").append(action.getUserName());
        }
        
        return desc.length() > 0 ? desc.toString() : null;
    }

    /**
     * Parse date string to LocalDateTime.
     * Supports ISO format and natural language (future enhancement).
     * If parsing fails or date is null, returns current timestamp.
     */
    private LocalDateTime parseDate(String dateString) {
        if (dateString == null || dateString.isBlank()) {
            return LocalDateTime.now();
        }
        
        try {
            // Try ISO format first: 2024-12-20T19:30:00
            return LocalDateTime.parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException e) {
            try {
                // Try ISO date only: 2024-12-20
                return LocalDateTime.parse(dateString + "T00:00:00", DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (DateTimeParseException e2) {
                log.warn("Failed to parse date '{}', using current timestamp", dateString);
                return LocalDateTime.now();
            }
        }
    }
}

