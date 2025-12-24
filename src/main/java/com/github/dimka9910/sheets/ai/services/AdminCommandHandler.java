package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.dto.telegram.TelegramChatRequest;
import com.github.dimka9910.sheets.ai.dto.telegram.TelegramChatResponse;
import com.github.dimka9910.sheets.ai.dto.user.UserEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Spring Service for handling admin/debug commands (/debug, /reset, /info, /note).
 * These are NOT processed by AI — direct system commands.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminCommandHandler {

    private final UserEntityService userContextService;

    /**
     * Try to handle admin command.
     * @return TelegramChatResponse if handled, null if not an admin command
     */
    public TelegramChatResponse handle(TelegramChatRequest request, String message, UserEntity userContext) {
        if (!message.startsWith("/")) {
            return null;
        }
        
        String chatId = request.getResponseChatId();
        String userName = request.getUserName();
        String msgLower = message.toLowerCase().trim();
        
        // /info — show available commands
        if (msgLower.equals("/info") || msgLower.equals("/help") || msgLower.equals("/commands")) {
            return TelegramChatResponse.builder()
                    .chatId(chatId)
                    .success(true)
                    .message(INFO_MESSAGE)
                    .build();
        }
        
        // /reset — delete user
        if (msgLower.equals("/reset") || msgLower.equals("/restart") || msgLower.equals("/clear")) {
            if (userName != null) {
                userContextService.deleteUser(userName);
                log.info("[ADMIN] User {} deleted by /reset command", userName);
            }
            return TelegramChatResponse.builder()
                    .chatId(chatId)
                    .success(true)
                    .message("🗑️ User deleted. Send any message to start fresh!")
                    .build();
        }
        
        // /note TEXT — save note to logs
        if (msgLower.startsWith("/note")) {
            String note = message.substring(5).trim();
            log.warn("[USER_FEEDBACK] user={} note={}", userName, note);
            return TelegramChatResponse.builder()
                    .chatId(chatId)
                    .success(true)
                    .message("📝 Noted! (saved to logs for developer)")
                    .build();
        }
        
        return null; // Not an admin command
    }

    private static final String INFO_MESSAGE = """
            🛠️ Admin Commands:
            
            /reset     — delete user and start fresh
            /note TEXT — save note to logs for developer
            /info      — show this help
            """;
}
