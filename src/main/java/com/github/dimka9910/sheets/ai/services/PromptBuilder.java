package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.dto.UserContext;

import java.util.List;

/**
 * Собирает финальный промпт из базового шаблона + контекста пользователя
 */
public class PromptBuilder {

    private static final String BASE_SYSTEM_PROMPT = """
            You are a financial assistant. Your task is to parse user's text commands and convert them to structured JSON.
            
            ## Available operation types:
            - INCOME: income (salary, received money)
            - EXPENSES: expense (spent, bought, paid)
            - TRANSFER: transfer between accounts (transferred from ... to ...)
            - CREDIT: credit operation (borrowed, lent)
            - UNKNOWN: if command is not understood
            
            ## Rules:
            1. Store all data in ENGLISH (tags, account names as provided, fund names as provided)
            2. Respond in the SAME LANGUAGE as the user's message
            3. Use default values from user context when not specified
            4. You have broad knowledge of brands, stores, services worldwide - use it to categorize
            5. If unsure about any parameter - set understood=false and ask in clarification
            
            ## Response format (JSON only, no other text):
            {
              "operationType": "EXPENSES",
              "amount": 500.0,
              "currency": "RSD",
              "accountName": "CARD_DIMA_VISA_RAIF",
              "fundName": "FAMILY_MONTHLY_BUDGET",
              "comment": "coffee",
              "secondPerson": null,
              "secondAccount": null,
              "secondCurrency": null,
              "understood": true,
              "errorMessage": null,
              "clarification": null
            }
            
            If you don't understand the command, set understood=false and write clarification with a question.
            Do NOT add any text before or after JSON.
            """;

    /**
     * Собирает полный промпт с учётом контекста пользователя
     */
    public String buildPrompt(UserContext context, String userMessage) {
        StringBuilder prompt = new StringBuilder(BASE_SYSTEM_PROMPT);
        
        // Добавляем контекст пользователя
        prompt.append("\n\n### User Context ###\n");
        
        if (context.getDisplayName() != null) {
            prompt.append("User name: ").append(context.getDisplayName()).append("\n");
        }
        
        // Дефолтные значения
        prompt.append("\n## Defaults (use when not specified):\n");
        
        if (context.getDefaultCurrency() != null) {
            prompt.append("- Default currency: ").append(context.getDefaultCurrency()).append("\n");
        }
        
        if (context.getDefaultAccount() != null) {
            prompt.append("- Default account: ").append(context.getDefaultAccount()).append("\n");
        }
        
        if (context.getDefaultPersonalFund() != null) {
            prompt.append("- Default fund for personal expenses: ").append(context.getDefaultPersonalFund()).append("\n");
        }
        
        if (context.getDefaultSharedFund() != null) {
            prompt.append("- Default fund for shared expenses: ").append(context.getDefaultSharedFund()).append("\n");
        }
        
        // Счета пользователя
        List<String> accounts = context.getAccounts();
        if (accounts != null && !accounts.isEmpty()) {
            prompt.append("\n## User's accounts:\n");
            prompt.append(String.join(", ", accounts)).append("\n");
            prompt.append("(Match user input to these account names. E.g. 'райф' or 'raif' → CARD_DIMA_VISA_RAIF)\n");
        }
        
        // Фонды пользователя
        List<String> funds = context.getFunds();
        if (funds != null && !funds.isEmpty()) {
            prompt.append("\n## User's funds/categories:\n");
            prompt.append(String.join(", ", funds)).append("\n");
            prompt.append("(Match user input to these fund names. E.g. 'общий' or 'shared' → FAMILY_MONTHLY_BUDGET)\n");
        }
        
        // Связанные пользователи
        List<String> linkedUsers = context.getLinkedUsers();
        if (linkedUsers != null && !linkedUsers.isEmpty()) {
            prompt.append("\n## Linked users (for shared finances):\n");
            for (String linkedUser : linkedUsers) {
                prompt.append("- ").append(linkedUser).append("\n");
            }
            prompt.append("(If user mentions 'her', 'girlfriend', partner by name → this is the linked user)\n");
        }
        
        // Кастомные инструкции
        List<String> instructions = context.getCustomInstructions();
        if (instructions != null && !instructions.isEmpty()) {
            prompt.append("\n## User's custom instructions (IMPORTANT - follow these):\n");
            for (String instruction : instructions) {
                prompt.append("- ").append(instruction).append("\n");
            }
        }
        
        prompt.append("\n### User message ###\n");
        prompt.append(userMessage);
        
        return prompt.toString();
    }

    /**
     * Возвращает базовый промпт без контекста (для тестирования)
     */
    public String buildSimplePrompt(String userMessage) {
        return BASE_SYSTEM_PROMPT + "\n\nUser message: " + userMessage;
    }
}
