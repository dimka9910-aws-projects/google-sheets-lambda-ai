package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.dto.response.PendingClarificationAction;
import com.github.dimka9910.sheets.ai.dto.response.MainAgentRedirectAction;
import com.github.dimka9910.sheets.ai.dto.response.CombinedAgentResponse;
import com.github.dimka9910.sheets.ai.dto.response.BaseAgentResponse;
import com.github.dimka9910.sheets.ai.dto.response.MainAgentResponse;
import com.github.dimka9910.sheets.ai.dto.response.CustomInstructionAction;
import com.github.dimka9910.sheets.ai.dto.response.CustomInstructionAgentResponse;
import com.github.dimka9910.sheets.ai.dto.response.FinancialAction;
import com.github.dimka9910.sheets.ai.dto.response.FinancialAgentResponse;
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
                    var mainResponse = mainAgent.process(agentRequest);
                    
                    // Check if MainAgent succeeded
                    if (!mainResponse.isSuccess()) {
                        log.error("❌ MainAgent failed: {}", mainResponse.errorMessage());
                        // Create error response and yield it
                        yield MainAgentResponse.builder()
                                .redirects(List.of())
                                .pendingClarifications(List.of())
                                .message("Something went wrong. Please try again.")
                                .build();
                    }
                    
                    yield mainResponse.result();
                }
            };
            
            if (agentResponse == null) {
                log.error("❌ Agent returned NULL response!");
                return TelegramChatResponse.builder()
                        .chatId(request.getResponseChatId())
                        .success(false)
                        .message("Internal error. Please try again.")
                        .build();
            }
            
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
                    .message("Something went wrong. Please try again.")
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

        // Execute ALL redirects (MainAgent may decompose "A and B" into multiple actions).
        List<FinancialAction> mergedFinancial = new ArrayList<>();
        List<CustomInstructionAction> mergedInstructions = new ArrayList<>();
        List<PendingClarificationAction> mergedPending = new ArrayList<>();
        if (mainResponse.getPendingClarifications() != null) {
            mergedPending.addAll(mainResponse.getPendingClarifications());
        }

        for (MainAgentRedirectAction redirect : redirects) {
            String ticket = redirect.getMessage();
            log.info("→ Redirecting to {} with message: \"{}\"",
                    redirect.getAgentType(), truncate(ticket, 60));

            BaseAgentResponse r = switch (redirect.getAgentType()) {
                case CUSTOM_INSTRUCTION -> {
                    log.info("  ↳ Calling CustomInstructionAgent");
                    var ciRequest = new CustomInstructionAgent.Request(List.of(ticket), userContext);
                    yield customInstructionAgent.process(ciRequest);
                }
                case FINANCIAL -> {
                    log.info("  ↳ Calling FinancialAgent (no linked users)");
                    yield financialAgent.process(ticket, userContext, false);
                }
                case THIRD_PARTY_FINANCIAL -> {
                    log.info("  ↳ Calling FinancialAgent (with linked users)");
                    yield financialAgent.process(ticket, userContext, true);
                }
                case CORRECTION -> {
                    log.info("  ↳ Calling ExpenseEditAndDeletionAgent");
                    yield expenseEditAndDeletionAgent.process(ticket, userContext);
                }
            };

            log.info("  ✅ Specialized agent returned: type={}, pending={}",
                    r.getClass().getSimpleName(),
                    !CollectionUtils.isEmpty(r.getPendingClarifications()));

            if (r.getPendingClarifications() != null) mergedPending.addAll(r.getPendingClarifications());

            if (r instanceof FinancialAgentResponse fr && fr.getFinancialActions() != null) {
                mergedFinancial.addAll(fr.getFinancialActions());
            } else if (r instanceof CustomInstructionAgentResponse cr && cr.getCustomInstructionActions() != null) {
                mergedInstructions.addAll(cr.getCustomInstructionActions());
            }
        }

        // If we ended up with multiple action types, return a composite response but keep MainAgent's message
        // (it should describe what happened to the user in one coherent response).
        if (!mergedFinancial.isEmpty() || !mergedInstructions.isEmpty()) {
            return CombinedAgentResponse.builder()
                    .message(mainResponse.getMessage())
                    .pendingClarifications(mergedPending)
                    .financialActions(mergedFinancial)
                    .customInstructionActions(mergedInstructions)
                    .build();
        }

        // No actions produced; keep MainAgent response but preserve merged pending.
        mainResponse.setPendingClarifications(mergedPending);
        return mainResponse;
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
