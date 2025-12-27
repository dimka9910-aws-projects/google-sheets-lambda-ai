package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.dto.actions.FinancialAction;
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
import java.util.Objects;
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
        ctx.append(formatDefaultsSection(context));
        ctx.append(formatAccountsSection(context));
        ctx.append(formatFundsSection(context));
        
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
            
            ctx.append("\n");
            
            // Show their accounts if available (indented)
            if (linkedContexts != null && linkedContexts.containsKey(linkedUser.getName())) {
                UserEntity linked = linkedContexts.get(linkedUser.getName());
                List<AccountEntry> linkedAccounts = linked.getAccounts();
                if (linkedAccounts != null && !linkedAccounts.isEmpty()) {
                    for (AccountEntry account : linkedAccounts) {
                        ctx.append("  - ").append(formatAccountEntry(account)).append("\n");
                    }
                }
            }
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
            // Use new detailed formatter that includes FinancialAction objects
            ctx.append(formatConversationHistoryWithActions(history, 10)); // last 10 messages
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

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC FORMATTERS (for use in lightweight agents)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Format accounts list as multi-line text for prompts.
     * Each account on a new line with "- " prefix.
     */
    public String formatAccountsList(List<AccountEntry> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return "(No accounts configured)";
        }

        return accounts.stream()
                .map(account -> "- " + formatAccountEntry(account))
                .collect(Collectors.joining("\n"));
    }

    /**
     * Format funds list as multi-line text for prompts.
     * Each fund on a new line with "- " prefix.
     */
    public String formatFundsList(List<FundEntry> funds) {
        if (funds == null || funds.isEmpty()) {
            return "(No funds configured)";
        }

        return funds.stream()
                .map(fund -> "- " + formatFundEntry(fund))
                .collect(Collectors.joining("\n"));
    }

    /**
     * Format linked users list as multi-line text for prompts.
     * Each linked user on a new line with "- " prefix.
     * Shows userName (EXACT userName to use in actions), displayName, and aliases.
     */
    public String formatLinkedUsersList(List<LinkedUserEntry> linkedUsers) {
        if (linkedUsers == null || linkedUsers.isEmpty()) {
            return "(No linked users configured)";
        }

        return linkedUsers.stream()
                .map(lu -> "- " + formatLinkedUserEntry(lu))
                .collect(Collectors.joining("\n"));
    }

    private String formatLinkedUserEntry(LinkedUserEntry lu) {
        StringBuilder sb = new StringBuilder();
        
        // Show userName (this is the EXACT userName to use in FINANCIAL actions!)
        sb.append("**").append(lu.getUserName()).append("**");
        
        // Show display name if different
        if (lu.getDisplayName() != null && !lu.getDisplayName().equals(lu.getUserName())) {
            sb.append(" (").append(lu.getDisplayName()).append(")");
        }
        
        // Show aliases
        if (lu.getAliases() != null && !lu.getAliases().isEmpty()) {
            sb.append(" [aliases: ").append(String.join(", ", lu.getAliases())).append("]");
        }
        
        return sb.toString();
    }


  /**
   * Format custom instructions as a complete, numbered section for prompts.
   * Numbered lists are preferred for strict rule adherence by LLMs.
   */
  public String formatCustomInstructionsSection(List<String> instructions) {
    if (instructions == null || instructions.isEmpty()) {
      return "";
    }

    // Pre-filter the list to remove nulls, empty strings, or blank instructions
    List<String> validInstructions = instructions.stream()
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toList();

    if (validInstructions.isEmpty()) {
      return "";
    }

    StringBuilder sb = new StringBuilder();
    // Using high-priority headers to ensure the LLM prioritizes this block
    sb.append("\n## Custom User Instructions (STRICT PRIORITY)\n");
    sb.append("Apply these specific user rules before any standard logic:\n");

    for (int i = 0; i < validInstructions.size(); i++) {
      // Numbered lists are treated by the model as a strict sequence of rules
      sb.append(String.format("%d. %s\n", i + 1, validInstructions.get(i)));
    }

    // Add a trailing newline to ensure clean separation from subsequent prompt sections
    sb.append("\n");
    return sb.toString();
  }

    /**
     * Format defaults section as a complete prompt section.
     */
    public String formatDefaultsSection(UserEntity context) {
        StringBuilder sb = new StringBuilder("\n## Defaults\n");
        sb.append("Currency: ").append(orNotSet(context.getDefaultCurrency())).append("\n");
        sb.append("Account: ").append(
                context.getDefaultAccount() != null ? context.getDefaultAccount().getAccountId() : "not set"
        ).append("\n");
        sb.append("Fund: ").append(
                context.getDefaultFund() != null ? context.getDefaultFund().getFundId() : "not set"
        ).append("\n");
        return sb.toString();
    }

    /**
     * Format accounts section as a complete prompt section.
     */
    public String formatAccountsSection(UserEntity context) {
        return "\n## Accounts\n" + formatAccountsList(context.getAccounts()) + "\n";
    }

    /**
     * Format funds section as a complete prompt section.
     */
    public String formatFundsSection(UserEntity context) {
        return "\n## Funds\n" + formatFundsList(context.getFunds()) + "\n";
    }
    
    /**
     * Format conversation history with Financial Actions for MainAgent.
     * Shows last N messages with their related financial operations.
     * This enables context-aware corrections and references to previous operations.
     */
    public String formatConversationHistoryWithActions(List<ConversationMessage> messages, int limit) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder("\n## Recent Conversation History\n\n");
        
        // Take last N messages
        int startIndex = Math.max(0, messages.size() - limit);
        List<ConversationMessage> recentMessages = messages.subList(startIndex, messages.size());
        
        for (ConversationMessage msg : recentMessages) {
            // Format message header with timestamp
            sb.append("**").append(msg.getRole().toUpperCase()).append("**");
            
            // Add timestamp (relative time: "2 minutes ago", "just now")
            if (msg.getTimestamp() != null) {
                String timeAgo = formatTimeAgo(msg.getTimestamp());
                sb.append(" (").append(timeAgo).append(")");
            }
            sb.append(": ");
            sb.append(truncate(msg.getContent(), 200)).append("\n");
            
            // If assistant message has related financial actions, show them
            if ("assistant".equals(msg.getRole()) && 
                msg.getRelatedFinancialActions() != null && 
                !msg.getRelatedFinancialActions().isEmpty()) {
                
                sb.append("  → Created operations:\n");
                for (var action : msg.getRelatedFinancialActions()) {
                    sb.append("    • ID: ").append(action.getId()).append("\n");
                    sb.append("      Type: ").append(action.getOperationType()).append("\n");
                    sb.append("      Amount: ").append(action.getAmount())
                      .append(" ").append(action.getCurrency()).append("\n");
                    sb.append("      Account: ").append(action.getAccount()).append("\n");
                    if (action.getFund() != null) {
                        sb.append("      Fund: ").append(action.getFund()).append("\n");
                    }
                    if (action.getTargetAccount() != null) {
                        sb.append("      Target Account: ").append(action.getTargetAccount()).append("\n");
                    }
                    if (action.getComment() != null && !action.getComment().isBlank()) {
                        sb.append("      Comment: ").append(action.getComment()).append("\n");
                    }
                }
            }
            sb.append("\n");
        }
        
        return sb.toString();
    }
    
    /**
     * Format pending actions as a numbered list.
     * Used when user has unresolved clarification requests.
     */
    public String formatPendingActionsList(List<PendingClarificationAction> pendingActions) {
        if (pendingActions == null || pendingActions.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pendingActions.size(); i++) {
            sb.append(i + 1).append(". ")
              .append(pendingActions.get(i).getContext())
              .append("\n");
        }
        return sb.toString();
    }
    
    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
    
    /**
     * Format timestamp as relative time: "just now", "2 minutes ago", etc.
     */
    private String formatTimeAgo(long timestamp) {
        long now = System.currentTimeMillis();
        long diffMs = now - timestamp;
        
        if (diffMs < 0) {
            return "just now"; // future timestamp (clock skew)
        }
        
        long seconds = diffMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        
        if (seconds < 60) {
            return "just now";
        } else if (minutes < 60) {
            return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
        } else if (hours < 24) {
            return hours + (hours == 1 ? " hour ago" : " hours ago");
        } else {
            return days + (days == 1 ? " day ago" : " days ago");
        }
    }
}

