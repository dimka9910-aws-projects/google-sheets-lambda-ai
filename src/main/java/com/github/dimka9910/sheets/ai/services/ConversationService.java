package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.dto.ConversationMessage;
import com.github.dimka9910.sheets.ai.dto.UserContext;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Сервис для работы с историей диалога.
 * Определяет: это новая команда или продолжение диалога?
 */
@Slf4j
public class ConversationService {

    private static final int MAX_HISTORY_MESSAGES = 20;
    
    // Паттерны мета-команд (всегда начинают новый контекст)
    private static final Pattern META_COMMAND_PATTERN = Pattern.compile(
            "(?i)(запомни|помни|установи|поставь|покажи настройки|мои настройки|забудь|очисти)",
            Pattern.UNICODE_CASE
    );
    
    // Паттерны коротких ответов (вероятно ответ на уточнение)
    private static final Pattern SHORT_ANSWER_PATTERN = Pattern.compile(
            "(?i)^(да|нет|ок|окей|первый|второй|третий|1|2|3|\\d+|[а-яa-z]+)$",
            Pattern.UNICODE_CASE
    );
    
    // Паттерны чисел/сумм
    private static final Pattern NUMBER_PATTERN = Pattern.compile(
            "^\\d+([.,]\\d+)?\\s*(к|k|тыс|руб|rub|rsd|eur|usd)?$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    /**
     * Определяет, является ли сообщение новой командой или ответом на уточнение.
     * 
     * @return true если новая команда (нужно очистить историю)
     */
    public boolean isNewCommand(String message, UserContext context) {
        String trimmed = message.trim();
        
        // 1. Мета-команды ВСЕГДА новая команда
        if (isMetaCommand(trimmed)) {
            log.debug("Meta command detected, starting new context");
            return true;
        }
        
        // 2. Если история пустая — новая команда
        List<ConversationMessage> history = context.getConversationHistory();
        if (history == null || history.isEmpty()) {
            log.debug("Empty history, starting new context");
            return true;
        }
        
        // 3. Если последний ответ был уточняющим вопросом
        if (context.isAwaitingClarification()) {
            // Проверяем, похоже ли это на ответ
            if (looksLikeAnswer(trimmed)) {
                log.debug("Looks like answer to clarification, continuing context");
                return false;  // Продолжаем диалог
            }
        }
        
        // 4. По умолчанию — новая команда
        log.debug("Default: treating as new command");
        return true;
    }

    /**
     * Проверяет, является ли сообщение мета-командой
     */
    public boolean isMetaCommand(String message) {
        return META_COMMAND_PATTERN.matcher(message).find();
    }

    /**
     * Проверяет, похоже ли сообщение на ответ (короткое, число, да/нет)
     */
    public boolean looksLikeAnswer(String message) {
        String trimmed = message.trim();
        
        // Короткое сообщение (< 30 символов)
        if (trimmed.length() < 30) {
            // Число или сумма
            if (NUMBER_PATTERN.matcher(trimmed).matches()) {
                return true;
            }
            // Короткий ответ (да/нет/выбор)
            if (SHORT_ANSWER_PATTERN.matcher(trimmed).matches()) {
                return true;
            }
            // Просто короткое (1-3 слова)
            if (trimmed.split("\\s+").length <= 3) {
                return true;
            }
        }
        
        return false;
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

