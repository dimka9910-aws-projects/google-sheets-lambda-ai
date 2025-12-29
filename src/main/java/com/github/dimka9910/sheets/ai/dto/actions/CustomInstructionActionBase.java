package com.github.dimka9910.sheets.ai.dto.actions;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Base interface for CustomInstructionAgent actions.
 * Allows polymorphic deserialization without @Builder conflicts.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "actionType"
)
@JsonSubTypes({
    @JsonSubTypes.Type(value = InstructionAction.class, name = "ADD_LINKED_USER_ALIAS"),
    @JsonSubTypes.Type(value = InstructionAction.class, name = "REMOVE_LINKED_USER_ALIAS"),
    @JsonSubTypes.Type(value = InstructionAction.class, name = "ADD_ACCOUNT_ALIAS"),
    @JsonSubTypes.Type(value = InstructionAction.class, name = "REMOVE_ACCOUNT_ALIAS"),
    @JsonSubTypes.Type(value = InstructionAction.class, name = "ADD_FUND_ALIAS"),
    @JsonSubTypes.Type(value = InstructionAction.class, name = "REMOVE_FUND_ALIAS"),
    @JsonSubTypes.Type(value = InstructionAction.class, name = "ADD_CUSTOM_INSTRUCTION"),
    @JsonSubTypes.Type(value = InstructionAction.class, name = "REMOVE_CUSTOM_INSTRUCTION"),
    @JsonSubTypes.Type(value = InstructionAction.class, name = "UPDATE_DEFAULT")
})
public interface CustomInstructionActionBase {
    String getActionType();
}

