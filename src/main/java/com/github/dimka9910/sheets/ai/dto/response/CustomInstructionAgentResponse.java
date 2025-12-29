package com.github.dimka9910.sheets.ai.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.github.dimka9910.sheets.ai.dto.actions.CustomInstructionActionBase;
import com.github.dimka9910.sheets.ai.dto.actions.PendingClarificationAction;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Response from CustomInstructionAgent.
 * 
 * Contains list of instruction actions (add/remove aliases, set defaults, manage custom instructions).
 */
@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CustomInstructionAgentResponse extends BaseAgentResponse {
    
    /**
     * List of custom instruction actions to perform.
     */
    @JsonPropertyDescription("List of instruction actions: ADD_LINKED_USER_ALIAS, REMOVE_LINKED_USER_ALIAS, ADD_ACCOUNT_ALIAS, REMOVE_ACCOUNT_ALIAS, ADD_FUND_ALIAS, REMOVE_FUND_ALIAS, ADD_CUSTOM_INSTRUCTION, REMOVE_CUSTOM_INSTRUCTION, UPDATE_DEFAULT")
    private List<CustomInstructionActionBase> instructionActions;
    
    /**
     * Constructor for Jackson
     */
    public CustomInstructionAgentResponse(String message,
                                          List<PendingClarificationAction> pendingClarifications,
                                          List<CustomInstructionActionBase> instructionActions) {
        super(message, pendingClarifications);
        this.instructionActions = instructionActions != null ? instructionActions : new ArrayList<>();
    }
}

