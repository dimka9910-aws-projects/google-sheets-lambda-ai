package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.db.service.FinancialOperationService;
import com.github.dimka9910.sheets.ai.dto.actions.*;
import com.github.dimka9910.sheets.ai.dto.response.BaseAgentResponse;
import com.github.dimka9910.sheets.ai.dto.response.FinancialAgentResponse;
import com.github.dimka9910.sheets.ai.dto.response.CustomInstructionAgentResponse;
import com.github.dimka9910.sheets.ai.dto.actions.InstructionAction;
import com.github.dimka9910.sheets.ai.dto.telegram.TelegramChatRequest;
import com.github.dimka9910.sheets.ai.dto.telegram.TelegramChatResponse;
import com.github.dimka9910.sheets.ai.dto.user.ConversationMessage;
import com.github.dimka9910.sheets.ai.dto.user.UserEntity;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Service for handling MainAgentResponse results.
 * 
 * Responsibilities:
 * - Execute settings actions (add account, undo, etc.)
 * - Save financial actions to PostgreSQL database
 * - Manage pending clarifications in UserEntity
 * - Build TelegramChatResponse with model's response message
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MainAgentResultHandler {

    private final SQSPublisher sqsPublisher;
    private final UserEntityService userContextService;
    private final CustomInstructionHandler customInstructionHandler;
    private final Optional<FinancialOperationService> financialOperationService;
    
    @PostConstruct
    public void init() {
        if (financialOperationService.isPresent()) {
            log.info("✅ ResultHandler initialized with DATABASE support");
        } else {
            log.warn("⚠️ ResultHandler initialized WITHOUT database support (DATABASE_URL not set)");
        }
    }

    /**
     * Process agent response and return TelegramChatResponse.
     * 
     * Handles different response types:
     * - FinancialAgentResponse: save financial operations
     * - CustomInstructionAgentResponse: update user settings
     * - MainAgentResponse: pure conversational (no actions)
     * 
     * NOTE: CUSTOM_INSTRUCTION actions are processed asynchronously.
     * If CustomInstructionAgent needs clarification, it will send a SECOND message to user.
     */
    public TelegramChatResponse handle(TelegramChatRequest request, BaseAgentResponse agentResponse, UserEntity userContext) {
        String chatId = request.getResponseChatId();
        String message = request.getMessage();
        
        // Add user message to history
        userContext.addToHistory(ConversationMessage.userMessage(message));
        
        // Extract actions based on response type
        List<FinancialAction> financialActions = new ArrayList<>();
        List<InstructionAction> instructionActions = new ArrayList<>();
        List<PendingClarificationAction> pendingActions = agentResponse.getPendingClarifications() != null 
                ? agentResponse.getPendingClarifications() 
                : new ArrayList<>();
        
        if (agentResponse instanceof FinancialAgentResponse financialResponse) {
            financialActions = financialResponse.getFinancialActions() != null 
                    ? financialResponse.getFinancialActions() 
                    : new ArrayList<>();
            log.info("Processing FinancialAgentResponse: {} financial actions, {} pending", 
                    financialActions.size(), pendingActions.size());
        } else if (agentResponse instanceof CustomInstructionAgentResponse instructionResponse) {
            instructionActions = instructionResponse.getInstructionActions() != null 
                    ? instructionResponse.getInstructionActions() 
                    : new ArrayList<>();
            log.info("Processing CustomInstructionAgentResponse: {} instruction actions, {} pending", 
                    instructionActions.size(), pendingActions.size());
        } else {
            // MainAgentResponse - pure conversational, no actions
            log.info("Processing MainAgentResponse: conversational only, {} pending", pendingActions.size());
        }
        
        // Handle pending clarifications
        if (!pendingActions.isEmpty()) {
            userContext.setPendingActions(pendingActions);
            log.info("Saved {} pending clarifications", pendingActions.size());
        } else {
            userContext.clearPendingActions();
        }
        
        // Handle instruction actions (settings updates)
        if (!instructionActions.isEmpty()) {
            handleInstructionActions(instructionActions, userContext);
        }
        
        // Handle financial actions
        boolean allSuccess = true;
        int operationsCount = 0;
        List<FinancialAction> successfulActions = new ArrayList<>();
        
        for (FinancialAction action : financialActions) {
            // Note: UUID will be generated by database (@GeneratedValue) and set after save
            
            boolean success = handleFinancialAction(action, userContext);
            if (success) {
                operationsCount++;
                successfulActions.add(action);
            } else {
                allSuccess = false;
            }
        }
        
        // Determine success
        boolean hasFinancialWork = !financialActions.isEmpty();
        boolean isSuccess = hasFinancialWork ? allSuccess && operationsCount > 0 : true;

        
        // Add assistant response to history with related financial actions
        userContext.addToHistory(ConversationMessage.builder()
                .role("assistant")
                .content(agentResponse.getMessage())
                .relatedFinancialActions(successfulActions)
                .build());
        
        // Save ONLY conversation history + AI context (NOT accounts/funds to avoid constraint violations)
        userContextService.saveConversationAndAiContext(userContext);
        
        return TelegramChatResponse.builder()
                .chatId(chatId)
                .success(isSuccess)
                .message(agentResponse.getMessage())
                .operationsCount(operationsCount)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INSTRUCTION ACTIONS (Settings/Aliases/Defaults)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Handle instruction actions from CustomInstructionAgent.
     * These actions modify user settings: aliases, defaults, custom instructions.
     */
    private void handleInstructionActions(List<InstructionAction> actions, UserEntity userContext) {
        log.info("Processing {} instruction actions", actions.size());
        
        for (InstructionAction action : actions) {
            handleInstructionAction(action, userContext);
        }
    }
    
    /**
     * Handle single InstructionAction.
     */
    private void handleInstructionAction(InstructionAction action, UserEntity userContext) {
        log.info("Instruction action: {} (entity={}, entityId={})", 
                action.getActionType(), action.getEntityType(), action.getEntityId());
        
        // TODO: Implement instruction action handling
        // This will be done when we update CustomInstructionAgent to use CustomInstructionAgentResponse
        log.warn("⚠️ Instruction action handling not yet implemented: {}", action.getActionType());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FINANCIAL ACTIONS
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean handleFinancialAction(FinancialAction action, UserEntity userContext) {
        // Validate required fields
        if (action.getAmount() == null || action.getAmount() <= 0) {
            log.warn("❌ Financial action missing amount: {}", action);
            return false;
        }
        
        if (action.getOperationType() == null) {
            log.warn("❌ Financial action missing operationType: {}", action);
            return false;
        }
        
        // Check if database is available
        if (financialOperationService.isEmpty()) {
            log.error("❌ DATABASE_URL not configured, cannot save financial operation");
            return false;
        }
        
        // MODIFY and DELETE are not yet implemented
        if (action.getOperationType() == FinancialAction.OperationType.MODIFY ||
            action.getOperationType() == FinancialAction.OperationType.DELETE) {
            log.info("⏸️ [NOT IMPLEMENTED] {} action received: {} {} {} - skipping for now", 
                    action.getOperationType(), action.getAmount(), action.getCurrency(), action.getComment());
            // Return true so it shows in debug, but don't save
            return true;
        }
        
        // TODO: Handle correction (soft delete previous operation)
        if (action.isCorrection()) {
            log.warn("⏸️ Correction not yet implemented for database - skipping old operation cancellation");
            // ParsedCommand lastOp = userContext.popLastOperation();
            // if (lastOp != null) {
            //     log.info("Correction: soft deleting old operation");
            //     financialOperationService.get().softDelete(lastOpId);
            // }
        }
        
        try {
            // Save to database based on operation type
            // Database will generate UUID via @GeneratedValue
            switch (action.getOperationType()) {
                case EXPENSE -> {
                    var saved = financialOperationService.get().saveExpense(action, userContext);
                    action.setId(saved.getId()); // Update action with DB-generated UUID
                    log.info("✅ EXPENSE saved to database: {} {} {}", 
                        action.getAmount(), action.getCurrency(), action.getAccount());
                }
                case INCOME -> {
                    var saved = financialOperationService.get().saveIncome(action, userContext);
                    action.setId(saved.getId()); // Update action with DB-generated UUID
                    log.info("✅ INCOME saved to database: {} {} {}", 
                        action.getAmount(), action.getCurrency(), action.getAccount());
                }
                case TRANSFER -> {
                    var saved = financialOperationService.get().saveTransfer(action, userContext);
                    // For transfers, use the first (debit) record's ID
                    action.setId(saved.get(0).getId());
                    log.info("✅ TRANSFER saved to database: {} {} {} → {}", 
                        action.getAmount(), action.getCurrency(), action.getAccount(), action.getTargetAccount());
                }
                default -> {
                    log.warn("❌ Unknown operation type: {}", action.getOperationType());
                    return false;
                }
            }
            
            return true;
            
        } catch (Exception e) {
            log.error("❌ Failed to save financial operation to database", e);
            return false;
        }
    }

}
