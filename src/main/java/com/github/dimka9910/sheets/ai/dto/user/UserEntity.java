package com.github.dimka9910.sheets.ai.dto.user;

import com.github.dimka9910.sheets.ai.dto.actions.PendingClarificationAction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbIgnore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * User context - settings, accounts, funds, custom instructions.
 * Stored in DynamoDB table finance-tracker-users-{env}
 * 
 * Primary Key: userName (e.g., "DIMA", "KIKI")
 * GSI: telegramId (for lookup from Telegram)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class UserEntity {

    // Primary identifier - system user name (e.g., "DIMA", "KIKI")
    private String userName;
    
    // Telegram User ID for GSI lookup (e.g., "377662506")
    private String telegramId;
    
    // Display name (for UI, can be different from userName)
    private String displayName;
    
    // Accounts (with IDs, display names, and aliases)
    @Builder.Default
    private List<AccountEntry> accounts = new ArrayList<>();
    
    // Funds/categories (with IDs, display names, and aliases)
    @Builder.Default
    private List<FundEntry> funds = new ArrayList<>();
    
    // Defaults
    private String defaultAccount;
    private String defaultCurrency;
    private String defaultFund;
    
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
    
    // Debug mode
    @Builder.Default
    private Boolean debugMode = false;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DynamoDB KEYS
    // ═══════════════════════════════════════════════════════════════════════════
    
    @DynamoDbPartitionKey
    @DynamoDbAttribute("userId")  // Maps to existing DynamoDB attribute "userId"
    public String getUserName() {
        return userName;
    }
    
    @DynamoDbSecondaryPartitionKey(indexNames = "telegramId-index")
    public String getTelegramId() {
        return telegramId;
    }
    
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
    public AccountEntry findAccount(String reference) {
        if (accounts == null || reference == null) return null;
        return accounts.stream()
                .filter(a -> a.matches(reference))
                .findFirst()
                .orElse(null);
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
    public FundEntry findFund(String reference) {
        if (funds == null || reference == null) return null;
        return funds.stream()
                .filter(f -> f.matches(reference))
                .findFirst()
                .orElse(null);
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
    
    public boolean isAwaitingClarification() {
        ConversationMessage last = getLastAssistantMessage();
        return last != null && Boolean.TRUE.equals(last.getWasClarification());
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
    
    // ════════════════════════════════════════════════════════════════════════════
    // NOTE: UNDO feature removed - никогда не работал в legacy коде
    // ════════════════════════════════════════════════════════════════════════════
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LINKED USER CONTEXTS (transient)
    // ═══════════════════════════════════════════════════════════════════════════
    
    @DynamoDbIgnore
    public Map<String, UserEntity> getLinkedUserEntitys() {
        return linkedUserEntitys;
    }
    
    public void setLinkedUserEntitys(Map<String, UserEntity> linkedUserEntitys) {
        this.linkedUserEntitys = linkedUserEntitys;
    }
    
    public void addLinkedUserEntity(String linkedUserName, UserEntity context) {
        if (linkedUserEntitys == null) {
            linkedUserEntitys = new HashMap<>();
        }
        linkedUserEntitys.put(linkedUserName, context);
    }
}

