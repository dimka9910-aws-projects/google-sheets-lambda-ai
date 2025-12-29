package com.github.dimka9910.sheets.ai.dto.user;

import com.github.dimka9910.sheets.ai.dto.response.PendingClarificationAction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.*;

/**
 * User context DTO - settings, accounts, funds, custom instructions.
 * Now sourced from PostgreSQL (users, accounts, funds, linked_users, chat_messages tables).
 * 
 * Primary identifier: userName (e.g., "DIMA", "KIKI")
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserEntity {

    // Internal UUID from PostgreSQL (users.id)
    private UUID id;
    
    // Primary identifier - system user name (e.g., "DIMA", "KIKI")
    private String userName;
    
    // Telegram User ID for GSI lookup (e.g., "377662506")
    private String telegramId;
    
    // Display name (for UI, can be different from userName)
    private String displayName;
    
    // Created timestamp
    private Instant createdAt;

    // Defaults
    private AccountEntry defaultAccount;  // Now an object (not just ID string)
    private FundEntry defaultFund;        // Now an object (not just ID string)

    private String defaultCurrency;

  // Accounts (with IDs, display names, and aliases)
    @Builder.Default
    private List<AccountEntry> accounts = new ArrayList<>();
    
    // Funds/categories (with IDs, display names, and aliases)
    @Builder.Default
    private List<FundEntry> funds = new ArrayList<>();

    // Linked users (for shared finances)
    @Builder.Default
    private List<LinkedUserEntry> linkedUsers = new ArrayList<>();
    
    // Transient: linked user contexts (NOT saved to DynamoDB)
    @Builder.Default
    private transient Map<String, UserEntity> linkedUserEntitys = new HashMap<>();
    
    // Custom instructions
    @Builder.Default
    private List<String> customInstructions = new ArrayList<>();
    
    // Conversation history
    @Builder.Default
    private List<ConversationMessage> conversationHistory = new ArrayList<>();
    
    // Pending clarifications from MainAgent
    @Builder.Default
    private List<PendingClarificationAction> pendingActions = new ArrayList<>();
    
    // Preferred language (ISO code: en, ru, sr)
    private String preferredLanguage;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CORE FIELDS (Lombok generates getters/setters)
    // ═══════════════════════════════════════════════════════════════════════════
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INSTRUCTIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    public void addInstruction(String instruction) {
        if (customInstructions == null) {
            customInstructions = new ArrayList<>();
        }
        customInstructions.add(instruction);
    }
    
    public void removeInstruction(int index) {
        if (customInstructions != null && index >= 0 && index < customInstructions.size()) {
            customInstructions.remove(index);
        }
    }
    
    public void clearInstructions() {
        if (customInstructions != null) {
            customInstructions.clear();
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ACCOUNTS & FUNDS
    // ═══════════════════════════════════════════════════════════════════════════
    
    public void addAccount(AccountEntry account) {
        if (accounts == null) {
            accounts = new ArrayList<>();
        }
        // Check if account with same ID already exists
        boolean exists = accounts.stream()
                .anyMatch(a -> a.getAccountId().equals(account.getAccountId()));
        if (!exists) {
            accounts.add(account);
        }
    }
    
    /**
     * Add account with just ID (backward compatibility, creates entry with no displayName/aliases)
     */
    public void addAccount(String accountId) {
        addAccount(AccountEntry.builder()
                .accountId(accountId)
                .build());
    }
    
    /**
     * Find account by ID or alias
     */
    public Optional<AccountEntry> findAccountByAlias(String reference) {
        if (accounts == null || reference == null) return Optional.empty();
        return accounts.stream()
                .filter(a -> a.matches(reference))
                .findFirst();
    }
    
    public void addFund(FundEntry fund) {
        if (funds == null) {
            funds = new ArrayList<>();
        }
        // Check if fund with same ID already exists
        boolean exists = funds.stream()
                .anyMatch(f -> f.getFundId().equals(fund.getFundId()));
        if (!exists) {
            funds.add(fund);
        }
    }
    
    /**
     * Add fund with just ID (backward compatibility, creates entry with no displayName/aliases)
     */
    public void addFund(String fundId) {
        addFund(FundEntry.builder()
                .fundId(fundId)
                .build());
    }
    
    /**
     * Find fund by ID or alias
     */
    public Optional<FundEntry> findFundByAlias(String reference) {
        if (funds == null || reference == null) return Optional.empty();
        return funds.stream()
                .filter(f -> f.matches(reference))
                .findFirst();
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONVERSATION HISTORY
    // ═══════════════════════════════════════════════════════════════════════════
    
    private static final int MAX_HISTORY_MESSAGES = 20;
    
    public void addToHistory(ConversationMessage message) {
        if (conversationHistory == null) {
            conversationHistory = new ArrayList<>();
        }
        conversationHistory.add(message);
        if (conversationHistory.size() > MAX_HISTORY_MESSAGES) {
            conversationHistory = new ArrayList<>(
                conversationHistory.subList(conversationHistory.size() - MAX_HISTORY_MESSAGES, conversationHistory.size())
            );
        }
    }
    
    public void clearHistory() {
        if (conversationHistory != null) {
            conversationHistory.clear();
        }
    }
    
    public ConversationMessage getLastAssistantMessage() {
        if (conversationHistory == null || conversationHistory.isEmpty()) {
            return null;
        }
        for (int i = conversationHistory.size() - 1; i >= 0; i--) {
            if ("assistant".equals(conversationHistory.get(i).getRole())) {
                return conversationHistory.get(i);
            }
        }
        return null;
    }
    
    public String getLastBotMessageContent() {
        ConversationMessage msg = getLastAssistantMessage();
        return msg != null ? msg.getContent() : null;
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PENDING ACTIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    public void setPendingActions(List<PendingClarificationAction> actions) {
        this.pendingActions = actions != null ? new ArrayList<>(actions) : new ArrayList<>();
    }
    
    public void clearPendingActions() {
        if (pendingActions != null) {
            pendingActions.clear();
        }
    }
    
    public boolean hasPendingActions() {
        return pendingActions != null && !pendingActions.isEmpty();
    }
    

    // ═══════════════════════════════════════════════════════════════════════════
    // LINKED USER CONTEXTS (transient - not persisted)
    // ═══════════════════════════════════════════════════════════════════════════
    
    public Map<String, UserEntity> getLinkedUserEntitys() {
        return linkedUserEntitys;
    }

    
    public void addLinkedUserEntity(String linkedUserName, UserEntity context) {
        if (linkedUserEntitys == null) {
            linkedUserEntitys = new HashMap<>();
        }
        linkedUserEntitys.put(linkedUserName, context);
    }
}

