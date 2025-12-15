package com.github.dimka9910.sheets.ai.dto.actions;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Action to remove a custom instruction by index.
 * Used when instruction is contradictory or no longer needed.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class RemoveCustomInstructionAction extends InstructionAction {
    
    public static final String TYPE = "REMOVE_CUSTOM_INSTRUCTION";
    
    private int index;  // Index in customInstructions list
    
    @Override
    public String getActionType() {
        return TYPE;
    }
}

