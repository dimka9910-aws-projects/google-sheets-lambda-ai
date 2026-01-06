package com.github.dimka9910.sheets.ai.db.service;

import com.github.dimka9910.sheets.ai.db.entity.FinancialOperation;
import com.github.dimka9910.sheets.ai.db.mapper.FinancialOperationMapper;
import com.github.dimka9910.sheets.ai.db.repository.FinancialOperationRepository;
import com.github.dimka9910.sheets.ai.dto.response.FinancialAction;
import com.github.dimka9910.sheets.ai.dto.user.AccountEntry;
import com.github.dimka9910.sheets.ai.dto.user.FundEntry;
import com.github.dimka9910.sheets.ai.dto.user.UserEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for persisting financial operations to PostgreSQL database.
 * 
 * Handles:
 * - EXPENSE: Single record with negative amount
 * - INCOME: Single record with positive amount
 * - TRANSFER: Two linked records (debit + credit) with same link_id
 * - EXCHANGE: Not yet implemented
 * 
 * All operations are transactional.
 * 
 * DRY_RUN mode: when enabled, only logs operations without actually saving to DB.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "DATABASE_URL")
public class FinancialOperationService {

    private final FinancialOperationRepository repository;
    private final FinancialOperationMapper mapper;
    private final boolean dryRun;

    public FinancialOperationService(
            FinancialOperationRepository repository,
            FinancialOperationMapper mapper,
            @Value("${DRY_RUN:false}") String dryRunStr) {
        this.repository = repository;
        this.mapper = mapper;
        this.dryRun = "true".equalsIgnoreCase(dryRunStr) || "1".equals(dryRunStr);
        
        log.info("✅ FinancialOperationService initialized (DRY_RUN={})", this.dryRun);
    }

    /**
     * Save EXPENSE operation to database.
     * Amount is stored as negative value.
     * 
     * @param action Financial action from AI
     * @param userContext User context with accounts/funds
     * @return Created entity
     */
    @Transactional
    public FinancialOperation saveExpense(FinancialAction action, UserEntity userContext) {
        log.info("💰 Saving EXPENSE: amount={}, currency={}, account={}, fund={}", 
            action.getAmount(), action.getCurrency(), action.getAccount(), action.getFund());
        
        // Resolve external IDs → UUIDs
        UUID userId = userContext.getId();
        UUID accountId = resolveAccountId(action.getAccount(), userContext);
        UUID fundId = resolveFundId(action.getFund(), userContext);
        
        if (userId == null) {
            throw new IllegalStateException("User ID is null - user not persisted?");
        }
        
        FinancialOperation entity = mapper.toExpenseEntity(action, userId, accountId, fundId);
        
        // DRY_RUN mode - only log, don't save to DB
        if (dryRun) {
            log.info("[DRY_RUN] Would save EXPENSE: id={}, amount={}, currency={}, accountId={}, fundId={}", 
                entity.getId(), entity.getAmount(), entity.getCurrency(), entity.getAccountId(), entity.getFundId());
            return entity;
        }
        
        @SuppressWarnings("null")
        FinancialOperation saved = repository.save(entity);
        
        log.info("✅ EXPENSE saved: id={}, amount={}, accountId={}, fundId={}", 
            saved.getId(), saved.getAmount(), saved.getAccountId(), saved.getFundId());
        return saved;
    }

    /**
     * Save INCOME operation to database.
     * Amount is stored as positive value.
     * 
     * @param action Financial action from AI
     * @param userContext User context with accounts/funds
     * @return Created entity
     */
    @Transactional
    public FinancialOperation saveIncome(FinancialAction action, UserEntity userContext) {
        log.info("💰 Saving INCOME: amount={}, currency={}, account={}, fund={}", 
            action.getAmount(), action.getCurrency(), action.getAccount(), action.getFund());
        
        // Resolve external IDs → UUIDs
        UUID userId = userContext.getId();
        UUID accountId = resolveAccountId(action.getAccount(), userContext);
        UUID fundId = resolveFundId(action.getFund(), userContext);
        
        if (userId == null) {
            throw new IllegalStateException("User ID is null - user not persisted?");
        }
        
        FinancialOperation entity = mapper.toIncomeEntity(action, userId, accountId, fundId);
        
        // DRY_RUN mode - only log, don't save to DB
        if (dryRun) {
            log.info("[DRY_RUN] Would save INCOME: id={}, amount={}, currency={}, accountId={}, fundId={}", 
                entity.getId(), entity.getAmount(), entity.getCurrency(), entity.getAccountId(), entity.getFundId());
            return entity;
        }
        
        @SuppressWarnings("null")
        FinancialOperation saved = repository.save(entity);
        
        log.info("✅ INCOME saved: id={}, amount={}, accountId={}, fundId={}", 
            saved.getId(), saved.getAmount(), saved.getAccountId(), saved.getFundId());
        return saved;
    }

    /**
     * Save TRANSFER operation to database.
     * Creates two linked records:
     * 1. Debit record (source account, negative amount)
     * 2. Credit record (target account, positive amount)
     * Both share the same link_id.
     * 
     * @param action Financial action from AI
     * @param userContext User context with accounts/funds
     * @return List of created entities [debit, credit]
     */
    @Transactional
    public List<FinancialOperation> saveTransfer(FinancialAction action, UserEntity userContext) {
        log.info("💰 Saving TRANSFER: amount={}, currency={}, from={} to={}", 
            action.getAmount(), action.getCurrency(), action.getAccount(), action.getTargetAccount());
        
        // Resolve external IDs → UUIDs
        UUID userId = userContext.getId();
        UUID sourceAccountId = resolveAccountId(action.getAccount(), userContext);
        UUID targetAccountId = resolveAccountId(action.getTargetAccount(), userContext);
        // TRANSFER has no funds (for now): always persist fund_id=NULL, ignore any model-provided fund.
        UUID fundId = null;
        
        if (userId == null) {
            throw new IllegalStateException("User ID is null - user not persisted?");
        }
        if (sourceAccountId == null) {
            throw new IllegalArgumentException("Source account not found: " + action.getAccount());
        }
        if (targetAccountId == null) {
            throw new IllegalArgumentException("Target account not found: " + action.getTargetAccount());
        }
        
        FinancialOperation[] entities = mapper.toTransferEntities(action, userId, sourceAccountId, targetAccountId, fundId);
        FinancialOperation debit = entities[0];
        FinancialOperation credit = entities[1];
        
        // DRY_RUN mode - only log, don't save to DB
        if (dryRun) {
            log.info("[DRY_RUN] Would save TRANSFER: link_id={}, from={} to={}, amount={}", 
                debit.getLinkId(), sourceAccountId, targetAccountId, action.getAmount());
            return List.of(debit, credit);
        }
        
        @SuppressWarnings("null")
        FinancialOperation savedDebit = repository.save(debit);
        @SuppressWarnings("null")
        FinancialOperation savedCredit = repository.save(credit);
        
        log.info("✅ TRANSFER saved: link_id={}, debit_id={}, credit_id={}, from={} to={}", 
            savedDebit.getLinkId(), savedDebit.getId(), savedCredit.getId(), sourceAccountId, targetAccountId);
        
        return List.of(savedDebit, savedCredit);
    }

    /**
     * Find all operations for a user.
     * 
     * @param userId User UUID
     * @return List of financial operations
     */
    @Transactional(readOnly = true)
    public List<FinancialOperation> findByUserId(UUID userId) {
        log.debug("📊 Finding operations for user: {}", userId);
        return repository.findByUserIdOrderByTransactionDateDesc(userId);
    }

    /**
     * Find operation by ID.
     * 
     * @param id Operation ID
     * @return Financial operation or null
     */
    @Transactional(readOnly = true)
    public FinancialOperation findById(UUID id) {
        if (id == null) return null;
        @SuppressWarnings("null")
        FinancialOperation op = repository.findById(id).orElse(null);
        return op;
    }

    /**
     * Delete operation (soft delete by setting deleted_at).
     * 
     * @param id Operation ID
     */
    @Transactional
    public void softDelete(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("Operation id is required for softDelete");
        }
        log.info("🗑️ Soft deleting operation: {}", id);
        repository.findById(id).ifPresent(op -> {
            op.setDeletedAt(java.time.LocalDateTime.now());
            @SuppressWarnings("null")
            FinancialOperation saved = repository.save(op);
            log.debug("Soft delete saved: id={}", saved.getId());
            log.info("✅ Operation soft deleted: {}", id);
        });
    }

    /**
     * Delete operation by ID. If operation is a TRANSFER (linkId != null) deletes both sides.
     */
    @Transactional
    public void deleteOperation(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("Operation id is required for DELETE");
        }

        FinancialOperation op = repository.findActiveById(id).orElse(null);
        if (op == null) {
            log.warn("⚠️ DELETE requested but operation not found or already deleted: {}", id);
            return;
        }

        // If it is a transfer (two linked records) - delete by linkId
        if (op.getLinkId() != null) {
            int updated = repository.softDeleteByLinkId(op.getLinkId());
            log.info("✅ Soft deleted {} linked operations (linkId={})", updated, op.getLinkId());
            return;
        }

        int updated = repository.softDelete(id);
        log.info("✅ Soft deleted {} operation (id={})", updated, id);
    }

    /**
     * Modify an existing operation by ID.
     * Supports EXPENSE/INCOME and TRANSFER (updates both linked records).
     *
     * NOTE: FinancialAction.operationType should be MODIFY; the actual stored type is read from DB.
     */
    @Transactional
    public void modifyOperation(UUID id, FinancialAction action, UserEntity userContext) {
        if (id == null) {
            throw new IllegalArgumentException("Operation id is required for MODIFY");
        }
        if (action == null) {
            throw new IllegalArgumentException("Action is required for MODIFY");
        }
        if (userContext == null) {
            throw new IllegalArgumentException("User context is required for MODIFY");
        }

        FinancialOperation existing = repository.findActiveById(id).orElse(null);
        if (existing == null) {
            throw new IllegalStateException("Operation not found or already deleted: " + id);
        }

        String storedType = existing.getOperationType();
        if ("TRANSFER".equalsIgnoreCase(storedType) && existing.getLinkId() != null) {
            modifyTransfer(existing.getLinkId(), action, userContext);
            return;
        }

        modifySingle(existing, action, userContext);
    }

    private void modifyTransfer(UUID linkId, FinancialAction action, UserEntity userContext) {
        List<FinancialOperation> ops = repository.findByLinkId(linkId);
        if (ops == null || ops.isEmpty()) {
            throw new IllegalStateException("TRANSFER not found by linkId: " + linkId);
        }

        FinancialOperation debit = null;
        FinancialOperation credit = null;
        for (FinancialOperation op : ops) {
            if (op.getAmount() != null && op.getAmount().signum() < 0) debit = op;
            if (op.getAmount() != null && op.getAmount().signum() > 0) credit = op;
        }
        if (debit == null || credit == null) {
            throw new IllegalStateException("TRANSFER records malformed for linkId=" + linkId + " (need debit+credit)");
        }

        BigDecimal absAmount = action.getAmount() != null ? BigDecimal.valueOf(action.getAmount()).abs() : null;
        if (absAmount != null) {
            debit.setAmount(absAmount.negate());
            credit.setAmount(absAmount);
        }

        if (action.getCurrency() != null && !action.getCurrency().isBlank()) {
            debit.setCurrency(action.getCurrency());
            credit.setCurrency(action.getCurrency());
        }

        LocalDateTime txDate = parseDate(action.getDate());
        if (action.getDate() != null && !action.getDate().isBlank()) {
            debit.setTransactionDate(txDate);
            credit.setTransactionDate(txDate);
        }

        if (action.getAccount() != null && !action.getAccount().isBlank()) {
            UUID sourceAccountId = resolveAccountId(action.getAccount(), userContext);
            if (sourceAccountId == null) throw new IllegalArgumentException("Source account not found: " + action.getAccount());
            debit.setAccountId(sourceAccountId);
        }

        if (action.getTargetAccount() != null && !action.getTargetAccount().isBlank()) {
            UUID targetAccountId = resolveAccountId(action.getTargetAccount(), userContext);
            if (targetAccountId == null) throw new IllegalArgumentException("Target account not found: " + action.getTargetAccount());
            credit.setAccountId(targetAccountId);
        }

        if (action.getComment() != null) {
            // Keep existing suffixes "(from)/(to)" if present
            debit.setDescription(action.getComment() + " (from)");
            credit.setDescription(action.getComment() + " (to)");
        }

        @SuppressWarnings("null")
        FinancialOperation savedDebit = repository.save(debit);
        @SuppressWarnings("null")
        FinancialOperation savedCredit = repository.save(credit);
        log.debug("Modified TRANSFER saved: debit_id={}, credit_id={}", savedDebit.getId(), savedCredit.getId());
        log.info("✅ TRANSFER modified (linkId={})", linkId);
    }

    private void modifySingle(FinancialOperation existing, FinancialAction action, UserEntity userContext) {
        String storedType = existing.getOperationType();

        if (action.getAmount() != null) {
            BigDecimal absAmount = BigDecimal.valueOf(action.getAmount()).abs();
            if ("EXPENSE".equalsIgnoreCase(storedType)) {
                existing.setAmount(absAmount.negate());
            } else {
                // INCOME or any other single-record type: positive amount
                existing.setAmount(absAmount);
            }
        }

        if (action.getCurrency() != null && !action.getCurrency().isBlank()) {
            existing.setCurrency(action.getCurrency());
        }

        if (action.getDate() != null && !action.getDate().isBlank()) {
            existing.setTransactionDate(parseDate(action.getDate()));
        }

        if (action.getAccount() != null && !action.getAccount().isBlank()) {
            UUID accountId = resolveAccountId(action.getAccount(), userContext);
            if (accountId == null) throw new IllegalArgumentException("Account not found: " + action.getAccount());
            existing.setAccountId(accountId);
        }

        if (action.getFund() != null && !action.getFund().isBlank()) {
            UUID fundId = resolveFundId(action.getFund(), userContext);
            if (fundId == null) throw new IllegalArgumentException("Fund not found: " + action.getFund());
            existing.setFundId(fundId);
        }

        if (action.getComment() != null) {
            existing.setDescription(action.getComment());
        }

        @SuppressWarnings("null")
        FinancialOperation saved = repository.save(existing);
        log.debug("Modified operation saved: id={}", saved.getId());
        log.info("✅ {} modified (id={})", storedType, existing.getId());
    }

    private LocalDateTime parseDate(String dateString) {
        if (dateString == null || dateString.isBlank()) return null;
        try {
            return LocalDateTime.parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(dateString + "T00:00:00", DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (DateTimeParseException e2) {
                log.warn("Failed to parse date '{}', keeping original timestamp", dateString);
                return null;
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Resolve account external ID (or alias) to UUID.
     * Returns null if not found.
     */
    private UUID resolveAccountId(String accountReference, UserEntity userContext) {
        if (accountReference == null || accountReference.isBlank()) {
            return null;
        }
        
        // Try current user's accounts first
        Optional<AccountEntry> account = userContext.findAccountByAlias(accountReference);
        if (account.isPresent()) {
            return account.get().getId();
        }
        
        // Try linked users' accounts
        if (userContext.getLinkedUserEntitys() != null) {
            for (UserEntity linkedUser : userContext.getLinkedUserEntitys().values()) {
                account = linkedUser.findAccountByAlias(accountReference);
                if (account.isPresent()) {
                    log.debug("✅ Found account {} in linked user {}", accountReference, linkedUser.getUserName());
                    return account.get().getId();
                }
            }
        }
        
        return null;
    }
    
    /**
     * Resolve fund external ID (or alias) to UUID.
     * Returns null if not found.
     */
    private UUID resolveFundId(String fundReference, UserEntity userContext) {
        if (fundReference == null || fundReference.isBlank()) {
            return null;
        }
        
        Optional<FundEntry> fund = userContext.findFundByAlias(fundReference);
        if (fund.isPresent()) {
            return fund.get().getId();
        }

        // Try linked users' funds (for cross-user expense tracking)
        if (userContext.getLinkedUserEntitys() != null) {
            for (UserEntity linkedUser : userContext.getLinkedUserEntitys().values()) {
                fund = linkedUser.findFundByAlias(fundReference);
                if (fund.isPresent()) {
                    log.debug("✅ Found fund {} in linked user {}", fundReference, linkedUser.getUserName());
                    return fund.get().getId();
                }
            }
        }

        return null;
    }
}

