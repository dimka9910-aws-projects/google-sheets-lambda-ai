package com.github.dimka9910.sheets.ai.dto.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

/**
 * Одно сообщение в истории диалога.
 * Хранится в DynamoDB как вложенный объект в UserEntity.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class ConversationMessage {
    
    private String role;      // "user" | "assistant"
    private String content;   // текст сообщения
    private Long timestamp;   // epoch millis
    
    // Флаг что это был уточняющий вопрос
    private Boolean wasClarification;
    
    public static ConversationMessage userMessage(String content) {
        return ConversationMessage.builder()
                .role("user")
                .content(content)
                .timestamp(System.currentTimeMillis())
                .wasClarification(false)
                .build();
    }
    
    // Getter для DynamoDB (Boolean вместо boolean для nullable)
    public Boolean getWasClarification() {
        return wasClarification != null ? wasClarification : false;
    }
}

