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
        UUID fundId = resolveFundId(action.getFund(), userContext); // Can be null
        
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
        
        FinancialOperation savedDebit = repository.save(debit);
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
        return repository.findById(id).orElse(null);
    }

    /**
     * Delete operation (soft delete by setting deleted_at).
     * 
     * @param id Operation ID
     */
    @Transactional
    public void softDelete(UUID id) {
        log.info("🗑️ Soft deleting operation: {}", id);
        repository.findById(id).ifPresent(op -> {
            op.setDeletedAt(java.time.LocalDateTime.now());
            repository.save(op);
            log.info("✅ Operation soft deleted: {}", id);
        });
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
        return fund.map(FundEntry::getId).orElse(null);
    }
}

