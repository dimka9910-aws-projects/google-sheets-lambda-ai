package com.github.dimka9910.sheets.ai.dto.user;

import com.github.dimka9910.sheets.ai.dto.actions.FinancialAction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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
    
    // FinancialAction объекты созданные как результат этого сообщения
    // (для аудита, отмены, и контекста для corrections)
    private List<FinancialAction> relatedFinancialActions;
    
    public static ConversationMessage userMessage(String content) {
        return ConversationMessage.builder()
                .role("user")
                .content(content)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}

