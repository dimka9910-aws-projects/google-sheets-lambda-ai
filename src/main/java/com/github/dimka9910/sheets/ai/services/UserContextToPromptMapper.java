package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.dto.actions.PendingClarificationAction;
import com.github.dimka9910.sheets.ai.dto.user.AccountEntry;
import com.github.dimka9910.sheets.ai.dto.user.ConversationMessage;
import com.github.dimka9910.sheets.ai.dto.user.FundEntry;
import com.github.dimka9910.sheets.ai.dto.user.LinkedUserEntry;
import com.github.dimka9910.sheets.ai.dto.user.UserEntity;
import com.github.dimka9910.sheets.ai.services.agents.MessageClassifierAgent.Category;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spring Component for mapping UserEntity to formatted prompt text for LLM agents.
 * 
 * Responsibilities:
 * - Format user context (accounts, funds, defaults, linked users) for LLM prompt
 * - Include conversation history
 * - Include pending clarifications
 * - Adapt output based on message classification tags
 * 
 * Single Responsibility: UserEntity → Prompt String conversion
 */
@Component
public class UserContextToPromptMapper {

    /**
     * Build formatted user context string for LLM prompt.
     * 
     * @param context UserEntity with all user data
     * @param category Classification category (e.g., COMPLEX_ACTION) to adapt output
     * @return Formatted context string ready to inject into LLM prompt
     */
    public String buildContextPrompt(UserEntity context, Category category) {
        StringBuilder ctx = new StringBuilder();
        ctx.append("\n\n### User Context ###\n");
        
        // Always show userName (needed for TRANSFER operations)
        ctx.append("Current user name: ").append(context.getUserName()).append("\n");
        
        if (context.getDisplayName() != null) {
            ctx.append("Display name: ").append(context.getDisplayName()).append("\n");
        }
        
        if (context.getPreferredLanguage() != null) {
            ctx.append("Language: ").append(context.getPreferredLanguage()).append("\n");
        }
        
        // Always show defaults, accounts, and funds
        appendDefaults(ctx, context);
        appendAccounts(ctx, context);
        appendFunds(ctx, context);
        
        // Show linked users with their accounts if available
        // For COMPLEX_ACTION, we always include them (don't know if needed yet)
        // For simpler categories, they won't reach MainAgent anyway
        if (context.getLinkedUsers() != null && !context.getLinkedUsers().isEmpty()) {
            appendLinkedUsers(ctx, context);
        }
        
        // Always show custom instructions if they exist
        appendCustomInstructions(ctx, context);
        
        // Show pending clarifications if any
        appendPendingClarifications(ctx, context);
        
        // Always show recent conversation - model can use it for context
        appendConversationHistory(ctx, context);
        
        return ctx.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private void appendDefaults(StringBuilder ctx, UserEntity context) {
        ctx.append("\n## Defaults:\n");
        ctx.append("- Currency: ").append(orNotSet(context.getDefaultCurrency())).append("\n");
        ctx.append("- Account: ").append(orNotSet(context.getDefaultAccount())).append("\n");
        ctx.append("- Fund: ").append(orNotSet(context.getDefaultFund())).append("\n");
    }

    private void appendAccounts(StringBuilder ctx, UserEntity context) {
        List<AccountEntry> accounts = context.getAccounts();
        if (accounts != null && !accounts.isEmpty()) {
            String accountsList = accounts.stream()
                    .map(this::formatAccountEntry)
                    .collect(Collectors.joining(", "));
            ctx.append("\n## Accounts: ").append(accountsList).append("\n");
        }
    }

    private void appendFunds(StringBuilder ctx, UserEntity context) {
        List<FundEntry> funds = context.getFunds();
        if (funds != null && !funds.isEmpty()) {
            String fundsList = funds.stream()
                    .map(this::formatFundEntry)
                    .collect(Collectors.joining(", "));
            ctx.append("## Funds: ").append(fundsList).append("\n");
        }
    }

    private void appendLinkedUsers(StringBuilder ctx, UserEntity context) {
        List<LinkedUserEntry> linkedUsers = context.getLinkedUsers();
        ctx.append("\n## Linked users:\n");
        Map<String, UserEntity> linkedContexts = context.getLinkedUserEntitys();
        
        for (LinkedUserEntry linkedUser : linkedUsers) {
            ctx.append("- **").append(linkedUser.getName()).append("**");
            if (linkedUser.getDisplayName() != null) {
                ctx.append(" (").append(linkedUser.getDisplayName()).append(")");
            }
            if (linkedUser.getAliases() != null && !linkedUser.getAliases().isEmpty()) {
                ctx.append(" [aliases: ").append(String.join(", ", linkedUser.getAliases())).append("]");
            }
            
            // Show their accounts if available
            if (linkedContexts != null && linkedContexts.containsKey(linkedUser.getName())) {
                UserEntity linked = linkedContexts.get(linkedUser.getName());
                List<AccountEntry> linkedAccounts = linked.getAccounts();
                if (linkedAccounts != null && !linkedAccounts.isEmpty()) {
                    String accountsList = linkedAccounts.stream()
                            .map(this::formatAccountEntry)
                            .collect(Collectors.joining(", "));
                    ctx.append(" — accounts: ").append(accountsList);
                }
            }
            ctx.append("\n");
        }
    }

    private void appendCustomInstructions(StringBuilder ctx, UserEntity context) {
        List<String> instructions = context.getCustomInstructions();
        if (instructions != null && !instructions.isEmpty()) {
            ctx.append("\n## Custom Instructions:\n");
            for (int i = 0; i < instructions.size(); i++) {
                ctx.append("[").append(i).append("] ").append(instructions.get(i)).append("\n");
            }
        }
    }

    private void appendPendingClarifications(StringBuilder ctx, UserEntity context) {
        List<PendingClarificationAction> pendingActions = context.getPendingActions();
        if (pendingActions != null && !pendingActions.isEmpty()) {
            ctx.append("\n## Pending Clarifications (from previous request):\n");
            for (int i = 0; i < pendingActions.size(); i++) {
                ctx.append("[").append(i).append("] ").append(pendingActions.get(i).getContext()).append("\n");
            }
            ctx.append("→ Try to resolve these with user's new message, or replace/clear if topic changed.\n");
        }
    }

    private void appendConversationHistory(StringBuilder ctx, UserEntity context) {
        List<ConversationMessage> history = context.getConversationHistory();
        if (history != null && !history.isEmpty()) {
            ctx.append("\n## Recent Conversation:\n");
            int start = Math.max(0, history.size() - 4); // last 4 messages
            for (int i = start; i < history.size(); i++) {
                ConversationMessage msg = history.get(i);
                String role = "user".equals(msg.getRole()) ? "User" : "Bot";
                ctx.append(role).append(": ").append(msg.getContent()).append("\n");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FORMATTERS
    // ═══════════════════════════════════════════════════════════════════════════

    private String formatAccountEntry(AccountEntry a) {
        StringBuilder sb = new StringBuilder(a.getAccountId());
        if (a.getDisplayName() != null) {
            sb.append(" (").append(a.getDisplayName()).append(")");
        }
        if (a.getAliases() != null && !a.getAliases().isEmpty()) {
            sb.append(" [aliases: ").append(String.join(", ", a.getAliases())).append("]");
        }
        return sb.toString();
    }

    private String formatFundEntry(FundEntry f) {
        StringBuilder sb = new StringBuilder(f.getFundId());
        if (f.getDisplayName() != null) {
            sb.append(" (").append(f.getDisplayName()).append(")");
        }
        if (f.getAliases() != null && !f.getAliases().isEmpty()) {
            sb.append(" [aliases: ").append(String.join(", ", f.getAliases())).append("]");
        }
        return sb.toString();
    }

    private String orNotSet(String value) {
        return value != null ? value : "not set";
    }
}

