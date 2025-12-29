package com.github.dimka9910.sheets.ai.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.github.dimka9910.sheets.ai.dto.actions.FinancialAction;
import com.github.dimka9910.sheets.ai.dto.actions.PendingClarificationAction;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Response from financial agents:
 * - SimpleExpenseAgent
 * - InternalTransferAgent
 * - ThirdPartyActionAgent
 * - ExpenseEditAndDeletionAgent
 * 
 * Contains list of financial actions (expenses, income, transfers, edits, deletions).
 */
@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class FinancialAgentResponse extends BaseAgentResponse {
    
    /**
     * List of financial actions to perform.
     */
    @JsonPropertyDescription("List of financial operations to perform: EXPENSE, INCOME, INTERNAL_TRANSFER, TRANSFER_TO_USER, RECEIVE_FROM_USER, EDIT_OPERATION, DELETE_OPERATION")
    private List<FinancialAction> financialActions;
    
    /**
     * Constructor for Jackson
     */
    public FinancialAgentResponse(String message, 
                                   List<PendingClarificationAction> pendingClarifications,
                                   List<FinancialAction> financialActions) {
        super(message, pendingClarifications);
        this.financialActions = financialActions != null ? financialActions : new ArrayList<>();
    }
}

