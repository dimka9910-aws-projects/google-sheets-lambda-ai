package com.github.dimka9910.sheets.ai.dto.actions;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Action to ask user for clarification before making changes.
 * Used when agent is uncertain or detects potential conflicts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class AskClarificationAction extends InstructionAction {
    
    public static final String TYPE = "ASK_CLARIFICATION";
    
    private String question;  // Question to ask user
    private String context;   // Internal context for next resolution
    
    @Override
    public String getActionType() {
        return TYPE;
    }
}

