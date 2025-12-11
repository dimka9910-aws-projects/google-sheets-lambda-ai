package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.dto.UserEntity;
import com.github.dimka9910.sheets.ai.repository.UserEntityRepository;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

/**
 * Service for managing user context.
 * Reads/writes to DynamoDB via UserEntityRepository.
 * 
 * Primary key: userName (e.g., "DIMA", "KIKI")
 * GSI: telegramId (for lookup from Telegram)
 */
@Slf4j
public class UserEntityService {

    private final UserEntityRepository repository;

    public UserEntityService() {
        this.repository = new UserEntityRepository();
    }

    public UserEntityService(UserEntityRepository repository) {
        this.repository = repository;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GET CONTEXT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get context by userName (primary key).
     * Returns empty context if not found.
     */
    public UserEntity getByUserName(String userName) {
        log.info("Getting context for userName: {}", userName);
        
        Optional<UserEntity> contextOpt = repository.getByUserName(userName);
        
        if (contextOpt.isPresent()) {
            log.debug("Found context for userName: {}", userName);
            return contextOpt.get();
        }
        
        log.info("User {} not found, returning empty context", userName);
        return UserEntity.builder()
                .userName(userName)
                .build();
    }

    /**
     * Get context by Telegram ID (GSI).
     * Returns Optional.empty() if not found (user needs to be created).
     */
    public Optional<UserEntity> getByTelegramId(String telegramId) {
        log.info("Getting context for telegramId: {}", telegramId);
        return repository.getByTelegramId(telegramId);
    }

    /**
     * Save user context.
     */
    public void saveContext(UserEntity context) {
        log.info("Saving context for userName: {}", context.getUserName());
        repository.save(context);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SETTINGS OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    public void addInstruction(String userName, String instruction) {
        UserEntity context = getByUserName(userName);
        context.addInstruction(instruction);
        saveContext(context);
        log.info("Added instruction for {}: {}", userName, instruction);
    }

    public void removeInstruction(String userName, int index) {
        UserEntity context = getByUserName(userName);
        context.removeInstruction(index);
        saveContext(context);
        log.info("Removed instruction {} for {}", index, userName);
    }

    public void setDefaultCurrency(String userName, String currency) {
        UserEntity context = getByUserName(userName);
        context.setDefaultCurrency(currency);
        saveContext(context);
        log.info("Set default currency for {}: {}", userName, currency);
    }

    public void setDefaultAccount(String userName, String account) {
        UserEntity context = getByUserName(userName);
        context.setDefaultAccount(account);
        saveContext(context);
        log.info("Set default account for {}: {}", userName, account);
    }

    public void setDefaultFund(String userName, String fund) {
        UserEntity context = getByUserName(userName);
        context.setDefaultFund(fund);
        saveContext(context);
        log.info("Set default fund for {}: {}", userName, fund);
    }

    public void addAccount(String userName, String account) {
        UserEntity context = getByUserName(userName);
        context.addAccount(account);
        saveContext(context);
        log.info("Added account for {}: {}", userName, account);
    }

    public void addFund(String userName, String fund) {
        UserEntity context = getByUserName(userName);
        context.addFund(fund);
        saveContext(context);
        log.info("Added fund for {}: {}", userName, fund);
    }

    public void clearInstructions(String userName) {
        UserEntity context = getByUserName(userName);
        context.clearInstructions();
        saveContext(context);
        log.info("Cleared instructions for {}", userName);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // USER MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Delete user by userName.
     */
    public void deleteUser(String userName) {
        repository.delete(userName);
        log.info("Deleted user {}", userName);
    }

    /**
     * Get context summary as text (for SHOW_SETTINGS).
     */
    public String getContextSummary(String userName) {
        UserEntity context = getByUserName(userName);
        StringBuilder sb = new StringBuilder();
        
        sb.append("👤 ").append(context.getUserName());
        if (context.getDisplayName() != null && !context.getDisplayName().equals(context.getUserName())) {
            sb.append(" (").append(context.getDisplayName()).append(")");
        }
        sb.append("\n\n");
        
        sb.append("💱 Default currency: ").append(orNotSet(context.getDefaultCurrency())).append("\n");
        sb.append("💳 Default account: ").append(orNotSet(context.getDefaultAccount())).append("\n");
        sb.append("📂 Default fund: ").append(orNotSet(context.getDefaultFund())).append("\n\n");
        
        List<String> accounts = context.getAccounts();
        if (accounts != null && !accounts.isEmpty()) {
            sb.append("💳 Accounts: ").append(String.join(", ", accounts)).append("\n");
        }
        
        List<String> funds = context.getFunds();
        if (funds != null && !funds.isEmpty()) {
            sb.append("📂 Funds: ").append(String.join(", ", funds)).append("\n");
        }
        
        var linkedUsers = context.getLinkedUsers();
        if (linkedUsers != null && !linkedUsers.isEmpty()) {
            String linkedNames = linkedUsers.stream()
                    .map(u -> u.getName() + " (" + u.getUserName() + ")")
                    .collect(java.util.stream.Collectors.joining(", "));
            sb.append("👥 Linked: ").append(linkedNames).append("\n");
        }
        
        List<String> instructions = context.getCustomInstructions();
        if (instructions != null && !instructions.isEmpty()) {
            sb.append("\n📋 Instructions:\n");
            for (int i = 0; i < instructions.size(); i++) {
                sb.append("  [").append(i).append("] ").append(instructions.get(i)).append("\n");
            }
        }
        
        return sb.toString();
    }
    
    private String orNotSet(String value) {
        return value != null ? value : "not set";
    }
}
