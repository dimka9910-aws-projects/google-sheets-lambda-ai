package com.github.dimka9910.sheets.ai.dto.actions;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Financial operation action: EXPENSE, INCOME, TRANSFER.
 * 
 * Example JSON:
 * {
 *   "type": "FINANCIAL",
 *   "operationType": "EXPENSE",
 *   "amount": 500.0,
 *   "currency": "RSD",
 *   "account": "CARD_VISA",
 *   "fund": "Food",
 *   "comment": "кофе"
 * }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class FinancialAction extends AgentAction {
    
    public static final String TYPE = "FINANCIAL";
    
    /**
     * Operation type: EXPENSE, INCOME, TRANSFER
     */
    private OperationType operationType;
    
    /**
     * Amount (null if needs clarification)
     */
    private Double amount;
    
    /**
     * Currency code: RSD, EUR, USD, etc.
     */
    private String currency;
    
    /**
     * Source account name
     */
    @JsonAlias({"accountName"})
    private String account;
    
    /**
     * Fund/category for expenses (Food, Transport, etc.)
     */
    @JsonAlias({"fundName"})
    private String fund;
    
    /**
     * Optional comment
     */
    private String comment;
    
    /**
     * Target account for TRANSFER operations
     */
    @JsonAlias({"to", "toAccountName", "secondAccount"})
    private String targetAccount;
    
    /**
     * Target person for TRANSFER to/from linked user (person RECEIVING money).
     * For transfers between linked users: MANDATORY, must be exact userName.
     */
    @JsonAlias({"secondPerson"})
    private String targetPerson;
    
    /**
     * User name for TRANSFER to/from linked user (person SENDING money).
     * For transfers between linked users: MANDATORY, must be exact userName.
     */
    private String userName;
    
    /**
     * Target currency for TRANSFER (if converting)
     */
    @JsonAlias({"secondCurrency"})
    private String targetCurrency;
    
    /**
     * Date of operation (ISO format or natural language parsed)
     */
    private String date;
    
    /**
     * True if this is a correction of previous operation
     */
    @Builder.Default
    private boolean correction = false;
    
    @Override
    public String getType() {
        return TYPE;
    }
    
    /**
     * Operation types for financial actions
     */
    public enum OperationType {
        EXPENSE,
        INCOME,
        TRANSFER,
        MODIFY,   // Edit existing operation (NOT YET IMPLEMENTED in Google Sheets)
        DELETE    // Delete operation (NOT YET IMPLEMENTED in Google Sheets)
    }
}

