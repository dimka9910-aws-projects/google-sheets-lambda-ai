package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.dto.actions.AgentAction;
import com.github.dimka9910.sheets.ai.dto.actions.MainAgentResponse;
import com.github.dimka9910.sheets.ai.dto.actions.PendingClarificationAction;
import com.github.dimka9910.sheets.ai.dto.actions.RedirectToAgentAction;
import com.github.dimka9910.sheets.ai.dto.telegram.TelegramChatRequest;
import com.github.dimka9910.sheets.ai.dto.telegram.TelegramChatResponse;
import com.github.dimka9910.sheets.ai.dto.user.UserEntity;
import com.github.dimka9910.sheets.ai.services.agents.MainAgent;
import com.github.dimka9910.sheets.ai.services.agents.MessageClassifierAgent;
import com.github.dimka9910.sheets.ai.services.agents.MessageClassifierAgent.Category;
import com.github.dimka9910.sheets.ai.services.agents.SimpleExpenseAgent;
import com.github.dimka9910.sheets.ai.services.agents.InternalTransferAgent;
import com.github.dimka9910.sheets.ai.services.agents.ThirdPartyActionAgent;
import com.github.dimka9910.sheets.ai.services.agents.CustomInstructionAgent;
import com.github.dimka9910.sheets.ai.services.agents.ExpenseEditAndDeletionAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring Service for orchestrating message processing.
 * 
 * New simplified flow:
 * 1. ClassifierAgent — determine category (SIMPLE vs COMPLEX)
 * 2. Route to appropriate handler:
 *    - SIMPLE_EXPENSE → SimpleExpenseHandler (TODO)
 *    - INTERNAL_TRANSFER → InternalTransferHandler (TODO)
 *    - THIRD_PARTY_ACTION → ThirdPartyHandler (TODO)
 *    - SIMPLE_CUSTOM_INSTRUCTION → CustomInstructionAgent (existing)
 *    - COMPLEX_ACTION → MainAgent (with full context)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Orchestrator {
    
    private final MessageClassifierAgent classifierAgent;
    private final SimpleExpenseAgent simpleExpenseAgent;
    private final InternalTransferAgent internalTransferAgent;
    private final ThirdPartyActionAgent thirdPartyActionAgent;
    private final CustomInstructionAgent customInstructionAgent;
    private final ExpenseEditAndDeletionAgent expenseEditAndDeletionAgent;
    private final MainAgent mainAgent;
    private final MainAgentResultHandler mainAgentResultHandler;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN API
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Full processing: validate → classify → route → handle → TelegramChatResponse
     */
    public TelegramChatResponse process(TelegramChatRequest request, UserEntity userContext) {
        String message = request.getMessage();
        
        log.info("=== ORCHESTRATOR ===");
        log.info("Input: \"{}\"", truncate(message, 60));
        
        try {
            // Step 0: Validate basic setup
            if (!hasBasicSetup(userContext)) {
                return buildSetupRequiredResponse(request, userContext);
            }
            
            // Step 1: Check for pending clarifications
            // If user has pending clarifications, they are likely answering them
            // → Route to MainAgent to understand context and resolve pending
            boolean hasPending = userContext.getPendingActions() != null && !userContext.getPendingActions().isEmpty();
            
            Category category;
            if (hasPending) {
                log.info("User has {} pending clarifications → routing to MainAgent", userContext.getPendingActions().size());
                category = Category.COMPLEX_ACTION;  // Force MainAgent
            } else {
                // Step 2: Classify into ONE category
                boolean hasLinkedUsers = userContext.getLinkedUsers() != null && !userContext.getLinkedUsers().isEmpty();
                category = classifierAgent.classify(message, hasLinkedUsers);
                log.info("Classification: category={}", category);
            }
            
            // Step 3: Route to appropriate agent and handle result
            MainAgentResponse agentResponse = switch (category) {
                case SIMPLE_EXPENSE -> {
                    log.info("→ Routing to SimpleExpenseAgent");
                    yield simpleExpenseAgent.process(message, userContext);
                }
                case INTERNAL_TRANSFER -> {
                    log.info("→ Routing to InternalTransferAgent");
                    yield internalTransferAgent.process(message, userContext);
                }
                case THIRD_PARTY_ACTION -> {
                    log.info("→ Routing to ThirdPartyActionAgent");
                    yield thirdPartyActionAgent.process(message, userContext);
                }
                case SIMPLE_CUSTOM_INSTRUCTION -> {
                    log.info("→ Routing to MainAgent (SIMPLE_CUSTOM_INSTRUCTION)");
                    var agentRequest = new MainAgent.Request(message, userContext, category);
                    yield mainAgent.process(agentRequest).result();
                }
                case COMPLEX_ACTION -> {
                    log.info("→ Routing to MainAgent (COMPLEX_ACTION)");
                    var agentRequest = new MainAgent.Request(message, userContext, category);
                    yield mainAgent.process(agentRequest).result();
                }
            };
            
            log.info("Agent parsed: {} actions, pending={}, redirects={}", 
                    agentResponse.getActions().size(), 
                    agentResponse.hasPendingClarifications(),
                    agentResponse.hasRedirects());
            
            // Step 4: Handle redirects if any
            if (agentResponse.hasRedirects()) {
                agentResponse = handleRedirects(agentResponse, userContext);
            }
            
            return mainAgentResultHandler.handle(request, agentResponse, userContext);
            
        } catch (Exception e) {
            log.error("Orchestration failed: {}", e.getMessage(), e);
            return TelegramChatResponse.builder()
                    .chatId(request.getResponseChatId())
                    .success(false)
                    .message("Error: " + e.getMessage())
                    .build();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REDIRECT HANDLING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Handle REDIRECT_TO_AGENT actions by calling the appropriate specialized agent.
     * 
     * MainAgent (or any agent) can return REDIRECT_TO_AGENT actions to offload
     * simple requests to faster, cheaper specialized agents.
     * 
     * @param agentResponse Response containing REDIRECT_TO_AGENT actions
     * @param userContext User context for the specialized agent
     * @return New response from the specialized agent
     */
    private MainAgentResponse handleRedirects(MainAgentResponse agentResponse, UserEntity userContext) {
        List<RedirectToAgentAction> redirects = agentResponse.getRedirectActions();
        
        if (redirects.isEmpty()) {
            return agentResponse;
        }
        
        // Currently we only support single redirect per response
        // (MainAgent should not return multiple redirects in one response)
        if (redirects.size() > 1) {
            log.warn("Multiple redirects found ({}), processing only the first one", redirects.size());
        }
        
        RedirectToAgentAction redirect = redirects.get(0);
        String message = redirect.getMessage();
        
        log.info("→ Redirecting to {} with message: \"{}\"", 
                redirect.getAgentType(), truncate(message, 60));
        
        // Route to specialized agent based on agentType
        MainAgentResponse specializedResponse = switch (redirect.getAgentType()) {
            case CUSTOM_INSTRUCTION -> {
                log.info("  ↳ Calling CustomInstructionAgent");
                // CustomInstructionAgent expects List<String> instructions, so wrap message in list
                var ciRequest = new CustomInstructionAgent.Request(List.of(message), userContext);
                var ciResponse = customInstructionAgent.process(ciRequest);
                
                // Convert CustomInstructionAgent.Response to MainAgentResponse
                // For now, just return a simple response (TODO: proper conversion if needed)
                yield MainAgentResponse.builder()
                        .actions(List.of()) // CustomInstructionAgent handles actions differently
                        .response(ciResponse.explanation() != null ? ciResponse.explanation() : "Settings updated")
                        .build();
            }
            case SIMPLE_EXPENSE -> {
                log.info("  ↳ Calling SimpleExpenseAgent");
                yield simpleExpenseAgent.process(message, userContext);
            }
            case INTERNAL_TRANSFER -> {
                log.info("  ↳ Calling InternalTransferAgent");
                yield internalTransferAgent.process(message, userContext);
            }
            case THIRD_PARTY_ACTION -> {
                log.info("  ↳ Calling ThirdPartyActionAgent");
                yield thirdPartyActionAgent.process(message, userContext);
            }
            case CORRECTION -> {
                log.info("  ↳ Calling ExpenseEditAndDeletionAgent");
                yield expenseEditAndDeletionAgent.process(message, userContext);
            }
        };
        
        log.info("  ✅ Specialized agent returned: {} actions, pending={}", 
                specializedResponse.getActions().size(), 
                specializedResponse.hasPendingClarifications());
        
        // IMPORTANT: Merge ALL actions from BOTH MainAgent and specialized agent
        // MainAgent might have created pending clarifications BEFORE redirecting
        // Specialized agent might have created NEW pending/financial/utils actions during processing
        // We need to collect ALL actions from the entire chain
        List<AgentAction> allActions = new ArrayList<>();
        
        // Add pending clarifications from original MainAgent response (if any)
        // These might exist if MainAgent asked for clarification while also redirecting
        if (agentResponse.getPendingClarifications() != null && !agentResponse.getPendingClarifications().isEmpty()) {
            allActions.addAll(agentResponse.getPendingClarifications());
            log.debug("  → Merged {} pending from MainAgent", agentResponse.getPendingClarifications().size());
        }
        
        // Add ALL actions from specialized agent response
        if (specializedResponse.getActions() != null) {
            allActions.addAll(specializedResponse.getActions());
        }
        
        // Return merged response
        // Use specialized agent's response text, but merged actions
        return MainAgentResponse.builder()
                .actions(allActions)  // MERGED actions from entire chain
                .response(specializedResponse.getResponse())
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // VALIDATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Check if user has basic setup (at least 1 account and 1 fund).
     */
    private boolean hasBasicSetup(UserEntity userContext) {
        boolean hasAccounts = userContext.getAccounts() != null && !userContext.getAccounts().isEmpty();
        boolean hasFunds = userContext.getFunds() != null && !userContext.getFunds().isEmpty();
        return hasAccounts && hasFunds;
    }
    
    /**
     * Build response for user missing setup.
     */
    private TelegramChatResponse buildSetupRequiredResponse(TelegramChatRequest request, UserEntity userContext) {
        log.info("User {} missing setup", userContext.getUserName());
        
        StringBuilder sb = new StringBuilder();
        sb.append("⚙️User is not fully configured\n\n");
        
        return TelegramChatResponse.builder()
                .chatId(request.getResponseChatId())
                .success(true)
                .message(sb.toString())
                .build();
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITIES
    // ═══════════════════════════════════════════════════════════════════════════
    
    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
