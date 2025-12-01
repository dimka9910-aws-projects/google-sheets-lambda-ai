package com.github.dimka9910.sheets.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Входящий запрос от Telegram бота
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {
    
    private String chatId;      // ID чата в Telegram
    private String userId;      // ID пользователя
    private String userName;    // Имя пользователя
    private String message;     // Текст сообщения от пользователя
}

