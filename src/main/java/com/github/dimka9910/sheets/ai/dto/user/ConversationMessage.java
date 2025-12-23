package com.github.dimka9910.sheets.ai.dto.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Одно сообщение в истории диалога.
 * Now stored in PostgreSQL chat_messages table.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationMessage {
    
    private String role;      // "user" | "assistant"
    private String content;   // текст сообщения
    private Long timestamp;   // epoch millis
    
    // Флаг что это был уточняющий вопрос
    private Boolean wasClarification;
    
    // UUID операций созданных этим сообщением (для аудита и отмены)
    private List<UUID> relatedOperationIds;
    
    public static ConversationMessage userMessage(String content) {
        return ConversationMessage.builder()
                .role("user")
                .content(content)
                .timestamp(System.currentTimeMillis())
                .wasClarification(false)
                .build();
    }
}

