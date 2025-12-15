package com.github.dimka9910.sheets.ai.dto.actions;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Action to add alias to a linked user.
 * Example: "remember that I call KIKI as ЗАЯ"
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class AddLinkedUserAliasAction extends InstructionAction {
    
    public static final String TYPE = "ADD_LINKED_USER_ALIAS";
    
    private String userName;  // e.g., "KIKI"
    private String alias;     // e.g., "ЗАЯ"
    
    @Override
    public String getActionType() {
        return TYPE;
    }
}

