package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.dto.response.PendingClarificationAction;
import com.github.dimka9910.sheets.ai.dto.response.MainAgentRedirectAction;
import com.github.dimka9910.sheets.ai.dto.response.BaseAgentResponse;
import com.github.dimka9910.sheets.ai.dto.response.MainAgentResponse;
import com.github.dimka9910.sheets.ai.dto.response.CustomInstructionAgentResponse;
import com.github.dimka9910.sheets.ai.dto.telegram.TelegramChatRequest;
import com.github.dimka9910.sheets.ai.dto.telegram.TelegramChatResponse;
import com.github.dimka9910.sheets.ai.dto.user.UserEntity;
import com.github.dimka9910.sheets.ai.services.agents.MainAgent;
import com.github.dimka9910.sheets.ai.services.agents.MessageClassifierAgent;
import com.github.dimka9910.sheets.ai.services.agents.MessageClassifierAgent.Category;
import com.github.dimka9910.sheets.ai.services.agents.FinancialAgent;
import com.github.dimka9910.sheets.ai.services.agents.CustomInstructionAgent;
import com.github.dimka9910.sheets.ai.services.agents.ExpenseEditAndDeletionAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.apache.commons.collections4.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring Service for orchestrating message processing.
 * 
 * Simplified flow:
 * 1. ClassifierAgent — determine category (SIMPLE_FINANCIAL vs THIRD_PARTY_FINANCIAL vs COMPLEX_ACTION)
 * 2. Route to appropriate handler:
 *    - SIMPLE_FINANCIAL → FinancialAgent (without linked users context - token optimization)
 *    - THIRD_PARTY_FINANCIAL → FinancialAgent (with linked users context)
 *    - COMPLEX_ACTION → MainAgent (corrections, multi-step, custom instructions)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Orchestrator {
    
    private final MessageClassifierAgent classifierAgent;
    private final FinancialAgent financialAgent;
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
                // Collect all linked user names + aliases for precise classification
                List<String> linkedUserNamesAndAliases = new ArrayList<>();
                if (userContext.getLinkedUsers() != null) {
                    for (var linkedUser : userContext.getLinkedUsers()) {
                        linkedUserNamesAndAliases.add(linkedUser.getUserName());  // e.g. "KIKI"
                        if (linkedUser.getAliases() != null) {
                            linkedUserNamesAndAliases.addAll(linkedUser.getAliases());  // e.g. ["Ксюша", "kiki"]
                        }
                    }
                }
                
                category = classifierAgent.classify(message, linkedUserNamesAndAliases);
                log.info("Classification: category={} (linkedUsers={})", category, linkedUserNamesAndAliases);
            }
            
            // Step 3: Route to appropriate agent and handle result
            BaseAgentResponse agentResponse = switch (category) {
                case SIMPLE_FINANCIAL -> {
                    log.info("→ Routing to FinancialAgent (no linked users context)");
                    yield financialAgent.process(message, userContext, false);  // Token optimization: exclude linked users
                }
                case THIRD_PARTY_FINANCIAL -> {
                    log.info("→ Routing to FinancialAgent (with linked users context)");
                    yield financialAgent.process(message, userContext, true);   // Include linked users context
                }
                case COMPLEX_ACTION -> {
                    log.info("→ Routing to MainAgent (COMPLEX_ACTION)");
                    var agentRequest = new MainAgent.Request(message, userContext, category);
                    yield mainAgent.process(agentRequest).result();
                }
            };
            
            log.info("Agent returned: type={}, pending={}", 
                    agentResponse.getClass().getSimpleName(),
                    !CollectionUtils.isEmpty(agentResponse.getPendingClarifications()));
            
            // Step 4: Handle redirects if MainAgent returned redirects
            if (agentResponse instanceof MainAgentResponse mainResponse && !CollectionUtils.isEmpty(mainResponse.getRedirects())) {
                log.info("MainAgent returned {} redirects", mainResponse.getRedirects().size());
                agentResponse = handleRedirects(mainResponse, userContext);
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
     * MainAgent can return redirects to offload simple requests to faster, cheaper specialized agents.
     * 
     * @param mainResponse MainAgentResponse containing redirects
     * @param userContext User context for the specialized agent
     * @return Response from the specialized agent (with merged pending clarifications from MainAgent)
     */
    private BaseAgentResponse handleRedirects(MainAgentResponse mainResponse, UserEntity userContext) {
        List<MainAgentRedirectAction> redirects = mainResponse.getRedirects();
        
        if (redirects.isEmpty()) {
            return mainResponse;
        }
        
        // Currently we only support single redirect per response
        if (redirects.size() > 1) {
            log.warn("Multiple redirects found ({}), processing only the first one", redirects.size());
        }
        
        MainAgentRedirectAction redirect = redirects.get(0);
        String message = redirect.getMessage();
        
        log.info("→ Redirecting to {} with message: \"{}\"", 
                redirect.getAgentType(), truncate(message, 60));
        
        // Route to specialized agent based on agentType
        BaseAgentResponse specializedResponse = switch (redirect.getAgentType()) {
            case CUSTOM_INSTRUCTION -> {
                log.info("  ↳ Calling CustomInstructionAgent");
                // CustomInstructionAgent expects List<String> instructions, so wrap message in list
                var ciRequest = new CustomInstructionAgent.Request(List.of(message), userContext);
                var ciResponse = customInstructionAgent.process(ciRequest);
                
                // Convert CustomInstructionAgent.Response to CustomInstructionAgentResponse
                // TODO: Update CustomInstructionAgent to return CustomInstructionAgentResponse directly
                yield CustomInstructionAgentResponse.builder()
                        .customInstructionActions(List.of())
                        .message(ciResponse.explanation() != null ? ciResponse.explanation() : "Settings updated")
                        .build();
            }
            case FINANCIAL -> {
                log.info("  ↳ Calling FinancialAgent (no linked users)");
                yield financialAgent.process(message, userContext, false);
            }
            case THIRD_PARTY_FINANCIAL -> {
                log.info("  ↳ Calling FinancialAgent (with linked users)");
                yield financialAgent.process(message, userContext, true);
            }
            case CORRECTION -> {
                log.info("  ↳ Calling ExpenseEditAndDeletionAgent");
                yield expenseEditAndDeletionAgent.process(message, userContext);
            }
        };
        
        log.info("  ✅ Specialized agent returned: type={}, pending={}", 
                specializedResponse.getClass().getSimpleName(),
                !CollectionUtils.isEmpty(specializedResponse.getPendingClarifications()));
        
        // IMPORTANT: Merge pending clarifications from MainAgent (if any) into specialized response
        // MainAgent might have asked for clarification while also redirecting
        if (mainResponse.getPendingClarifications() != null && !mainResponse.getPendingClarifications().isEmpty()) {
            List<PendingClarificationAction> mergedPending = new ArrayList<>(mainResponse.getPendingClarifications());
            if (specializedResponse.getPendingClarifications() != null) {
                mergedPending.addAll(specializedResponse.getPendingClarifications());
            }
            specializedResponse.setPendingClarifications(mergedPending);
            log.debug("  → Merged {} pending from MainAgent", mainResponse.getPendingClarifications().size());
        }
        
        return specializedResponse;
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
