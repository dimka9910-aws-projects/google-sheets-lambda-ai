package com.github.dimka9910.sheets.ai.dto.actions;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Pending clarification action.
 * Model uses this to remember what it needs to clarify with user.
 * 
 * This is just a text prompt - model describes what's unclear in its own words.
 * On next request, model sees this context and tries to resolve it.
 * 
 * Example JSON:
 * {
 *   "type": "PENDING_CLARIFICATION",
 *   "context": "User wrote 'кофе'. Need to clarify: amount."
 * }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingClarificationAction {
    
    /**
     * Model's description of what needs clarification.
     * Free-form text that model writes to remind itself what's unclear.
     * 
     * Examples:
     * - "User wrote 'кофе'. Need: amount."
     * - "Transfer to Kiki requested. Need: amount and source account."
     * - "User wants to set default currency but didn't specify which one."
     */
    private String context;
}

