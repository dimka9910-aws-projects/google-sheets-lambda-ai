package com.github.dimka9910.sheets.ai.util;

import com.github.dimka9910.sheets.ai.dto.user.UserEntity;

/**
 * Centralized user-facing fallback messages.
 * Goal: never leak raw exception messages to users, and avoid hardcoded English-only replies.
 */
public final class UserFacingText {

    private UserFacingText() {}

    public static String detectLanguage(UserEntity userContext, String userMessage) {
        String preferred = userContext != null ? userContext.getPreferredLanguage() : null;
        if (preferred != null && !preferred.isBlank()) {
            String p = preferred.trim().toLowerCase();
            if (p.startsWith("ru")) return "ru";
            if (p.startsWith("en")) return "en";
            return p;
        }
        if (userMessage != null) {
            for (int i = 0; i < userMessage.length(); i++) {
                char ch = userMessage.charAt(i);
                if ((ch >= '\u0400' && ch <= '\u04FF') || (ch >= '\u0500' && ch <= '\u052F')) {
                    return "ru";
                }
            }
        }
        return "en";
    }

    public static String genericError(String lang) {
        if ("ru".equals(lang)) {
            return "Что-то пошло не так. Попробуй ещё раз.";
        }
        return "Something went wrong. Please try again.";
    }

    public static String internalError(String lang) {
        if ("ru".equals(lang)) {
            return "Внутренняя ошибка. Попробуй ещё раз через минуту.";
        }
        return "Internal error. Please try again in a moment.";
    }

    public static String emptyMessage(String lang) {
        if ("ru".equals(lang)) {
            return "Сообщение пустое — напиши, пожалуйста, что нужно записать.";
        }
        return "Your message is empty — please tell me what to record.";
    }

    public static String emptyModelResponse(String lang) {
        if ("ru".equals(lang)) {
            return "Не получил ответ от AI. Попробуй ещё раз.";
        }
        return "I didn't get a response from the AI. Please try again.";
    }
}


