package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.db.service.FinancialOperationService;
import com.github.dimka9910.sheets.ai.dto.*;
import com.github.dimka9910.sheets.ai.dto.actions.*;
import com.github.dimka9910.sheets.ai.dto.user.ConversationMessage;
import com.github.dimka9910.sheets.ai.dto.user.UserEntity;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Spring Service for handling MainAgentResponse results.
 * 
 * Responsibilities:
 * - Execute settings actions (add account, undo, etc.)
 * - Save financial actions to PostgreSQL database
 * - Manage pending clarifications in UserEntity
 * - Build ChatResponse with model's response message
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResultHandler {

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
     * Process MainAgentResponse and return ChatResponse.
     * 
     * NOTE: CUSTOM_INSTRUCTION actions are processed asynchronously.
     * If CustomInstructionAgent needs clarification, it will send a SECOND message to user.
     */
    public ChatResponse handle(ChatRequest request, MainAgentResponse agentResponse, UserEntity userContext) {
        String chatId = request.getResponseChatId();
        String message = request.getMessage();
        
        // Add user message to history
        userContext.addToHistory(ConversationMessage.userMessage(message));
        
        // Process all actions
        List<FinancialAction> financialActions = agentResponse.getFinancialActions();
        List<UtilsAction> utilsActions = agentResponse.getUtilsActions();
        List<PendingClarificationAction> pendingActions = agentResponse.getPendingClarifications();
        
        log.info("Processing: {} financial, {} utils, {} pending", 
                financialActions.size(), utilsActions.size(), pendingActions.size());
        
        // Separate CUSTOM_INSTRUCTION actions for async processing
        List<UtilsAction> customInstructionActions = new ArrayList<>();
        List<UtilsAction> otherUtilsActions = new ArrayList<>();
        
        for (UtilsAction action : utilsActions) {
            if (action.getCommand() == UtilsAction.Command.CUSTOM_INSTRUCTION) {
                customInstructionActions.add(action);
            } else {
                otherUtilsActions.add(action);
            }
        }
        
        // Handle non-CUSTOM_INSTRUCTION utils actions synchronously
        for (UtilsAction action : otherUtilsActions) {
            handleUtilsAction(action, userContext, request);
        }
        
        // Handle pending clarifications
        if (!pendingActions.isEmpty()) {
            userContext.setPendingActions(pendingActions);
            log.info("Saved {} pending clarifications", pendingActions.size());
        } else {
            userContext.clearPendingActions();
        }
        
        // Handle financial actions
        boolean allSuccess = true;
        int operationsCount = 0;
        
        for (FinancialAction action : financialActions) {
            boolean success = handleFinancialAction(action, userContext);
            if (success) {
                operationsCount++;
            } else {
                allSuccess = false;
            }
        }
        
        // Determine success
        boolean hasFinancialWork = !financialActions.isEmpty();
        boolean isSuccess = hasFinancialWork ? allSuccess && operationsCount > 0 : true;

        
        // Add assistant response to history
        boolean wasClarification = !pendingActions.isEmpty();
        userContext.addToHistory(ConversationMessage.builder()
                .role("assistant")
                .content(agentResponse.getResponse())
                .wasClarification(wasClarification)
                .build());
        
        // Save context before processing custom instructions
        userContextService.saveContext(userContext);
        
        // Process CUSTOM_INSTRUCTION actions AFTER returning main response
        // If CustomInstructionAgent needs clarification, it will send a SECOND message
        if (!customInstructionActions.isEmpty()) {
            // Collect all instruction values into a list
            List<String> instructions = customInstructionActions.stream()
                    .map(UtilsAction::getValue)
                    .filter(v -> v != null && !v.isBlank())
                    .toList();
            
            if (!instructions.isEmpty()) {
                customInstructionHandler.processAsync(instructions, userContext, request);
            }
        }
        
        return ChatResponse.builder()
                .chatId(chatId)
                .success(isSuccess)
                .message(agentResponse.getResponse())
                .operationsCount(operationsCount)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SETTINGS ACTIONS
    // ═══════════════════════════════════════════════════════════════════════════

    private void handleUtilsAction(UtilsAction action, UserEntity userContext, ChatRequest request) {
        UtilsAction.Command command = action.getCommand();
        String value = action.getValue();
        
        log.info("Settings action: {} = {}", command, value);
        
        switch (command) {
            
            case ADD_ACCOUNT -> {
                if (value != null && !value.isBlank()) {
                    userContext.addAccount(value.toUpperCase().replaceAll("\\s+", "_"));
                }
            }
            
            case ADD_FUND -> {
                if (value != null && !value.isBlank()) {
                    userContext.addFund(value.toUpperCase().replaceAll("\\s+", "_"));
                }
            }
            
            case CUSTOM_INSTRUCTION -> {
                // Handled separately in handle() method asynchronously
                log.debug("CUSTOM_INSTRUCTION action - will be processed asynchronously");
            }
            
            case SET_DEFAULT_CURRENCY -> {
                if (value != null && !value.isBlank()) {
                    userContext.setDefaultCurrency(value.toUpperCase());
                }
            }
            
            case SET_DEFAULT_ACCOUNT -> {
                if (value != null && !value.isBlank()) {
                    userContext.setDefaultAccount(value.toUpperCase());
                }
            }
            
            case SET_DEFAULT_FUND -> {
                if (value != null && !value.isBlank()) {
                    userContext.setDefaultFund(value.toUpperCase());
                }
            }
            
            case UNDO -> handleUndo(userContext);
            
            case CANCEL_PENDING -> {
                userContext.clearPendingActions();
            }
            
            case HELP -> {
                // Response is already generated by model
            }
            
            default -> log.warn("Unknown settings command: {}", command);
        }
    }

    private void handleUndo(UserEntity userContext) {
        // UNDO никогда не работал в legacy коде, теперь это заглушка
        log.warn("⚠️ UNDO not implemented - feature disabled");
        // TODO: Implement undo for database operations when needed
        // Need to soft delete the operation by setting deleted_at timestamp
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
        
        String userId = userContext.getUserName();
        
        try {
            // Save to database based on operation type
            switch (action.getOperationType()) {
                case EXPENSE -> {
                    financialOperationService.get().saveExpense(action, userId);
                    log.info("✅ EXPENSE saved to database: {} {} {}", 
                        action.getAmount(), action.getCurrency(), action.getAccount());
                }
                case INCOME -> {
                    financialOperationService.get().saveIncome(action, userId);
                    log.info("✅ INCOME saved to database: {} {} {}", 
                        action.getAmount(), action.getCurrency(), action.getAccount());
                }
                case TRANSFER -> {
                    financialOperationService.get().saveTransfer(action, userId);
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
