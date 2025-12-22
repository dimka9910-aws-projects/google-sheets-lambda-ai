package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.dto.user.AccountEntry;
import com.github.dimka9910.sheets.ai.dto.user.FundEntry;
import com.github.dimka9910.sheets.ai.dto.user.LinkedUserEntry;
import com.github.dimka9910.sheets.ai.dto.user.UserEntity;
import com.github.dimka9910.sheets.ai.repository.UserEntityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Spring Service for managing user context.
 * Reads/writes to DynamoDB via UserEntityRepository.
 * Uses Spring DI.
 * 
 * Primary key: userName (e.g., "DIMA", "KIKI")
 * GSI: telegramId (for lookup from Telegram)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserEntityService {

    private final UserEntityRepository repository;

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
     * Resolve user context from Telegram ID with loaded linked users.
     * 
     * This is the main method for resolving user context from incoming Telegram messages.
     * 
     * @param telegramId Telegram user ID
     * @return Optional<UserEntity> with loaded linked users, or empty if not found
     */
    public Optional<UserEntity> resolveWithLinkedUsers(String telegramId) {
        if (telegramId == null || telegramId.isBlank()) {
            log.warn("Cannot resolve: telegramId is null or blank");
            return Optional.empty();
        }

        log.info("Resolving telegramId={}", telegramId);
        
        var found = getByTelegramId(telegramId);
        if (found.isEmpty()) {
            log.info("User not found for telegramId={}", telegramId);
            return Optional.empty();
        }

        UserEntity userContext = found.get();
        String userName = userContext.getUserName();
        log.info("Resolved telegramId={} → userName={}", telegramId, userName);

        // Load linked users' contexts
        loadLinkedUserContexts(userContext);

        return Optional.of(userContext);
    }

    /**
     * Save user context to DynamoDB.
     */
    public void saveContext(UserEntity context) {
        log.info("Saving context for userName: {}", context.getUserName());
        repository.save(context);
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
        
        List<AccountEntry> accounts = context.getAccounts();
        if (accounts != null && !accounts.isEmpty()) {
            String accountsList = accounts.stream()
                    .map(a -> a.getAccountId() + (a.getDisplayName() != null ? " (" + a.getDisplayName() + ")" : ""))
                    .collect(java.util.stream.Collectors.joining(", "));
            sb.append("💳 Accounts: ").append(accountsList).append("\n");
        }
        
        List<FundEntry> funds = context.getFunds();
        if (funds != null && !funds.isEmpty()) {
            String fundsList = funds.stream()
                    .map(f -> f.getFundId() + (f.getDisplayName() != null ? " (" + f.getDisplayName() + ")" : ""))
                    .collect(java.util.stream.Collectors.joining(", "));
            sb.append("📂 Funds: ").append(fundsList).append("\n");
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
    
    /**
     * Load linked users' full contexts (accounts, funds, etc.)
     */
    private void loadLinkedUserContexts(UserEntity userContext) {
        List<LinkedUserEntry> linkedUsers = userContext.getLinkedUsers();
        if (linkedUsers == null || linkedUsers.isEmpty()) {
            return;
        }

        for (LinkedUserEntry linkedUser : linkedUsers) {
            String linkedUserName = linkedUser.getUserName();
            if (linkedUserName != null && !linkedUserName.equals(userContext.getUserName())) {
                try {
                    UserEntity linkedContext = getByUserName(linkedUserName);
                    if (linkedContext != null && linkedContext.getUserName() != null) {
                        userContext.addLinkedUserEntity(linkedUserName, linkedContext);
                        log.debug("Loaded linked user context: {}", linkedUserName);
                    }
                } catch (Exception e) {
                    log.warn("Failed to load linked user context: {}", linkedUserName, e);
                }
            }
        }
    }
    
    private String orNotSet(String value) {
        return value != null ? value : "not set";
    }
}
