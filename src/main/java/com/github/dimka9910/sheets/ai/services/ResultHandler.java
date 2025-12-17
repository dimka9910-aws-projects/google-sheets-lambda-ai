package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.dto.*;
import com.github.dimka9910.sheets.ai.dto.actions.*;
import com.github.dimka9910.sheets.ai.dto.user.AccountEntry;
import com.github.dimka9910.sheets.ai.dto.user.ConversationMessage;
import com.github.dimka9910.sheets.ai.dto.user.FundEntry;
import com.github.dimka9910.sheets.ai.dto.user.LinkedUserEntry;
import com.github.dimka9910.sheets.ai.dto.user.UserEntity;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles MainAgentResponse results.
 * 
 * Responsibilities:
 * - Execute settings actions (add account, undo, etc.)
 * - Send financial actions to Google Sheets
 * - Manage pending clarifications in UserEntity
 * - Build ChatResponse with model's response message
 */
@Slf4j
public class ResultHandler {

    private final SQSPublisher sqsPublisher;
    private final UserEntityService userContextService;
    private final CustomInstructionHandler customInstructionHandler;

    public ResultHandler(SQSPublisher sqsPublisher, UserEntityService userContextService) {
        this.sqsPublisher = sqsPublisher;
        this.userContextService = userContextService;
        this.customInstructionHandler = new CustomInstructionHandler(userContextService, sqsPublisher);
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
                userContext.setPendingCommands(new ArrayList<>()); // Clear legacy too
            }
            
            case HELP -> {
                // Response is already generated by model
            }
            
            default -> log.warn("Unknown settings command: {}", command);
        }
    }

    private void handleUndo(UserEntity userContext) {
        if (!userContext.hasOperationsToUndo()) {
            log.warn("No operations to undo");
            return;
        }
        
        ParsedCommand lastOp = userContext.popLastOperation();
        log.info("Undoing: {}", lastOp);
        
        SheetsRecordDTO undoRecord = SheetsRecordDTO.fromParsedCommand(lastOp, userContext.getUserName());
        undoRecord.setUndo(true);
        sqsPublisher.sendToSheetsLambda(undoRecord);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FINANCIAL ACTIONS
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean handleFinancialAction(FinancialAction action, UserEntity userContext) {
        // Validate required fields
        if (action.getAmount() == null || action.getAmount() <= 0) {
            log.warn("Financial action missing amount: {}", action);
            return false;
        }
        
        if (action.getOperationType() == null) {
            log.warn("Financial action missing operationType: {}", action);
            return false;
        }
        
        // MODIFY and DELETE are not yet implemented in Google Sheets Lambda
        if (action.getOperationType() == FinancialAction.OperationType.MODIFY ||
            action.getOperationType() == FinancialAction.OperationType.DELETE) {
            log.info("[NOT IMPLEMENTED] {} action received: {} {} {} - skipping Google Sheets", 
                    action.getOperationType(), action.getAmount(), action.getCurrency(), action.getComment());
            // Return true so it shows in debug, but don't send to Sheets
            return true;
        }
        
        // Handle correction
        if (action.isCorrection()) {
            ParsedCommand lastOp = userContext.popLastOperation();
            if (lastOp != null) {
                log.info("Correction: canceling old operation");
                SheetsRecordDTO cancelRecord = SheetsRecordDTO.fromParsedCommand(lastOp, userContext.getUserName());
                cancelRecord.setUndo(true);
                sqsPublisher.sendToSheetsLambda(cancelRecord);
            }
        }
        
        // Convert to ParsedCommand for SheetsRecordDTO (backward compatibility)
        ParsedCommand cmd = convertToLegacyCommand(action);
        
        // Send to Google Sheets
        SheetsRecordDTO record = SheetsRecordDTO.fromParsedCommand(cmd, userContext.getUserName());
        sqsPublisher.sendToSheetsLambda(record);
        
        // Save for undo
        userContext.addOperation(cmd);
        
        log.info("Sent financial action: {} {} {}", 
                action.getOperationType(), action.getAmount(), action.getCurrency());
        
        return true;
    }

    /**
     * Convert FinancialAction to legacy ParsedCommand for SheetsRecordDTO compatibility.
     */
    private ParsedCommand convertToLegacyCommand(FinancialAction action) {
        OperationTypeEnum opType = switch (action.getOperationType()) {
            case EXPENSE -> OperationTypeEnum.EXPENSES;
            case INCOME -> OperationTypeEnum.INCOME;
            case TRANSFER -> OperationTypeEnum.TRANSFER;
            case MODIFY, DELETE -> OperationTypeEnum.UNKNOWN; // Not yet implemented
        };
        
        return ParsedCommand.builder()
                .operationType(opType)
                .amount(action.getAmount())
                .currency(action.getCurrency())
                .accountName(action.getAccount())
                .fundName(action.getFund())
                .comment(action.getComment())
                .secondAccount(action.getTargetAccount())
                .secondPerson(action.getTargetPerson())
                .secondCurrency(action.getTargetCurrency())
                .userName(action.getUserName())
                .understood(true)
                .build();
    }
}
