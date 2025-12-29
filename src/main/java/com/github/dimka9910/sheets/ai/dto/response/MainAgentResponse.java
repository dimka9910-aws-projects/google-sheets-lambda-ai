package com.github.dimka9910.sheets.ai.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.github.dimka9910.sheets.ai.dto.actions.PendingClarificationAction;
import com.github.dimka9910.sheets.ai.dto.actions.RedirectToAgentAction;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Response from MainAgent.
 * 
 * Contains list of redirect actions to specialized agents.
 * 
 * Examples:
 * 
 * 1. Redirect to SimpleExpenseAgent:
 * {
 *   "redirects": [
 *     {"agentType": "SIMPLE_EXPENSE", "message": "200 on coffee"}
 *   ],
 *   "message": "Processing expense..."
 * }
 * 
 * 2. Redirect to CustomInstructionAgent:
 * {
 *   "redirects": [
 *     {"agentType": "CUSTOM_INSTRUCTION", "message": "add alias 'kiki' for linked user KIKI"}
 *   ],
 *   "message": "Updating settings..."
 * }
 * 
 * 3. Ask clarification:
 * {
 *   "redirects": [],
 *   "pendingClarifications": [
 *     {"context": "User wrote 'coffee'. Need: amount."}
 *   ],
 *   "message": "How much did the coffee cost?"
 * }
 * 
 * 4. Pure conversation:
 * {
 *   "redirects": [],
 *   "message": "Hello! How can I help you?"
 * }
 */
@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class MainAgentResponse extends BaseAgentResponse {
    
    /**
     * List of redirect actions to specialized agents.
     */
    @JsonPropertyDescription("List of redirects to specialized agents: SIMPLE_EXPENSE, INTERNAL_TRANSFER, THIRD_PARTY_ACTION, EXPENSE_EDIT_AND_DELETION, CUSTOM_INSTRUCTION. Empty array for conversational responses or when asking clarifications.")
    private List<RedirectToAgentAction> redirects;
    
    /**
     * Constructor for Jackson
     */
    public MainAgentResponse(String message,
                             List<PendingClarificationAction> pendingClarifications,
                             List<RedirectToAgentAction> redirects) {
        super(message, pendingClarifications);
        this.redirects = redirects != null ? redirects : new ArrayList<>();
    }
    
    /**
     * Check if response has redirects
     */
    public boolean hasRedirects() {
        return redirects != null && !redirects.isEmpty();
    }
    
    /**
     * Check if this is just a conversational response (no redirects, no pending clarifications)
     */
    public boolean isConversationalOnly() {
        return !hasRedirects() && !hasPendingClarifications();
    }
}

