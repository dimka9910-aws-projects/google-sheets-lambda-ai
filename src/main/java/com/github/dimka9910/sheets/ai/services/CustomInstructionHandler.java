package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.dto.telegram.TelegramChatRequest;
import com.github.dimka9910.sheets.ai.dto.telegram.TelegramChatResponse;
import com.github.dimka9910.sheets.ai.dto.actions.*;
import com.github.dimka9910.sheets.ai.dto.user.AccountEntry;
import com.github.dimka9910.sheets.ai.dto.user.ConversationMessage;
import com.github.dimka9910.sheets.ai.dto.user.FundEntry;
import com.github.dimka9910.sheets.ai.dto.user.LinkedUserEntry;
import com.github.dimka9910.sheets.ai.dto.user.UserEntity;
import com.github.dimka9910.sheets.ai.services.agents.CustomInstructionAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring Service for processing custom instructions.
 * 
 * Responsibilities:
 * - Call CustomInstructionAgent to analyze user instructions
 * - Apply InstructionActions (add/remove aliases, update defaults, custom instructions)
 * - Send SECOND message to user if clarification needed
 * - Manage UserEntity aliases and custom instructions
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomInstructionHandler {

    private final CustomInstructionAgent customInstructionAgent;
    private final UserEntityService userEntityService;
    private final SQSPublisher sqsPublisher;

    /**
     * Process CUSTOM_INSTRUCTION actions asynchronously.
     * 
     * Flow:
     * 1. Call CustomInstructionAgent to analyze instructions (batch)
     * 2. Apply all returned InstructionActions
     * 3. Save updated UserEntity
     * 4. If clarification needed → send SECOND message via SQS
     */
    public void processAsync(List<String> instructions, UserEntity userEntity, TelegramChatRequest request) {
        log.info("Processing {} custom instructions asynchronously: {}", instructions.size(), instructions);
        
        try {
            var agentRequest = new CustomInstructionAgent.Request(instructions, userEntity);
            var agentResponse = customInstructionAgent.process(agentRequest);
            
            if (!agentResponse.isSuccess()) {
                log.error("CustomInstructionAgent failed: {}", agentResponse.errorMessage());
                return;
            }
            
            log.info("CustomInstructionAgent result: {} actions - {}", 
                    agentResponse.actions().size(),
                    agentResponse.explanation());
            
            // Apply all instruction actions
            for (var action : agentResponse.actions()) {
                if (action instanceof InstructionAction instructionAction) {
                    applyInstructionAction(instructionAction, userEntity);
                } else {
                    log.warn("Unknown action type: {}", action.getClass().getName());
                }
            }
            
            // Save updated custom instructions (NOT accounts/funds)
            userEntityService.saveConversationAndAiContext(userEntity);
            
            // TODO: Handle pending clarifications from CustomInstructionAgent
            // CustomInstructionAgent should now return pendingClarifications in response
            log.info("CustomInstructionAgent completed successfully");
        } catch (Exception e) {
            log.error("Fatal error processing custom instructions - changes may be lost", e);
            // Don't rethrow - this is async processing, first message already sent
            // Worst case: user's instruction wasn't saved, but main response was delivered
        }
    }

    /**
     * Apply a single InstructionAction to UserEntity.
     * Uses universal InstructionAction structure instead of separate classes.
     */
    private void applyInstructionAction(InstructionAction action, UserEntity userEntity) {
        String actionType = action.getActionType();
        String entityType = action.getEntityType();
        String entityId = action.getEntityId();
        String value = action.getValue();
        
        // Handle ADD operations
        if (action.isAdd()) {
            switch (entityType) {
                case "linkedUser" -> {
                    addLinkedUserAlias(userEntity, entityId, value);
                }
                case "account" -> {
                    addAccountAlias(userEntity, entityId, value);
                }
                case "fund" -> {
                    addFundAlias(userEntity, entityId, value);
                }
                case "customInstruction" -> {
                    userEntity.addInstruction(value);
                    log.info("Added custom instruction: {}", value);
                }
                default -> log.warn("Unknown entity type for ADD: {}", entityType);
            }
            return;
        }
        
        // Handle REMOVE operations
        if (action.isRemove()) {
            switch (entityType) {
                case "linkedUser" -> {
                    removeLinkedUserAlias(userEntity, entityId, value);
                }
                case "account" -> {
                    removeAccountAlias(userEntity, entityId, value);
                }
                case "fund" -> {
                    removeFundAlias(userEntity, entityId, value);
                }
                case "customInstruction" -> {
                    Integer index = action.getIndex();
                    if (index != null) {
                        userEntity.removeInstruction(index);
                        log.info("Removed custom instruction at index: {}", index);
                    } else {
                        log.warn("REMOVE_CUSTOM_INSTRUCTION requires index field");
                    }
                }
                default -> log.warn("Unknown entity type for REMOVE: {}", entityType);
            }
            return;
        }
        
        // Handle UPDATE operations
        if (action.isUpdate()) {
            if ("default".equals(entityType)) {
                switch (entityId) {
                    case "currency" -> {
                        userEntity.setDefaultCurrency(value);
                        log.info("Updated default currency: {}", value);
                    }
                    case "account" -> {
                        // Find account by ID or alias
                        userEntity.findAccountByAlias(value).ifPresentOrElse(
                                account -> {
                                    userEntity.setDefaultAccount(account);
                                    log.info("Updated default account: {}", account.getAccountId());
                                },
                                () -> log.warn("Account not found: {}", value)
                        );
                    }
                    case "fund" -> {
                        // Find fund by ID or alias
                        userEntity.findFundByAlias(value).ifPresentOrElse(
                                fund -> {
                                    userEntity.setDefaultFund(fund);
                                    log.info("Updated default fund: {}", fund.getFundId());
                                },
                                () -> log.warn("Fund not found: {}", value)
                        );
                    }
                    default -> log.warn("Unknown default type: {}", entityId);
                }
            } else {
                log.warn("Unknown entity type for UPDATE: {}", entityType);
            }
            return;
        }
        
        log.warn("Unknown instruction action type: {}", actionType);
    }

//     /**
//      * Send SECOND message to user with clarification questions.
//      * Model generates the question text itself, we just send it as-is.
//      */
//     private void sendClarificationMessage(List<AskClarificationAction> clarifications, 
//                                           UserEntity userEntity, TelegramChatRequest request) {
//         // Model already generated the question text - use it as-is
//         StringBuilder clarificationMsg = new StringBuilder();
//         
//         for (int i = 0; i < clarifications.size(); i++) {
//             if (i > 0) {
//                 clarificationMsg.append("\n\n");
//             }
//             clarificationMsg.append(clarifications.get(i).getQuestion());
//         }
//         
//         log.info("Sending clarification message to user: {}", clarificationMsg);
//         
//         // IMPORTANT: Add to EXISTING pending actions, don't replace!
//         // MainAgent might have already added some pending clarifications
//         List<PendingClarificationAction> existingPending = userEntity.getPendingActions();
//         if (existingPending == null) {
//             existingPending = new ArrayList<>();
//         } else {
//             existingPending = new ArrayList<>(existingPending); // copy to avoid mutation
//         }
//         
//         // Add clarifications from CustomInstructionAgent
//         for (AskClarificationAction clarification : clarifications) {
//             existingPending.add(PendingClarificationAction.builder()
//                     .context(clarification.getContext())
//                     .build());
//         }
//         
//         userEntity.setPendingActions(existingPending);
//         
//         // Add clarification message to conversation history
//         userEntity.addToHistory(ConversationMessage.builder()
//                 .role("assistant")
//                 .content(clarificationMsg.toString())
//                 .build());
//         
//         // Save pending actions + conversation history (NOT accounts/funds)
//         userEntityService.saveConversationAndAiContext(userEntity);
//         
//         // Send SECOND message via SQS
//         TelegramChatResponse clarificationResponse = TelegramChatResponse.builder()
//                 .chatId(request.getResponseChatId())
//                 .success(true)
//                 .message(clarificationMsg.toString())
//                 .build();
//         
//         sqsPublisher.sendResponse(clarificationResponse);
//         log.info("Sent clarification message as SECOND response");
//     }

    // ═══════════════════════════════════════════════════════════════════════════
    // ALIAS MANAGEMENT - Linked Users
    // ═══════════════════════════════════════════════════════════════════════════
    
    private void addLinkedUserAlias(UserEntity userEntity, String userName, String alias) {
        List<LinkedUserEntry> linkedUsers = userEntity.getLinkedUsers();
        if (linkedUsers != null) {
            for (LinkedUserEntry linked : linkedUsers) {
                if (linked.getUserName().equalsIgnoreCase(userName)) {
                    if (linked.getAliases() == null) {
                        linked.setAliases(new ArrayList<>());
                    }
                    if (!linked.getAliases().contains(alias.toLowerCase())) {
                        linked.getAliases().add(alias.toLowerCase());
                        log.info("Added alias '{}' to linked user {}", alias, userName);
                    }
                    return;
                }
            }
        }
        log.warn("Linked user {} not found, cannot add alias", userName);
    }
    
    private void removeLinkedUserAlias(UserEntity userEntity, String userName, String alias) {
        List<LinkedUserEntry> linkedUsers = userEntity.getLinkedUsers();
        if (linkedUsers != null) {
            for (LinkedUserEntry linked : linkedUsers) {
                if (linked.getUserName().equalsIgnoreCase(userName)) {
                    if (linked.getAliases() != null) {
                        boolean removed = linked.getAliases().remove(alias.toLowerCase());
                        if (removed) {
                            log.info("Removed alias '{}' from linked user {}", alias, userName);
                        } else {
                            log.warn("Alias '{}' not found in linked user {}", alias, userName);
                        }
                    }
                    return;
                }
            }
        }
        log.warn("Linked user {} not found, cannot remove alias", userName);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ALIAS MANAGEMENT - Accounts
    // ═══════════════════════════════════════════════════════════════════════════
    
    private void addAccountAlias(UserEntity userEntity, String accountId, String alias) {
        List<AccountEntry> accounts = userEntity.getAccounts();
        if (accounts != null) {
            for (AccountEntry account : accounts) {
                if (account.getAccountId().equalsIgnoreCase(accountId)) {
                    if (account.getAliases() == null) {
                        account.setAliases(new ArrayList<>());
                    }
                    if (!account.getAliases().contains(alias.toLowerCase())) {
                        account.getAliases().add(alias.toLowerCase());
                        log.info("Added alias '{}' to account {}", alias, accountId);
                    }
                    return;
                }
            }
        }
        log.warn("Account {} not found, cannot add alias", accountId);
    }
    
    private void removeAccountAlias(UserEntity userEntity, String accountId, String alias) {
        List<AccountEntry> accounts = userEntity.getAccounts();
        if (accounts != null) {
            for (AccountEntry account : accounts) {
                if (account.getAccountId().equalsIgnoreCase(accountId)) {
                    if (account.getAliases() != null) {
                        boolean removed = account.getAliases().remove(alias.toLowerCase());
                        if (removed) {
                            log.info("Removed alias '{}' from account {}", alias, accountId);
                        } else {
                            log.warn("Alias '{}' not found in account {}", alias, accountId);
                        }
                    }
                    return;
                }
            }
        }
        log.warn("Account {} not found, cannot remove alias", accountId);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ALIAS MANAGEMENT - Funds
    // ═══════════════════════════════════════════════════════════════════════════
    
    private void addFundAlias(UserEntity userEntity, String fundId, String alias) {
        List<FundEntry> funds = userEntity.getFunds();
        if (funds != null) {
            for (FundEntry fund : funds) {
                if (fund.getFundId().equalsIgnoreCase(fundId)) {
                    if (fund.getAliases() == null) {
                        fund.setAliases(new ArrayList<>());
                    }
                    if (!fund.getAliases().contains(alias.toLowerCase())) {
                        fund.getAliases().add(alias.toLowerCase());
                        log.info("Added alias '{}' to fund {}", alias, fundId);
                    }
                    return;
                }
            }
        }
        log.warn("Fund {} not found, cannot add alias", fundId);
    }
    
    private void removeFundAlias(UserEntity userEntity, String fundId, String alias) {
        List<FundEntry> funds = userEntity.getFunds();
        if (funds != null) {
            for (FundEntry fund : funds) {
                if (fund.getFundId().equalsIgnoreCase(fundId)) {
                    if (fund.getAliases() != null) {
                        boolean removed = fund.getAliases().remove(alias.toLowerCase());
                        if (removed) {
                            log.info("Removed alias '{}' from fund {}", alias, fundId);
                        } else {
                            log.warn("Alias '{}' not found in fund {}", alias, fundId);
                        }
                    }
                    return;
                }
            }
        }
        log.warn("Fund {} not found, cannot remove alias", fundId);
    }
}

