package com.github.dimka9910.sheets.ai.dto.actions;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Action to redirect the request to a specialized lightweight agent.
 * 
 * MainAgent can decide that a request is better handled by a specialized agent
 * (e.g., SimpleExpenseAgent for simple expenses, CustomInstructionAgent for settings).
 * 
 * This allows MainAgent to offload simple requests to faster, cheaper agents
 * while handling complex multi-action scenarios itself.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RedirectToAgentAction {
    
    /**
     * Type of agent to redirect to.
     */
    @JsonPropertyDescription("Type of specialized agent to handle this request. Options: CUSTOM_INSTRUCTION (for settings/preferences), SIMPLE_EXPENSE (for straightforward expenses), INTERNAL_TRANSFER (for transfers between own accounts), THIRD_PARTY_ACTION (for operations with linked users), CORRECTION (for modifying or deleting existing operations).")
    private AgentType agentType;
    
    /**
     * Original user message to pass to the specialized agent.
     * Can be the same as original message or a refined/simplified version.
     */
    @JsonPropertyDescription("The message to pass to the specialized agent. Usually the original user message, or a simplified/refined version if context was clarified.")
    private String message;
    
    /**
     * Optional context or reason for redirect (for debugging/logging).
     */
    @JsonPropertyDescription("Optional explanation of why this request is being redirected (for debugging). Example: 'Simple expense, no complexity'.")
    private String reason;
    
    /**
     * Enum of specialized agent types.
     */
    public enum AgentType {
        /**
         * CustomInstructionAgent - handles settings, preferences, defaults.
         * Examples: "set default currency to USD", "add account CARD_MAIN"
         */
        CUSTOM_INSTRUCTION,
        
        /**
         * SimpleExpenseAgent - handles straightforward single expenses.
         * Examples: "200 on coffee", "bought groceries 1500"
         */
        SIMPLE_EXPENSE,
        
        /**
         * InternalTransferAgent - handles transfers between user's own accounts.
         * Examples: "transfer 1000 from card to cash", "withdrew 500"
         */
        INTERNAL_TRANSFER,
        
        /**
         * ThirdPartyActionAgent - handles operations with linked users.
         * Examples: "sent 500 to BOB", "bought coffee for girlfriend 200"
         */
        THIRD_PARTY_ACTION,
        
        /**
         * ExpenseEditAndDeletionAgent - handles corrections (MODIFY/DELETE) of existing operations.
         * Examples: "not 200 but 300", "change to FOOD fund", "delete last"
         */
        CORRECTION
    }
}

