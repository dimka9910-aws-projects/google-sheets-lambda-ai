package com.github.dimka9910.sheets.ai.dto.response;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Financial operation action: EXPENSE, INCOME, TRANSFER, MODIFY, DELETE.
 * 
 * Each action has a unique ID for tracking and editing.
 * 
 * Example JSON:
 * {
 *   "type": "FINANCIAL",
 *   "id": "550e8400-e29b-41d4-a716-446655440000",
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
public class FinancialAction {
    
    /**
     * Unique identifier for this financial action.
     * Used for tracking and editing operations.
     * 
     * IMPORTANT:
     * - For NEW operations (EXPENSE/INCOME/TRANSFER): Backend generates this field. LLM should NOT set it.
     * - For MODIFY/DELETE: LLM MUST include this field (extracted from conversation history/enriched context).
     */
    @JsonPropertyDescription("Operation ID. REQUIRED for MODIFY/DELETE (extract from conversation history). MUST BE NULL for new EXPENSE/INCOME/TRANSFER.")
    private UUID id;
    
    /**
     * Operation type: EXPENSE, INCOME, TRANSFER, MODIFY, DELETE
     */
    @JsonPropertyDescription("Operation type: EXPENSE (spending), INCOME (receiving), TRANSFER (internal/to linked user), MODIFY (edit existing), DELETE (remove existing).")
    private OperationType operationType;
    
    /**
     * Amount (null if needs clarification)
     */
    @JsonPropertyDescription("Amount of money. REQUIRED for all operations. Positive number.")
    private Double amount;
    
    /**
     * Currency code: RSD, EUR, USD, etc.
     */
    @JsonPropertyDescription("Currency code: RSD, EUR, USD, etc. REQUIRED. Infer from context or use user's default.")
    private String currency;
    
    /**
     * Source account name
     */
    @JsonPropertyDescription("Source account external ID (e.g. CARD_DIMA_VISA_RAIF, CASH_DIMA). REQUIRED for EXPENSE/INCOME/TRANSFER.")
    @JsonAlias({"accountName"})
    private String account;
    
    /**
     * Fund/category for expenses (Food, Transport, etc.)
     */
    @JsonPropertyDescription("Fund/category external ID (e.g. FOOD, TRANSPORT). REQUIRED for EXPENSE. Null for INCOME/TRANSFER.")
    @JsonAlias({"fundName"})
    private String fund;
    
    /**
     * Optional comment
     */
    @JsonPropertyDescription("Optional user comment/description. Can be user's original message or extracted context.")
    private String comment;
    
    /**
     * Target account for TRANSFER operations
     */
    @JsonPropertyDescription("Target account external ID. REQUIRED for INTERNAL_TRANSFER (between own accounts). Null otherwise.")
    @JsonAlias({"to", "toAccountName", "secondAccount"})
    private String targetAccount;
    
    /**
     * Target person for TRANSFER to/from linked user (person RECEIVING money).
     * For transfers between linked users: MANDATORY, must be exact userName.
     */
    @JsonPropertyDescription("Target person userName (e.g. KIKI). REQUIRED for TRANSFER_TO_USER/RECEIVE_FROM_USER. Must match exact userName from linked users list.")
    @JsonAlias({"secondPerson"})
    private String targetPerson;
    
    /**
     * User name for TRANSFER to/from linked user (person SENDING money).
     * For transfers between linked users: MANDATORY, must be exact userName.
     */
    @JsonPropertyDescription("Source person userName (e.g. DIMA). REQUIRED for TRANSFER_TO_USER/RECEIVE_FROM_USER. Use current user's userName.")
    private String userName;
    
    /**
     * Target currency for TRANSFER (if converting)
     */
    @JsonPropertyDescription("Target currency for TRANSFER if converting (e.g. EUR -> RSD). Optional, use if user explicitly mentions currency conversion.")
    @JsonAlias({"secondCurrency"})
    private String targetCurrency;
    
    /**
     * Date of operation (ISO format or natural language parsed)
     */
    @JsonPropertyDescription("Operation date. Optional. Use ISO format (YYYY-MM-DD) or natural language (today, yesterday). Default: today.")
    private String date;
    
    /**
     * True if this is a correction of previous operation
     */
    @JsonPropertyDescription("True if correcting/modifying previous operation (e.g. 'not 200 but 300'). Default: false.")
    @Builder.Default
    private boolean correction = false;
    
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

