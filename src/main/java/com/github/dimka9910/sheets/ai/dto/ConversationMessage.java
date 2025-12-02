package com.github.dimka9910.sheets.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbIgnore;

/**
 * Одно сообщение в истории диалога.
 * Хранится в DynamoDB как вложенный объект в UserContext.
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
    
    // parsedResult НЕ хранится в DynamoDB — это временные данные в runtime
    private transient ParsedCommand parsedResult;
    
    public static ConversationMessage userMessage(String content) {
        return ConversationMessage.builder()
                .role("user")
                .content(content)
                .timestamp(System.currentTimeMillis())
                .wasClarification(false)
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
    
    // Getter для DynamoDB (Boolean вместо boolean для nullable)
    public Boolean getWasClarification() {
        return wasClarification != null ? wasClarification : false;
    }
    
    // parsedResult игнорируется DynamoDB — аннотация на getter
    @DynamoDbIgnore
    public ParsedCommand getParsedResult() {
        return parsedResult;
    }
}

