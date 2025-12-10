package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.dto.ConversationMessage;
import com.github.dimka9910.sheets.ai.dto.UserContext;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Сервис для работы с историей диалога.
 * AI через MessageClassifier определяет: это новая команда или продолжение.
 * Здесь только управление историей.
 */
@Slf4j
public class ConversationService {

    private static final int MAX_HISTORY_MESSAGES = 20;

    /**
     * Добавляет сообщение в историю и обрезает если нужно
     */
    public void addToHistory(UserContext context, ConversationMessage message) {
        context.addToHistory(message);
        context.trimHistory(MAX_HISTORY_MESSAGES);
    }

    /**
     * Очищает историю диалога
     */
    public void clearHistory(UserContext context) {
        context.clearHistory();
        log.debug("Conversation history cleared for user {}", context.getUserId());
    }

    /**
     * Gets the last bot message from history (for response detection).
     */
    public String getLastBotMessage(UserContext context) {
        List<ConversationMessage> history = context.getConversationHistory();
        if (history == null || history.isEmpty()) {
            return null;
        }
        
        // Find last assistant message
        for (int i = history.size() - 1; i >= 0; i--) {
            ConversationMessage msg = history.get(i);
            if ("assistant".equals(msg.getRole())) {
                return msg.getContent();
            }
        }
        return null;
    }

    /**
     * Строит контекст истории для промпта
     */
    public String buildHistoryContext(UserContext context) {
        List<ConversationMessage> history = context.getConversationHistory();
        if (history == null || history.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("\n### Recent conversation (for context) ###\n");
        
        for (ConversationMessage msg : history) {
            String role = "user".equals(msg.getRole()) ? "User" : "Assistant";
            sb.append(role).append(": ").append(msg.getContent()).append("\n");
        }
        
        return sb.toString();
    }
}

