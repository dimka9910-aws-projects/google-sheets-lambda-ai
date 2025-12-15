package com.github.dimka9910.sheets.ai.dto.actions;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Action to remove alias from a fund.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class RemoveFundAliasAction extends InstructionAction {
    
    public static final String TYPE = "REMOVE_FUND_ALIAS";
    
    private String fundId;  // e.g., "FOOD"
    private String alias;   // e.g., "кафе" - alias to remove
    
    @Override
    public String getActionType() {
        return TYPE;
    }
}

