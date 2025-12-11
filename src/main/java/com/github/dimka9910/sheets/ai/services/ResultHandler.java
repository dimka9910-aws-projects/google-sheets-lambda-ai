package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.dto.*;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles ParsedCommandList results from MainAgent.
 * 
 * Responsibilities:
 * - Execute meta commands (settings, undo, etc.)
 * - Send financial operations to Google Sheets
 * - Manage conversation history and pending commands
 * - Build ChatResponse
 */
@Slf4j
public class ResultHandler {

    private final SQSPublisher sqsPublisher;
    private final UserEntityService userContextService;

    public ResultHandler(SQSPublisher sqsPublisher, UserEntityService userContextService) {
        this.sqsPublisher = sqsPublisher;
        this.userContextService = userContextService;
    }

    /**
     * Process ParsedCommandList and return ChatResponse.
     */
    public ChatResponse handle(ChatRequest request, ParsedCommandList parsedList, UserEntity userContext) {
        String chatId = request.getResponseChatId();
        String message = request.getMessage();
        
        // Add user message to history
        userContext.addToHistory(ConversationMessage.userMessage(message));
        
        // Merge with pending commands if any
        mergePendingCommands(parsedList, userContext);
        
        // Handle meta command if present
        if (parsedList.getMetaCommand() != null && parsedList.getMetaCommand().isPresent()) {
            ChatResponse metaResponse = handleMetaCommand(request, parsedList, userContext);
            if (metaResponse != null) {
                userContextService.saveContext(userContext);
                return metaResponse;
            }
        }

        // Build response
        ChatResponse response = buildResponse(request, parsedList, userContext);
        
        // Track if this was a clarification question
        boolean wasClarification = !parsedList.isUnderstood() && parsedList.getClarification() != null;
        
        // Add assistant response to history
        ParsedCommand firstCmd = parsedList.getFirst();
        userContext.addToHistory(
                ConversationMessage.assistantMessage(response.getMessage(), firstCmd, wasClarification));

        // Manage pending commands
        if (wasClarification && parsedList.size() > 0) {
            userContext.setPendingCommands(new ArrayList<>(parsedList.getCommands()));
            log.info("Saved {} pending commands for clarification", parsedList.size());
        }

        // If successful — send to sheets
        if (response.isSuccess()) {
            userContext.getPendingCommands().clear();
            
            // Correction: cancel old operation first
            if (parsedList.isCorrection()) {
                ParsedCommand lastOp = userContext.popLastOperation();
                if (lastOp != null) {
                    log.info("Correction: canceling old operation: {}", lastOp);
                    sendCancelOperation(userContext, lastOp);
                }
            }
            
            // Send new commands
            for (ParsedCommand cmd : parsedList.getCommands()) {
                sendToSheetsLambda(userContext, cmd);
                userContext.addOperation(cmd);
            }
            
            // Apply setAsDefault if requested
            applySetAsDefault(parsedList, userContext);
            
            // Clear history after successful operation
            userContext.clearHistory();
        }

        userContextService.saveContext(userContext);
        return response;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // META COMMANDS
    // ═══════════════════════════════════════════════════════════════════════════

    private ChatResponse handleMetaCommand(ChatRequest request, ParsedCommandList parsedList, UserEntity userContext) {
        ParsedCommandList.MetaCommand meta = parsedList.getMetaCommand();
        String chatId = request.getResponseChatId();
        String userName = request.getUserName();
        String type = meta.getType();
        String value = meta.getValue();
        String aiMessage = parsedList.getClarification();
        
        log.info("Meta command: type={}, value={}", type, value);
        
        return switch (type.toUpperCase()) {
            case "SHOW_SETTINGS" -> {
                String summary = userContextService.getContextSummary(userName);
                yield ChatResponse.builder()
                        .chatId(chatId)
                        .success(true)
                        .message(aiMessage != null ? aiMessage + "\n\n" + summary : summary)
                        .build();
            }
            
            case "ADD_ACCOUNT" -> {
                if (value != null && !value.isBlank()) {
                    userContext.addAccount(value.toUpperCase().replaceAll("\\s+", "_"));
                }
                yield simpleResponse(chatId, aiMessage);
            }
            
            case "ADD_FUND" -> {
                if (value != null && !value.isBlank()) {
                    userContext.addFund(value.toUpperCase().replaceAll("\\s+", "_"));
                }
                yield simpleResponse(chatId, aiMessage);
            }
            
            case "ADD_INSTRUCTION" -> {
                if (value != null && !value.isBlank()) {
                    List<String> existing = userContext.getCustomInstructions();
                    if (existing == null || !existing.contains(value)) {
                        userContext.addInstruction(value);
                        log.info("Added instruction: {}", value);
                    }
                }
                yield simpleResponse(chatId, aiMessage);
            }
            
            case "REMOVE_INSTRUCTION" -> {
                if (value != null && !value.isBlank()) {
                    try {
                        int index = Integer.parseInt(value.trim());
                        List<String> instructions = userContext.getCustomInstructions();
                        if (instructions != null && index >= 0 && index < instructions.size()) {
                            userContext.removeInstruction(index);
                        }
                    } catch (NumberFormatException ignored) {}
                }
                yield simpleResponse(chatId, aiMessage);
            }
            
            case "SET_DEFAULT_CURRENCY" -> {
                if (value != null && !value.isBlank()) {
                    userContext.setDefaultCurrency(value.toUpperCase());
                }
                yield simpleResponse(chatId, aiMessage);
            }
            
            case "SET_DEFAULT_ACCOUNT" -> {
                if (value != null && !value.isBlank()) {
                    userContext.setDefaultAccount(value.toUpperCase());
                }
                yield simpleResponse(chatId, aiMessage);
            }
            
            case "SET_DEFAULT_FUND" -> {
                if (value != null && !value.isBlank()) {
                    userContext.setDefaultFund(value.toUpperCase());
                }
                yield simpleResponse(chatId, aiMessage);
            }
            
            case "CLEAR_INSTRUCTIONS" -> {
                userContext.clearInstructions();
                yield simpleResponse(chatId, aiMessage);
            }
            
            case "UNDO" -> handleUndo(request, userContext, aiMessage);
            
            case "HELP" -> simpleResponse(chatId, aiMessage);
            
            case "CANCEL_PENDING" -> {
                userContext.setPendingCommands(new ArrayList<>());
                yield simpleResponse(chatId, aiMessage);
            }
            
            default -> {
                log.warn("Unknown meta command: {}", type);
                yield null;
            }
        };
    }

    private ChatResponse handleUndo(ChatRequest request, UserEntity userContext, String aiMessage) {
        String chatId = request.getResponseChatId();
        
        if (!userContext.hasOperationsToUndo()) {
            return ChatResponse.builder()
                    .chatId(chatId)
                    .success(false)
                    .message(aiMessage != null ? aiMessage : "No operations to undo")
                    .build();
        }
        
        ParsedCommand lastOp = userContext.popLastOperation();
        log.info("Undoing: {}", lastOp);
        
        SheetsRecordDTO undoRecord = SheetsRecordDTO.fromParsedCommand(lastOp, userContext.getUserName());
        undoRecord.setUndo(true);
        sqsPublisher.sendToSheetsLambda(undoRecord);
        
        userContextService.saveContext(userContext);
        
        return ChatResponse.builder()
                .chatId(chatId)
                .success(true)
                .message(aiMessage != null ? aiMessage : "Undone")
                .parsedCommand(lastOp)
                .build();
    }

    private ChatResponse simpleResponse(String chatId, String message) {
        return ChatResponse.builder()
                .chatId(chatId)
                .success(true)
                .message(message)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RESPONSE BUILDING
    // ═══════════════════════════════════════════════════════════════════════════

    private ChatResponse buildResponse(ChatRequest request, ParsedCommandList parsedList, UserEntity userContext) {
        List<ParsedCommand> commands = parsedList.getCommands();
        
        boolean allValid = parsedList.isUnderstood() 
                && commands != null 
                && !commands.isEmpty()
                && commands.stream().allMatch(cmd -> 
                        cmd.getOperationType() != null && cmd.getOperationType() != OperationTypeEnum.UNKNOWN);
        
        String message = parsedList.getClarification();
        if (message == null || message.isBlank()) {
            message = parsedList.getErrorMessage();
        }
        if (message == null || message.isBlank()) {
            log.warn("No clarification from AI");
            message = allValid ? "✓" : "?";
        }
        
        return ChatResponse.builder()
                .chatId(request.getResponseChatId())
                .success(allValid)
                .message(message)
                .parsedCommands(commands)
                .parsedCommand(parsedList.getFirst())
                .operationsCount(allValid ? commands.size() : 0)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private void mergePendingCommands(ParsedCommandList parsedList, UserEntity userContext) {
        List<ParsedCommand> pendingCmds = userContext.getPendingCommands();
        if (pendingCmds == null || pendingCmds.isEmpty() || parsedList.size() == 0) {
            return;
        }
        
        List<ParsedCommand> newCmds = parsedList.getCommands();
        for (int i = 0; i < pendingCmds.size(); i++) {
            ParsedCommand pending = pendingCmds.get(i);
            if (i < newCmds.size()) {
                newCmds.set(i, mergePendingWithNew(pending, newCmds.get(i)));
            } else if (!newCmds.isEmpty() && newCmds.get(0).getAmount() != null) {
                newCmds.add(mergePendingWithNew(pending, newCmds.get(0)));
            }
        }
        parsedList.setCommands(newCmds);
    }

    private ParsedCommand mergePendingWithNew(ParsedCommand pending, ParsedCommand newCmd) {
        return ParsedCommand.builder()
                .operationType(newCmd.getOperationType() != null ? newCmd.getOperationType() : pending.getOperationType())
                .amount(newCmd.getAmount() != null && newCmd.getAmount() > 0 ? newCmd.getAmount() : pending.getAmount())
                .currency(newCmd.getCurrency() != null ? newCmd.getCurrency() : pending.getCurrency())
                .accountName(newCmd.getAccountName() != null ? newCmd.getAccountName() : pending.getAccountName())
                .fundName(newCmd.getFundName() != null ? newCmd.getFundName() : pending.getFundName())
                .comment(newCmd.getComment() != null ? newCmd.getComment() : pending.getComment())
                .secondAccount(newCmd.getSecondAccount() != null ? newCmd.getSecondAccount() : pending.getSecondAccount())
                .secondPerson(newCmd.getSecondPerson() != null ? newCmd.getSecondPerson() : pending.getSecondPerson())
                .secondCurrency(newCmd.getSecondCurrency() != null ? newCmd.getSecondCurrency() : pending.getSecondCurrency())
                .understood(newCmd.isUnderstood())
                .clarification(newCmd.getClarification())
                .errorMessage(newCmd.getErrorMessage())
                .build();
    }

    private void applySetAsDefault(ParsedCommandList parsedList, UserEntity userContext) {
        if (parsedList.getSetAsDefault() == null || !parsedList.getSetAsDefault().hasAny()) {
            return;
        }
        ParsedCommandList.SetAsDefault defaults = parsedList.getSetAsDefault();
        if (defaults.getAccount() != null) userContext.setDefaultAccount(defaults.getAccount());
        if (defaults.getCurrency() != null) userContext.setDefaultCurrency(defaults.getCurrency());
        if (defaults.getFund() != null) userContext.setDefaultFund(defaults.getFund());
        log.info("Applied defaults: {}", defaults);
    }

    private void sendToSheetsLambda(UserEntity userContext, ParsedCommand cmd) {
        SheetsRecordDTO record = SheetsRecordDTO.fromParsedCommand(cmd, userContext.getUserName());
        sqsPublisher.sendToSheetsLambda(record);
    }

    private void sendCancelOperation(UserEntity userContext, ParsedCommand originalOp) {
        ParsedCommand cancelOp = ParsedCommand.builder()
                .operationType(originalOp.getOperationType())
                .amount(-originalOp.getAmount())
                .currency(originalOp.getCurrency())
                .accountName(originalOp.getAccountName())
                .fundName(originalOp.getFundName())
                .comment("CANCEL: " + originalOp.getComment())
                .secondPerson(originalOp.getSecondPerson())
                .secondAccount(originalOp.getSecondAccount())
                .secondCurrency(originalOp.getSecondCurrency())
                .understood(true)
                .build();
        
        sendToSheetsLambda(userContext, cancelOp);
    }
}
