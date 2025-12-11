package com.github.dimka9910.sheets.ai.dto.actions;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Settings/meta command action.
 * 
 * Example JSON:
 * {
 *   "type": "SETTINGS",
 *   "command": "ADD_ACCOUNT",
 *   "value": "CARD_MONO"
 * }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class SettingsAction extends AgentAction {
    
    public static final String TYPE = "SETTINGS";
    
    /**
     * Command type:
     * - SHOW_SETTINGS: display current settings
     * - ADD_ACCOUNT: add new account
     * - ADD_FUND: add new fund/category
     * - ADD_INSTRUCTION: save custom instruction
     * - SET_DEFAULT_CURRENCY: set default currency
     * - SET_DEFAULT_ACCOUNT: set default account
     * - SET_DEFAULT_FUND: set default fund
     * - CLEAR_INSTRUCTIONS: clear all custom instructions
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
     * Available settings commands
     */
    public enum Command {
        SHOW_SETTINGS,
        ADD_ACCOUNT,
        ADD_FUND,
        ADD_INSTRUCTION,
        SET_DEFAULT_CURRENCY,
        SET_DEFAULT_ACCOUNT,
        SET_DEFAULT_FUND,
        CLEAR_INSTRUCTIONS,
        UNDO,
        HELP,
        CANCEL_PENDING
    }
}

