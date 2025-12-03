package com.github.dimka9910.sheets.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;

import java.util.ArrayList;
import java.util.List;

/**
 * Контекст пользователя - его настройки, счета, фонды, кастомные инструкции.
 * Хранится в DynamoDB таблице finance-tracker-users-{env}
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class UserContext {

    private String userId;          // PK
    private String displayName;     // Отображаемое имя
    private String telegramId;      // GSI для поиска по Telegram ID
    
    // Счета пользователя
    @Builder.Default
    private List<String> accounts = new ArrayList<>();
    
    // Фонды/категории пользователя
    @Builder.Default
    private List<String> funds = new ArrayList<>();
    
    // Дефолтные значения
    private String defaultAccount;
    private String defaultCurrency;
    private String defaultPersonalFund;   // Для личных трат
    private String defaultSharedFund;     // Для совместных трат
    
    // Связанные пользователи (для совместных финансов)
    @Builder.Default
    private List<String> linkedUsers = new ArrayList<>();
    
    // Кастомные инструкции от пользователя
    @Builder.Default
    private List<String> customInstructions = new ArrayList<>();
    
    // История текущего диалога (для уточнений)
    @Builder.Default
    private List<ConversationMessage> conversationHistory = new ArrayList<>();
    
    // Предложенная инструкция, ожидающая подтверждения (Learning)
    private String pendingSuggestion;
    
    // Команда, ожидающая уточнений (частично заполненная)
    // Сохраняем чтобы накапливать ответы пользователя
    private ParsedCommand pendingCommand;
    
    // Состояние онбординга (для новых пользователей)
    private String onboardingState;
    
    // Предпочитаемый язык общения (ISO code: en, ru, sr, es, etc.)
    // null = английский по умолчанию
    private String preferredLanguage;
    
    // Debug mode — показывать подробную информацию о работе системы
    @Builder.Default
    private Boolean debugMode = false;
    
    // Последние операции для возможности отмены (храним последние 5)
    @Builder.Default
    private List<ParsedCommand> lastOperations = new ArrayList<>();
    
    @DynamoDbPartitionKey
    public String getUserId() {
        return userId;
    }
    
    @DynamoDbSecondaryPartitionKey(indexNames = "telegramId-index")
    public String getTelegramId() {
        return telegramId;
    }
    
    // ====== Utility methods ======
    
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
    
    public void addAccount(String account) {
        if (accounts == null) {
            accounts = new ArrayList<>();
        }
        if (!accounts.contains(account)) {
            accounts.add(account);
        }
    }
    
    public void addFund(String fund) {
        if (funds == null) {
            funds = new ArrayList<>();
        }
        if (!funds.contains(fund)) {
            funds.add(fund);
        }
    }
    
    // ====== Conversation History methods ======
    
    public void addToHistory(ConversationMessage message) {
        if (conversationHistory == null) {
            conversationHistory = new ArrayList<>();
        }
        conversationHistory.add(message);
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
    
    public boolean isAwaitingClarification() {
        ConversationMessage last = getLastAssistantMessage();
        return last != null && Boolean.TRUE.equals(last.getWasClarification());
    }
    
    /**
     * Обрезает историю если превышает лимит сообщений
     */
    public void trimHistory(int maxMessages) {
        if (conversationHistory != null && conversationHistory.size() > maxMessages) {
            conversationHistory = new ArrayList<>(
                conversationHistory.subList(conversationHistory.size() - maxMessages, conversationHistory.size())
            );
        }
    }
    
    // ====== Last Operations (для отмены) ======
    
    private static final int MAX_UNDO_OPERATIONS = 5;
    
    /**
     * Добавляет операцию в историю для возможности отмены
     */
    public void addOperation(ParsedCommand operation) {
        if (lastOperations == null) {
            lastOperations = new ArrayList<>();
        }
        lastOperations.add(operation);
        // Храним только последние N
        if (lastOperations.size() > MAX_UNDO_OPERATIONS) {
            lastOperations.remove(0);
        }
    }
    
    /**
     * Возвращает последнюю операцию (для отмены)
     */
    public ParsedCommand getLastOperation() {
        if (lastOperations == null || lastOperations.isEmpty()) {
            return null;
        }
        return lastOperations.get(lastOperations.size() - 1);
    }
    
    /**
     * Удаляет последнюю операцию из истории (после отмены)
     */
    public ParsedCommand popLastOperation() {
        if (lastOperations == null || lastOperations.isEmpty()) {
            return null;
        }
        return lastOperations.remove(lastOperations.size() - 1);
    }
    
    /**
     * Проверяет есть ли операции для отмены
     */
    public boolean hasOperationsToUndo() {
        return lastOperations != null && !lastOperations.isEmpty();
    }
}
