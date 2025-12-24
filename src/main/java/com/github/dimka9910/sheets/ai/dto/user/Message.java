package com.github.dimka9910.sheets.ai.dto.user;

import com.github.dimka9910.sheets.ai.services.agents.MessageClassifierAgent.Category;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Сообщение в диалоге.
 * DTO for passing messages between services.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Message {
    
    /**
     * Уникальный ID сообщения (Telegram message_id или UUID).
     */
    private String messageId;
    
    /**
     * ID сообщения на которое это ответ (null если новая тема).
     */
    private String responseTo;
    
    /**
     * Текст сообщения.
     */
    private String content;
    
    /**
     * Category from MessageClassifier (one of 5 categories).
     * Stored as String for DB compatibility.
     */
    private String messageCategory;
    
    /**
     * Роль: "user" или "assistant".
     */
    private String role;
    
    /**
     * Timestamp сообщения (epoch millis).
     */
    private Long timestamp;
    
    // ==================== FACTORY METHODS ====================
    
    public static Message user(String messageId, String content) {
        return Message.builder()
                .messageId(messageId)
                .content(content)
                .role("user")
                .timestamp(System.currentTimeMillis())
                .build();
    }
    
    public static Message user(String messageId, String content, String responseTo) {
        return Message.builder()
                .messageId(messageId)
                .content(content)
                .responseTo(responseTo)
                .role("user")
                .timestamp(System.currentTimeMillis())
                .build();
    }
    
    public static Message assistant(String messageId, String content, Category category) {
        return Message.builder()
                .messageId(messageId)
                .content(content)
                .messageCategory(category != null ? category.name() : null)
                .role("assistant")
                .timestamp(System.currentTimeMillis())
                .build();
    }
    
    // ==================== UTILS ====================
    
    public boolean isUser() {
        return "user".equals(role);
    }
    
    public boolean isAssistant() {
        return "assistant".equals(role);
    }
    
    public boolean isResponse() {
        return responseTo != null && !responseTo.isBlank();
    }
}

