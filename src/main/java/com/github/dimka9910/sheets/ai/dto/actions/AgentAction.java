package com.github.dimka9910.sheets.ai.dto.actions;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Base class for all agent actions.
 * 
 * Uses Jackson polymorphic deserialization based on "type" field:
 * - FINANCIAL → FinancialAction
 * - SETTINGS → SettingsAction  
 * - PENDING_CLARIFICATION → PendingClarificationAction
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes({
    @JsonSubTypes.Type(value = FinancialAction.class, name = "FINANCIAL"),
    @JsonSubTypes.Type(value = SettingsAction.class, name = "SETTINGS"),
    @JsonSubTypes.Type(value = PendingClarificationAction.class, name = "PENDING_CLARIFICATION")
})
public abstract class AgentAction {
    
    /**
     * Returns action type for serialization.
     */
    public abstract String getType();
}

