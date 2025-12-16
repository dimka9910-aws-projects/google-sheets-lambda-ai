package com.github.dimka9910.sheets.ai.dto.actions;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Utilities action - settings, help, meta commands.
 * 
 * Example JSON:
 * {
 *   "type": "UTILS",
 *   "command": "ADD_ACCOUNT",
 *   "value": "CARD_MONO"
 * }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class UtilsAction extends AgentAction {
    
    public static final String TYPE = "UTILS";
    
    /**
     * Command type:
     * - ADD_ACCOUNT: add new account
     * - ADD_FUND: add new fund/category
     * - CUSTOM_INSTRUCTION: manage custom instructions (add/update/remove)
     * - SET_DEFAULT_CURRENCY: set default currency
     * - SET_DEFAULT_ACCOUNT: set default account
     * - SET_DEFAULT_FUND: set default fund
     * - UNDO: undo last operation
     * - HELP: show help/examples
     * - CANCEL_PENDING: cancel pending clarifications
     */
    private Command command;
    
    /**
     * Value for the command (account name, currency, instruction text, etc.)
     */
    private String value;
    
    @Override
    public String getType() {
        return TYPE;
    }
    
    /**
     * Available utility commands
     */
    public enum Command {
        ADD_ACCOUNT,
        ADD_FUND,
        CUSTOM_INSTRUCTION,
        SET_DEFAULT_CURRENCY,
        SET_DEFAULT_ACCOUNT,
        SET_DEFAULT_FUND,
        UNDO,
        HELP,
        CANCEL_PENDING
    }
}

