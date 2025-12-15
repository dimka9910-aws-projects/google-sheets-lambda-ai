package com.github.dimka9910.sheets.ai.dto.actions;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Action to remove alias from an account.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class RemoveAccountAliasAction extends InstructionAction {
    
    public static final String TYPE = "REMOVE_ACCOUNT_ALIAS";
    
    private String accountId;  // e.g., "CARD_RAIF"
    private String alias;      // e.g., "райф" - alias to remove
    
    @Override
    public String getActionType() {
        return TYPE;
    }
}

