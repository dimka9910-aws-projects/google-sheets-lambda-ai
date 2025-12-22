package com.github.dimka9910.sheets.ai.dto.telegram;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Ответ для отправки обратно в Telegram
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {
    
    private String chatId;
    private String message;
    private boolean success;
    
    /**
     * Количество успешно записанных операций
     */
    private int operationsCount;
}

