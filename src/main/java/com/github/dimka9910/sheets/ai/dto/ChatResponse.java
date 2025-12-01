package com.github.dimka9910.sheets.ai.dto;

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
    private ParsedCommand parsedCommand;  // Что распознали (для отладки)
}

