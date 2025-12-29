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
    
    private String message;
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

