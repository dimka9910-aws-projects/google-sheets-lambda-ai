package com.github.dimka9910.sheets.ai.dto.actions;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Response from MainAgent.
 * 
 * Structure:
 * {
 *   "actions": [...],      // List of actions to perform
 *   "response": "..."      // Message to show user
 * }
 * 
 * Example - expense recorded:
 * {
 *   "actions": [
 *     {"type": "FINANCIAL", "operationType": "EXPENSE", "amount": 500, ...}
 *   ],
 *   "response": "Записал: кофе 500 RSD"
 * }
 * 
 * Example - needs clarification:
 * {
 *   "actions": [
 *     {"type": "PENDING_CLARIFICATION", "context": "User wrote 'кофе'. Need: amount."}
 *   ],
 *   "response": "Сколько стоил кофе?"
 * }
 * 
 * Example - just chat:
 * {
 *   "actions": [],
 *   "response": "Привет! Чем помочь?"
 * }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MainAgentResponse {
    
    /**
     * List of actions to perform.
     * Can be empty for pure conversational responses.
     */
    @JsonPropertyDescription("List of actions to perform. Can include FINANCIAL (expenses/income/transfers), UTILS (settings commands), or PENDING_CLARIFICATION (questions to user). Empty array for pure conversational responses.")
    @Builder.Default
    private List<AgentAction> actions = new ArrayList<>();
    
    /**
     * Message to show user.
     * Model generates this based on what it understood and what actions it created.
     */
    @JsonPropertyDescription("Human-readable message to show the user. Should be a friendly confirmation (for successful actions), a clarifying question (for PENDING_CLARIFICATION), or a conversational response. Use the same language as the user's input.")
    private String response;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // Utility methods
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Check if response has any actions
     */
    public boolean hasActions() {
        return actions != null && !actions.isEmpty();
    }
    
    /**
     * Check if response has pending clarifications
     */
    public boolean hasPendingClarifications() {
        if (actions == null) return false;
        return actions.stream()
                .anyMatch(a -> a instanceof PendingClarificationAction);
    }
    
    /**
     * Get all pending clarifications
     */
    public List<PendingClarificationAction> getPendingClarifications() {
        if (actions == null) return List.of();
        return actions.stream()
                .filter(a -> a instanceof PendingClarificationAction)
                .map(a -> (PendingClarificationAction) a)
                .toList();
    }
    
    /**
     * Get all financial actions
     */
    public List<FinancialAction> getFinancialActions() {
        if (actions == null) return List.of();
        return actions.stream()
                .filter(a -> a instanceof FinancialAction)
                .map(a -> (FinancialAction) a)
                .toList();
    }
    
    /**
     * Get all utils actions
     */
    public List<UtilsAction> getUtilsActions() {
        if (actions == null) return List.of();
        return actions.stream()
                .filter(a -> a instanceof UtilsAction)
                .map(a -> (UtilsAction) a)
                .toList();
    }
    
    /**
     * Check if this is just a conversational response (no actions)
     */
    public boolean isConversationalOnly() {
        return !hasActions();
    }
}

