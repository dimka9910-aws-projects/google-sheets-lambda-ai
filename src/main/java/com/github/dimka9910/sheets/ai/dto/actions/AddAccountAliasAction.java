package com.github.dimka9910.sheets.ai.dto.actions;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Action to add alias to an account.
 * Example: "remember that райф is my Raiffeisen card"
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class AddAccountAliasAction extends InstructionAction {
    
    public static final String TYPE = "ADD_ACCOUNT_ALIAS";
    
    private String accountId;  // e.g., "CARD_RAIF"
    private String alias;      // e.g., "райф"
    
    @Override
    public String getActionType() {
        return TYPE;
    }
}

