package com.github.dimka9910.sheets.ai.dto.actions;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Action to add a custom instruction.
 * Used for complex rules that don't fit into aliases or defaults.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class AddCustomInstructionAction extends InstructionAction {
    
    public static final String TYPE = "ADD_CUSTOM_INSTRUCTION";
    
    private String instruction;  // e.g., "трасса = 100 dinars from cash"
    
    @Override
    public String getActionType() {
        return TYPE;
    }
}

