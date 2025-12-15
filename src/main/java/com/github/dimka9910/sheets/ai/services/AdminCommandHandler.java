package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.dto.*;
import com.github.dimka9910.sheets.ai.dto.user.UserEntity;
import lombok.extern.slf4j.Slf4j;

/**
 * Handles admin/debug commands (/debug, /reset, /info, /note).
 * These are NOT processed by AI — direct system commands.
 */
@Slf4j
public class AdminCommandHandler {

    private final UserEntityService userContextService;

    public AdminCommandHandler(UserEntityService userContextService) {
        this.userContextService = userContextService;
    }

    /**
     * Try to handle admin command.
     * @return ChatResponse if handled, null if not an admin command
     */
    public ChatResponse handle(ChatRequest request, String message, UserEntity userContext) {
        if (!message.startsWith("/")) {
            return null;
        }
        
        String chatId = request.getResponseChatId();
        String userName = request.getUserName();
        String msgLower = message.toLowerCase().trim();
        
        // /info — show available commands
        if (msgLower.equals("/info") || msgLower.equals("/help") || msgLower.equals("/commands")) {
            return ChatResponse.builder()
                    .chatId(chatId)
                    .success(true)
                    .message(INFO_MESSAGE)
                    .build();
        }
        
        // /debug on|off
        if (msgLower.startsWith("/debug")) {
            return handleDebugToggle(chatId, msgLower, userContext);
        }
        
        // /reset — delete user
        if (msgLower.equals("/reset") || msgLower.equals("/restart") || msgLower.equals("/clear")) {
            if (userName != null) {
                userContextService.deleteUser(userName);
                log.info("[ADMIN] User {} deleted by /reset command", userName);
            }
            return ChatResponse.builder()
                    .chatId(chatId)
                    .success(true)
                    .message("🗑️ User deleted. Send any message to start fresh!")
                    .build();
        }
        
        // /note TEXT — save note to logs
        if (msgLower.startsWith("/note")) {
            String note = message.substring(5).trim();
            log.warn("[USER_FEEDBACK] user={} note={}", userName, note);
            return ChatResponse.builder()
                    .chatId(chatId)
                    .success(true)
                    .message("📝 Noted! (saved to logs for developer)")
                    .build();
        }
        
        return null; // Not an admin command
    }

    private ChatResponse handleDebugToggle(String chatId, String msgLower, UserEntity userContext) {
        String arg = msgLower.replace("/debug", "").trim();
        
        if (arg.equals("on") || arg.equals("1") || arg.equals("true")) {
            userContext.setDebugMode(true);
            userContextService.saveContext(userContext);
            return ChatResponse.builder()
                    .chatId(chatId)
                    .success(true)
                    .message("🔧 Debug mode ON — you'll see internal data with each response")
                    .build();
        }
        
        if (arg.equals("off") || arg.equals("0") || arg.equals("false")) {
            userContext.setDebugMode(false);
            userContextService.saveContext(userContext);
            return ChatResponse.builder()
                    .chatId(chatId)
                    .success(true)
                    .message("🔧 Debug mode OFF")
                    .build();
        }
        
        String status = Boolean.TRUE.equals(userContext.getDebugMode()) ? "ON" : "OFF";
        return ChatResponse.builder()
                .chatId(chatId)
                .success(true)
                .message("🔧 Debug mode: " + status + "\nUse: /debug on or /debug off")
                .build();
    }

    private static final String INFO_MESSAGE = """
            🛠️ Admin Commands:
            
            /debug on  — enable debug mode (show internal data)
            /debug off — disable debug mode
            /reset     — delete user and start fresh
            /note TEXT — save note to logs for developer
            /info      — show this help
            """;
}
