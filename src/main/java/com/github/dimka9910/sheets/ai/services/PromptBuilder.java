package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.dto.UserContext;

/**
 * Собирает финальный промпт из базового шаблона + контекста пользователя
 */
public class PromptBuilder {

    private static final String BASE_SYSTEM_PROMPT = """
            Ты финансовый помощник. Твоя задача — парсить текстовые команды пользователя и преобразовывать их в структурированный JSON.
            
            Доступные типы операций:
            - INCOME: доход (зарплата, получил деньги, пришли деньги)
            - EXPENSES: расход (потратил, купил, заплатил)
            - TRANSFER: перевод между счетами (перевёл с ... на ...)
            - CREDIT: кредитная операция (взял в долг, дал в долг)
            - UNKNOWN: если не удалось понять команду
            
            Валюты: RUB (рубли, р, ₽), USD (доллары, $), EUR (евро, €).
            
            Категории расходов (fundName): Еда, Транспорт, Развлечения, Одежда, Здоровье, Дом, Связь, Подписки, Путешествия, Другое.
            
            Отвечай ТОЛЬКО валидным JSON в формате:
            {
              "operationType": "EXPENSES",
              "amount": 500.0,
              "currency": "RUB",
              "accountName": "Тинькофф",
              "fundName": "Еда",
              "comment": "кофе",
              "secondPerson": null,
              "secondAccount": null,
              "secondCurrency": null,
              "understood": true,
              "errorMessage": null,
              "clarification": null
            }
            
            Если не понял команду, установи understood=false и напиши clarification с уточняющим вопросом.
            Не добавляй никакого текста до или после JSON.
            """;

    /**
     * Собирает полный промпт с учётом контекста пользователя
     */
    public String buildPrompt(UserContext context, String userMessage) {
        StringBuilder prompt = new StringBuilder(BASE_SYSTEM_PROMPT);
        
        // Добавляем контекст пользователя
        prompt.append("\n\n### Контекст пользователя ###\n");
        
        if (context.getDefaultCurrency() != null) {
            prompt.append("Валюта по умолчанию (если не указана): ")
                  .append(context.getDefaultCurrency()).append("\n");
        }
        
        if (context.getDefaultAccount() != null) {
            prompt.append("Счёт по умолчанию (если не указан): ")
                  .append(context.getDefaultAccount()).append("\n");
        }
        
        if (context.getKnownAccounts() != null && !context.getKnownAccounts().isEmpty()) {
            prompt.append("Известные счета пользователя: ")
                  .append(String.join(", ", context.getKnownAccounts())).append("\n");
        }
        
        if (context.getCustomCategories() != null && !context.getCustomCategories().isEmpty()) {
            prompt.append("Дополнительные категории: ")
                  .append(String.join(", ", context.getCustomCategories())).append("\n");
        }
        
        // Добавляем кастомные инструкции
        if (context.getCustomInstructions() != null && !context.getCustomInstructions().isEmpty()) {
            prompt.append("\n### Особые указания пользователя ###\n");
            for (String instruction : context.getCustomInstructions()) {
                prompt.append("- ").append(instruction).append("\n");
            }
        }
        
        prompt.append("\n### Сообщение пользователя ###\n");
        prompt.append(userMessage);
        
        return prompt.toString();
    }

    /**
     * Возвращает базовый промпт без контекста (для тестирования)
     */
    public String buildSimplePrompt(String userMessage) {
        return BASE_SYSTEM_PROMPT + "\n\nСообщение пользователя: " + userMessage;
    }
}

