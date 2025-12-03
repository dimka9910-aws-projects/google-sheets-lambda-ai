package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.dto.ConversationMessage;
import com.github.dimka9910.sheets.ai.dto.UserContext;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Сервис для работы с историей диалога.
 * Определяет: это новая команда или продолжение диалога?
 * 
 * ВАЖНО: НЕ используем regex для определения типа команды!
 * Тип команды (финансовая/мета) определяет AI.
 * Здесь только логика для истории диалога.
 */
@Slf4j
public class ConversationService {

    private static final int MAX_HISTORY_MESSAGES = 20;

    /**
     * Определяет, является ли сообщение новой командой или ответом на уточнение.
     * НЕ определяет тип команды — это делает AI!
     * 
     * @return true если новая команда (нужно очистить историю)
     */
    public boolean isNewCommand(String message, UserContext context) {
        String trimmed = message.trim();
        
        // 1. Если история пустая — новая команда
        List<ConversationMessage> history = context.getConversationHistory();
        if (history == null || history.isEmpty()) {
            log.debug("Empty history, starting new context");
            return true;
        }
        
        // 2. Если последний ответ был уточняющим вопросом
        if (context.isAwaitingClarification()) {
            // Короткое сообщение после уточнения — скорее всего ответ
            if (looksLikeAnswer(trimmed)) {
                log.debug("Looks like answer to clarification, continuing context");
                return false;  // Продолжаем диалог
            }
        }
        
        // 3. По умолчанию — новая команда
        log.debug("Default: treating as new command");
        return true;
    }

    /**
     * Проверяет, похоже ли сообщение на ответ (короткое сообщение).
     * НЕ парсит содержимое — просто проверяет длину и количество слов.
     */
    public boolean looksLikeAnswer(String message) {
        String trimmed = message.trim();
        
        // Короткое сообщение (< 50 символов и <= 5 слов)
        // Это может быть ответ на уточнение: "рубли", "карта", "да", "500" и т.д.
        return trimmed.length() < 50 && trimmed.split("\\s+").length <= 5;
    }

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

