package com.github.dimka9910.sheets.ai.dto;

import com.github.dimka9910.sheets.ai.services.llm.MessageClassifierAgent.Tag;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

import java.util.Set;

/**
 * Сообщение в диалоге.
 * Используется для хранения истории в DynamoDB и передачи между сервисами.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
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
     * Теги контекста из MessageClassifier.
     * Хранятся как Set<String> для совместимости с DynamoDB.
     */
    private Set<String> contextTags;
    
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
    
    public static Message assistant(String messageId, String content, Set<Tag> tags) {
        return Message.builder()
                .messageId(messageId)
                .content(content)
                .contextTags(tagsToStrings(tags))
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
    
    private static Set<String> tagsToStrings(Set<Tag> tags) {
        if (tags == null) return null;
        return tags.stream()
                .map(Enum::name)
                .collect(java.util.stream.Collectors.toSet());
    }
}

