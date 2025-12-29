package com.github.dimka9910.sheets.ai.dto.response;

import com.github.dimka9910.sheets.ai.dto.actions.PendingClarificationAction;
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
    
    /**
     * Human-readable message to show the user.
     * Should be in the same language as user's input.
     */
    private String message;
    
    /**
     * Pending clarifications - questions that need user response.
     */
    private List<PendingClarificationAction> pendingClarifications;
    
    /**
     * Check if response has pending clarifications
     */
    public boolean hasPendingClarifications() {
        return pendingClarifications != null && !pendingClarifications.isEmpty();
    }
    
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

