package com.github.dimka9910.sheets.ai.dto.telegram;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Incoming request from Telegram bot or Web app.
 * 
 * From Telegram: telegramUserId + telegramChatId (or legacy userId/chatId)
 * From Web (future): userName directly
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TelegramChatRequest {
    
    // Telegram fields (used when request comes from Telegram)
    // JsonAlias for backward compatibility with Telegram Bot sending old field names
    @JsonAlias({"userId", "user_id"})
    private String telegramUserId;    // Telegram User ID (e.g., "377662506")
    
    @JsonAlias({"chatId", "chat_id"})
    private String telegramChatId;    // Telegram Chat ID
    
    // System user name (resolved from telegramUserId or provided directly)
    private String userName;          // e.g., "DIMA" — primary identifier
    
    // Message
    private String message;           // User's message text
    
    /**
     * Get chat ID for sending response.
     * In private chats, chatId == userId.
     */
    @JsonIgnore
    public String getResponseChatId() {
        if (telegramChatId != null) {
            return telegramChatId;
        }
        return telegramUserId;
    }
}

