package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.dto.actions.MainAgentResponse;
import com.github.dimka9910.sheets.ai.dto.telegram.ChatRequest;
import com.github.dimka9910.sheets.ai.dto.telegram.ChatResponse;
import com.github.dimka9910.sheets.ai.dto.user.UserEntity;
import com.github.dimka9910.sheets.ai.services.agents.MainAgent;
import com.github.dimka9910.sheets.ai.services.agents.MessageClassifierAgent;
import com.github.dimka9910.sheets.ai.services.agents.MessageClassifierAgent.Category;
import com.github.dimka9910.sheets.ai.services.agents.SimpleExpenseAgent;
import com.github.dimka9910.sheets.ai.services.agents.InternalTransferAgent;
import com.github.dimka9910.sheets.ai.services.agents.ThirdPartyActionAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
    private final MainAgent mainAgent;
    private final MainAgentResultHandler mainAgentResultHandler;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN API
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Full processing: validate → classify → route → handle → ChatResponse
     */
    public ChatResponse process(ChatRequest request, UserEntity userContext) {
        String message = request.getMessage();
        
        log.info("=== ORCHESTRATOR ===");
        log.info("Input: \"{}\"", truncate(message, 60));
        
        try {
            // Step 0: Validate basic setup
            if (!hasBasicSetup(userContext)) {
                return buildSetupRequiredResponse(request, userContext);
            }
            
            // Step 1: Classify into ONE category
            boolean hasLinkedUsers = userContext.getLinkedUsers() != null && !userContext.getLinkedUsers().isEmpty();
            Category category = classify(message, hasLinkedUsers);
            
            log.info("Classification: category={}", category);
            
            // Step 2: Route to appropriate handler
            return switch (category) {
                case SIMPLE_EXPENSE -> {
                    log.info("→ Routing to SimpleExpenseHandler");
                    yield simpleExpenseAgent.process(request, userContext);
                }
                case INTERNAL_TRANSFER -> {
                    log.info("→ Routing to InternalTransferHandler");
                    yield internalTransferAgent.process(request, userContext);
                }
                case THIRD_PARTY_ACTION -> {
                    log.info("→ Routing to ThirdPartyHandler");
                    yield thirdPartyActionAgent.process(request, userContext);
                }
                case SIMPLE_CUSTOM_INSTRUCTION -> {
                    // TODO: CustomInstructionAgent needs refactoring to match handler interface
                    log.info("→ Routing to MainAgent (SIMPLE_CUSTOM_INSTRUCTION not yet implemented)");
                    var agentRequest = new MainAgent.Request(message, userContext, category);
                    var agentResponse = mainAgent.process(agentRequest);
                    MainAgentResponse result = agentResponse.result();
                    
                    log.info("Parsed: {} actions, pending={}", 
                            result.getActions().size(), result.hasPendingClarifications());
                    
                    yield mainAgentResultHandler.handle(request, result, userContext);
                }
                case COMPLEX_ACTION -> {
                    // MainAgent handles complex actions with full context
                    log.info("→ Routing to MainAgent (COMPLEX_ACTION)");
                    var agentRequest = new MainAgent.Request(message, userContext, category);
                    var agentResponse = mainAgent.process(agentRequest);
                    MainAgentResponse result = agentResponse.result();
                    
                    log.info("Parsed: {} actions, pending={}", 
                            result.getActions().size(), result.hasPendingClarifications());
                    
                    yield mainAgentResultHandler.handle(request, result, userContext);
                }
            };
            
        } catch (Exception e) {
            log.error("Orchestration failed: {}", e.getMessage(), e);
            return ChatResponse.builder()
                    .chatId(request.getResponseChatId())
                    .success(false)
                    .message("Error: " + e.getMessage())
                    .build();
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CLASSIFICATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    private Category classify(String message, boolean hasLinkedUsers) {
        // Classify message into ONE category
        // Available categories dynamically adapt based on user context (e.g., has linked users)
        var classifierResponse = classifierAgent.classify(message, hasLinkedUsers);
        
        if (!classifierResponse.isSuccess()) {
            log.error("Classification failed: {}", classifierResponse.errorMessage());
            return Category.COMPLEX_ACTION; // Safe fallback
        }
        
        Category category = classifierResponse.category();
        log.info("ClassifierAgent: category={} (hasLinkedUsers={})", category, hasLinkedUsers);
        
        return category;
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
    private ChatResponse buildSetupRequiredResponse(ChatRequest request, UserEntity userContext) {
        log.info("User {} missing setup", userContext.getUserName());
        
        boolean hasAccounts = userContext.getAccounts() != null && !userContext.getAccounts().isEmpty();
        boolean hasFunds = userContext.getFunds() != null && !userContext.getFunds().isEmpty();
        
        StringBuilder sb = new StringBuilder();
        sb.append("⚙️User is not fully configured\n\n");
        
        return ChatResponse.builder()
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
