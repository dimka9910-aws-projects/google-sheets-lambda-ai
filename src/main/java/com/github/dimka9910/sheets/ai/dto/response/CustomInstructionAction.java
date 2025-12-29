package com.github.dimka9910.sheets.ai.dto.response;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Universal action for CustomInstructionAgent.
 * Replaces 8 separate action classes with one flexible structure.
 * 
 * Examples:
 * - ADD_LINKED_USER_ALIAS: entityType="linkedUser", entityId="KIKI", value="Ksyusha"
 * - ADD_ACCOUNT_ALIAS: entityType="account", entityId="CARD_DIMA_RAIF", value="raif"
 * - ADD_FUND_ALIAS: entityType="fund", entityId="DIMA_MONTHLY_BUDGET", value="monthly"
 * - ADD_CUSTOM_INSTRUCTION: entityType="customInstruction", value="rubles = BYN"
 * - REMOVE_ACCOUNT_ALIAS: entityType="account", entityId="CARD_DIMA_RAIF", value="raif"
 * - REMOVE_CUSTOM_INSTRUCTION: entityType="customInstruction", index=2
 * - UPDATE_DEFAULT: entityType="default", entityId="currency", value="EUR"
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomInstructionAction {
    
    public enum ActionType {
        ADD_LINKED_USER_ALIAS,
        REMOVE_LINKED_USER_ALIAS,
        ADD_ACCOUNT_ALIAS,
        REMOVE_ACCOUNT_ALIAS,
        ADD_FUND_ALIAS,
        REMOVE_FUND_ALIAS,
        ADD_CUSTOM_INSTRUCTION,
        REMOVE_CUSTOM_INSTRUCTION,
        UPDATE_DEFAULT
    }
    
    /**
     * Action type identifier.
     */
    @JsonPropertyDescription("Action type: ADD_LINKED_USER_ALIAS, REMOVE_LINKED_USER_ALIAS, ADD_ACCOUNT_ALIAS, REMOVE_ACCOUNT_ALIAS, ADD_FUND_ALIAS, REMOVE_FUND_ALIAS, ADD_CUSTOM_INSTRUCTION, REMOVE_CUSTOM_INSTRUCTION, UPDATE_DEFAULT")
    private ActionType actionType;
    
    /**
     * Entity type being modified.
     * Values: "linkedUser", "account", "fund", "customInstruction", "default"
     */
    @JsonPropertyDescription("Entity type: linkedUser, account, fund, customInstruction, or default. Must match actionType.")
    private String entityType;
    
    /**
     * Entity identifier (for linkedUser/account/fund/default operations).
     * Examples: userName="KIKI", accountId="CARD_DIMA_RAIF", defaultKey="currency"
     */
    @JsonPropertyDescription("Entity external ID. Examples: KIKI (linkedUser), CARD_DIMA_VISA_RAIF (account), FOOD (fund), currency/account/fund (default key). REQUIRED except for CUSTOM_INSTRUCTION actions.")
    private String entityId;
    
    /**
     * Value to add/set.
     * Examples: alias="Ksyusha", instruction="rubles = BYN", defaultValue="EUR"
     */
    @JsonPropertyDescription("Value to add/set. Examples: alias='kiki', instruction='rubles = BYN', default value='EUR'. REQUIRED for ADD/UPDATE actions.")
    private String value;
    
    /**
     * Index for REMOVE operations on list items (e.g., custom instructions).
     * Optional, only used for REMOVE_CUSTOM_INSTRUCTION.
     */
    @JsonPropertyDescription("Index for REMOVE_CUSTOM_INSTRUCTION (0-based). Optional, can remove by value instead.")
    private Integer index;
}
