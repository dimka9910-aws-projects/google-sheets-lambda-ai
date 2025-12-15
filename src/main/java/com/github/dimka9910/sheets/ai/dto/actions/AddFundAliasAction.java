package com.github.dimka9910.sheets.ai.dto.actions;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Action to add alias to a fund.
 * Example: "remember that кафе is FOOD category"
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class AddFundAliasAction extends InstructionAction {
    
    public static final String TYPE = "ADD_FUND_ALIAS";
    
    private String fundId;  // e.g., "FOOD"
    private String alias;   // e.g., "кафе"
    
    @Override
    public String getActionType() {
        return TYPE;
    }
}

