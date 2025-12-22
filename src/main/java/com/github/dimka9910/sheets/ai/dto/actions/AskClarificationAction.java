package com.github.dimka9910.sheets.ai.dto.actions;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Action to ask user for clarification before making changes.
 * Used when agent is uncertain or detects potential conflicts.
 * 
 * Note: This is a separate action type, not an InstructionAction.
 * It has different fields and handling logic.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AskClarificationAction implements CustomInstructionActionBase {
    
    public static final String TYPE = "ASK_CLARIFICATION";
    
    private String actionType;  // Always "ASK_CLARIFICATION"
    private String question;    // Question to ask user
    private String context;     // Internal context for next resolution
    
    public String getActionType() {
        return actionType != null ? actionType : TYPE;
    }
}

