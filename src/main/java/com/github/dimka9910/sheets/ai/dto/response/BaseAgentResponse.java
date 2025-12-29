package com.github.dimka9910.sheets.ai.dto.response;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Base response for all agents.
 * Contains common fields: message and pending clarifications.
 */
@Data
@SuperBuilder
public abstract class BaseAgentResponse {
    
    @JsonPropertyDescription("Response message to user in the SAME language as user's input. Conversational, friendly tone.")
    private String message;
    
    @JsonPropertyDescription("List of unresolved questions. If you need clarification, add items here. Empty array if everything is clear.")
    private List<PendingClarificationAction> pendingClarifications;
    
    /**
     * Default constructor for Jackson
     */
    protected BaseAgentResponse() {
        this.pendingClarifications = new ArrayList<>();
    }
    
    /**
     * Constructor with all fields
     */
    protected BaseAgentResponse(String message, List<PendingClarificationAction> pendingClarifications) {
        this.message = message;
        this.pendingClarifications = pendingClarifications != null ? pendingClarifications : new ArrayList<>();
    }
}

