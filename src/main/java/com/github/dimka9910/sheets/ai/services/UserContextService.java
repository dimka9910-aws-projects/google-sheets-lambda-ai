package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.dto.UserContext;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Сервис для управления контекстом пользователей.
 * 
 * TODO: Заменить in-memory хранение на DynamoDB для персистентности
 */
@Slf4j
public class UserContextService {

    // In-memory хранилище (для MVP)
    // В проде заменить на DynamoDB
    private final Map<String, UserContext> contextStore = new ConcurrentHashMap<>();

    public UserContext getContext(String userId) {
        return contextStore.computeIfAbsent(userId, id -> 
            UserContext.builder()
                .userId(id)
                .defaultCurrency("RUB")
                .knownAccounts(List.of("Тинькофф", "Сбер", "Альфа", "Наличные"))
                .build()
        );
    }

    public void saveContext(UserContext context) {
        contextStore.put(context.getUserId(), context);
        log.info("Saved context for user {}: {}", context.getUserId(), context);
    }

    /**
     * Добавляет кастомную инструкцию пользователя
     */
    public void addInstruction(String userId, String instruction) {
        UserContext context = getContext(userId);
        context.addInstruction(instruction);
        saveContext(context);
        log.info("Added instruction for user {}: {}", userId, instruction);
    }

    /**
     * Устанавливает валюту по умолчанию
     */
    public void setDefaultCurrency(String userId, String currency) {
        UserContext context = getContext(userId);
        context.setDefaultCurrency(currency);
        saveContext(context);
        log.info("Set default currency for user {}: {}", userId, currency);
    }

    /**
     * Устанавливает счёт по умолчанию
     */
    public void setDefaultAccount(String userId, String account) {
        UserContext context = getContext(userId);
        context.setDefaultAccount(account);
        saveContext(context);
        log.info("Set default account for user {}: {}", userId, account);
    }

    /**
     * Очищает все кастомные инструкции
     */
    public void clearInstructions(String userId) {
        UserContext context = getContext(userId);
        context.clearInstructions();
        saveContext(context);
        log.info("Cleared instructions for user {}", userId);
    }

    /**
     * Получает все настройки пользователя в текстовом виде
     */
    public String getContextSummary(String userId) {
        UserContext context = getContext(userId);
        StringBuilder sb = new StringBuilder();
        
        sb.append("Валюта по умолчанию: ").append(context.getDefaultCurrency()).append("\n");
        
        if (context.getDefaultAccount() != null) {
            sb.append("Счёт по умолчанию: ").append(context.getDefaultAccount()).append("\n");
        }
        
        if (context.getKnownAccounts() != null && !context.getKnownAccounts().isEmpty()) {
            sb.append("Известные счета: ").append(String.join(", ", context.getKnownAccounts())).append("\n");
        }
        
        if (context.getCustomInstructions() != null && !context.getCustomInstructions().isEmpty()) {
            sb.append("Особые указания:\n");
            for (String instruction : context.getCustomInstructions()) {
                sb.append("  - ").append(instruction).append("\n");
            }
        }
        
        return sb.toString();
    }
}

