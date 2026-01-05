package com.github.dimka9910.sheets.ai.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Composite response used internally when MainAgent returns multiple redirects
 * that produce both settings and financial actions in the same user turn.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class CombinedAgentResponse extends BaseAgentResponse {

    @JsonPropertyDescription("Merged financial actions from all executed redirects (if any).")
    private List<FinancialAction> financialActions;

    @JsonPropertyDescription("Merged custom-instruction actions from all executed redirects (if any).")
    private List<CustomInstructionAction> customInstructionActions;

    public CombinedAgentResponse(String message,
                                 List<PendingClarificationAction> pendingClarifications,
                                 List<FinancialAction> financialActions,
                                 List<CustomInstructionAction> customInstructionActions) {
        super(message, pendingClarifications);
        this.financialActions = financialActions != null ? financialActions : new ArrayList<>();
        this.customInstructionActions = customInstructionActions != null ? customInstructionActions : new ArrayList<>();
    }
}


