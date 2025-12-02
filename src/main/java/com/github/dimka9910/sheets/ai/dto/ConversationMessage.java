package com.github.dimka9910.sheets.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

/**
 * Одно сообщение в истории диалога.
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
    
    // Если assistant успешно распарсил команду — результат
    // null если это было уточнение или ошибка
    private ParsedCommand parsedResult;
    
    // Флаг что это был уточняющий вопрос
    private boolean wasClarification;
    
    public static ConversationMessage userMessage(String content) {
        return ConversationMessage.builder()
                .role("user")
                .content(content)
                .timestamp(System.currentTimeMillis())
                .build();
    }
    
    public static ConversationMessage assistantMessage(String content, ParsedCommand parsed, boolean clarification) {
        return ConversationMessage.builder()
                .role("assistant")
                .content(content)
                .timestamp(System.currentTimeMillis())
                .parsedResult(parsed)
                .wasClarification(clarification)
                .build();
    }
}

