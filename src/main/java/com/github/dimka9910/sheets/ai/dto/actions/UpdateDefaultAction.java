package com.github.dimka9910.sheets.ai.dto.actions;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Action to update default values (currency, account, or fund).
 * Example: "I always spend in EUR" → defaultCurrency = EUR
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class UpdateDefaultAction extends InstructionAction {
    
    public static final String TYPE = "UPDATE_DEFAULT";
    
    public enum DefaultType {
        CURRENCY,
        ACCOUNT,
        FUND
    }
    
    private DefaultType defaultType;
    private String value;  // e.g., "EUR", "CARD_RAIF", "FOOD"
    
    @Override
    public String getActionType() {
        return TYPE;
    }
}

