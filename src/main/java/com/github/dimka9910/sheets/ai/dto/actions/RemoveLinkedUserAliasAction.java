package com.github.dimka9910.sheets.ai.dto.actions;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Action to remove alias from a linked user.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class RemoveLinkedUserAliasAction extends InstructionAction {
    
    public static final String TYPE = "REMOVE_LINKED_USER_ALIAS";
    
    private String userName;  // e.g., "KIKI"
    private String alias;     // e.g., "ЗАЯ" - alias to remove
    
    @Override
    public String getActionType() {
        return TYPE;
    }
}

