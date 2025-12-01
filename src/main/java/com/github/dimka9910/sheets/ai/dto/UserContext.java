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
}
