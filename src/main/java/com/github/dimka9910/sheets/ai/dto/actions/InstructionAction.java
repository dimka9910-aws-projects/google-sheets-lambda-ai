package com.github.dimka9910.sheets.ai.dto.actions;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Data;

/**
 * Base class for all instruction-related actions.
 * Used by CustomInstructionAgent to modify user context.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "actionType"
)
@JsonSubTypes({
    @JsonSubTypes.Type(value = AddLinkedUserAliasAction.class, name = "ADD_LINKED_USER_ALIAS"),
    @JsonSubTypes.Type(value = RemoveLinkedUserAliasAction.class, name = "REMOVE_LINKED_USER_ALIAS"),
    @JsonSubTypes.Type(value = AddAccountAliasAction.class, name = "ADD_ACCOUNT_ALIAS"),
    @JsonSubTypes.Type(value = RemoveAccountAliasAction.class, name = "REMOVE_ACCOUNT_ALIAS"),
    @JsonSubTypes.Type(value = AddFundAliasAction.class, name = "ADD_FUND_ALIAS"),
    @JsonSubTypes.Type(value = RemoveFundAliasAction.class, name = "REMOVE_FUND_ALIAS"),
    @JsonSubTypes.Type(value = AddCustomInstructionAction.class, name = "ADD_CUSTOM_INSTRUCTION"),
    @JsonSubTypes.Type(value = RemoveCustomInstructionAction.class, name = "REMOVE_CUSTOM_INSTRUCTION"),
    @JsonSubTypes.Type(value = UpdateDefaultAction.class, name = "UPDATE_DEFAULT"),
    @JsonSubTypes.Type(value = AskClarificationAction.class, name = "ASK_CLARIFICATION")
})
public abstract class InstructionAction {
    
    public abstract String getActionType();
}

