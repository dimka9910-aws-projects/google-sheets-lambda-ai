package com.github.dimka9910.sheets.ai.dto.telegram;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response to send back to Telegram bot.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TelegramChatResponse {
    
    private String chatId;
    private String message;
    private boolean success;
    
    /**
     * Count of successfully recorded operations
     */
    private int operationsCount;
}

