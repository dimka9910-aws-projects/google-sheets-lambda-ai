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
        
        // /setting — show full user context (all accounts, funds, custom instructions, etc.)
        if (msgLower.equals("/setting") || msgLower.equals("/settings") || msgLower.equals("/context")) {
            String fullContext = formatUserSettings(userContext);
            return TelegramChatResponse.builder()
                    .chatId(chatId)
                    .success(true)
                    .message(fullContext)
                    .build();
        }
        
        return null; // Not an admin command
    }

    private static final String INFO_MESSAGE = """
            🛠️ Admin Commands:
            
            /reset     — delete user and start fresh
            /note TEXT — save note to logs for developer
            /setting   — show full user context (accounts, funds, instructions)
            /info      — show this help
            """;
    
    /**
     * Format user settings into a human-readable string.
     */
    private String formatUserSettings(UserEntity user) {
        if (user == null) {
            return "❌ User context not found.";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("⚙️ USER SETTINGS\n");
        sb.append("═══════════════════════════════════════\n\n");
        
        // User Info
        sb.append("👤 USER INFO:\n");
        sb.append("  • Username: ").append(user.getUserName()).append("\n");
        sb.append("  • Display Name: ").append(user.getDisplayName() != null ? user.getDisplayName() : "N/A").append("\n");
        sb.append("  • Telegram ID: ").append(user.getTelegramId()).append("\n");
        sb.append("  • Preferred Language: ").append(user.getPreferredLanguage() != null ? user.getPreferredLanguage() : "N/A").append("\n");
        sb.append("  • Created: ").append(user.getCreatedAt() != null ? user.getCreatedAt().toString() : "N/A").append("\n");
        sb.append("\n");
        
        // Defaults
        sb.append("🎯 DEFAULTS:\n");
        sb.append("  • Default Currency: ").append(user.getDefaultCurrency() != null ? user.getDefaultCurrency() : "N/A").append("\n");
        
        if (user.getDefaultAccount() != null) {
            sb.append("  • Default Account: ").append(user.getDefaultAccount().getAccountId());
            if (user.getDefaultAccount().getDisplayName() != null) {
                sb.append(" (").append(user.getDefaultAccount().getDisplayName()).append(")");
            }
            sb.append("\n");
        } else {
            sb.append("  • Default Account: N/A\n");
        }
        
        if (user.getDefaultFund() != null) {
            sb.append("  • Default Fund: ").append(user.getDefaultFund().getFundId());
            if (user.getDefaultFund().getDisplayName() != null) {
                sb.append(" (").append(user.getDefaultFund().getDisplayName()).append(")");
            }
            sb.append("\n");
        } else {
            sb.append("  • Default Fund: N/A\n");
        }
        sb.append("\n");
        
        // Accounts
        sb.append("💳 ACCOUNTS (").append(user.getAccounts() != null ? user.getAccounts().size() : 0).append("):\n");
        if (user.getAccounts() != null && !user.getAccounts().isEmpty()) {
            for (var acc : user.getAccounts()) {
                sb.append("  • ").append(acc.getAccountId());
                if (acc.getDisplayName() != null) {
                    sb.append(" → ").append(acc.getDisplayName());
                }
                if (acc.getAliases() != null && !acc.getAliases().isEmpty()) {
                    sb.append(" [aliases: ").append(String.join(", ", acc.getAliases())).append("]");
                }
                sb.append("\n");
            }
        } else {
            sb.append("  (none)\n");
        }
        sb.append("\n");
        
        // Funds
        sb.append("📊 FUNDS (").append(user.getFunds() != null ? user.getFunds().size() : 0).append("):\n");
        if (user.getFunds() != null && !user.getFunds().isEmpty()) {
            for (var fund : user.getFunds()) {
                sb.append("  • ").append(fund.getFundId());
                if (fund.getDisplayName() != null) {
                    sb.append(" → ").append(fund.getDisplayName());
                }
                if (fund.getAliases() != null && !fund.getAliases().isEmpty()) {
                    sb.append(" [aliases: ").append(String.join(", ", fund.getAliases())).append("]");
                }
                sb.append("\n");
            }
        } else {
            sb.append("  (none)\n");
        }
        sb.append("\n");
        
        // Linked Users
        sb.append("👥 LINKED USERS (").append(user.getLinkedUsers() != null ? user.getLinkedUsers().size() : 0).append("):\n");
        if (user.getLinkedUsers() != null && !user.getLinkedUsers().isEmpty()) {
            for (var linked : user.getLinkedUsers()) {
                sb.append("  • ").append(linked.getUserName());
                if (linked.getDisplayName() != null) {
                    sb.append(" → ").append(linked.getDisplayName());
                }
                if (linked.getAliases() != null && !linked.getAliases().isEmpty()) {
                    sb.append(" [aliases: ").append(String.join(", ", linked.getAliases())).append("]");
                }
                sb.append("\n");
            }
        } else {
            sb.append("  (none)\n");
        }
        sb.append("\n");
        
        // Custom Instructions
        sb.append("📝 CUSTOM INSTRUCTIONS (").append(user.getCustomInstructions() != null ? user.getCustomInstructions().size() : 0).append("):\n");
        if (user.getCustomInstructions() != null && !user.getCustomInstructions().isEmpty()) {
            int idx = 1;
            for (String instr : user.getCustomInstructions()) {
                sb.append("  ").append(idx++).append(". ").append(instr).append("\n");
            }
        } else {
            sb.append("  (none)\n");
        }
        sb.append("\n");
        
        // Pending Actions
        sb.append("⏳ PENDING CLARIFICATIONS: ");
        if (user.getPendingActions() != null && !user.getPendingActions().isEmpty()) {
            sb.append(user.getPendingActions().size()).append("\n");
            for (var pending : user.getPendingActions()) {
                sb.append("  • ").append(pending.getContext()).append("\n");
            }
        } else {
            sb.append("none\n");
        }
        sb.append("\n");
        
        // Conversation History
        sb.append("💬 CONVERSATION HISTORY: ");
        if (user.getConversationHistory() != null) {
            sb.append(user.getConversationHistory().size()).append(" messages\n");
        } else {
            sb.append("0 messages\n");
        }
        
        sb.append("\n═══════════════════════════════════════");
        
        return sb.toString();
    }
}
