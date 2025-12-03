package com.github.dimka9910.sheets.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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
     * Список распознанных команд (для multi-command)
     */
    private List<ParsedCommand> parsedCommands;
    
    /**
     * @deprecated Используй parsedCommands — поддерживает несколько команд
     */
    @Deprecated
    private ParsedCommand parsedCommand;  // Что распознали (для отладки)
    
    /**
     * Количество успешно записанных операций
     */
    private int operationsCount;
}

