package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.dto.actions.MainAgentResponse;
import com.github.dimka9910.sheets.ai.dto.telegram.ChatRequest;
import com.github.dimka9910.sheets.ai.dto.telegram.ChatResponse;
import com.github.dimka9910.sheets.ai.dto.user.LinkedUserEntry;
import com.github.dimka9910.sheets.ai.dto.user.UserEntity;
import com.github.dimka9910.sheets.ai.services.agents.MainAgent;
import com.github.dimka9910.sheets.ai.services.agents.MessageClassifierAgent;
import com.github.dimka9910.sheets.ai.services.agents.MessageClassifierAgent.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Spring Service for orchestrating ALL AI agents and result handling.
 * 
 * Full flow:
 * 1. ClassifierAgent (gpt-4o-mini) — determine context tags
 * 2. MainAgent (gpt-5-mini) — parse command with full context
 * 3. ResultHandler — execute and build response
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Orchestrator {
    
    private final MessageClassifierAgent classifierAgent;
    private final MainAgent mainAgent;
    private final MainAgentResultHandler mainAgentResultHandler;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN API
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Full processing: validate → classify → parse → handle → ChatResponse
     */
    public ChatResponse process(ChatRequest request, UserEntity userContext) {
        String message = request.getMessage();
        String previousBotMessage = userContext.getLastBotMessageContent();
        boolean hasPendingResponse = userContext.isAwaitingClarification();
        List<LinkedUserEntry> linkedUsers = userContext.getLinkedUsers();
        
        log.info("=== ORCHESTRATOR ===");
        log.info("Input: \"{}\"", truncate(message, 60));
        
        try {
            // Step 0: Validate basic setup
            if (!hasBasicSetup(userContext)) {
                return buildSetupRequiredResponse(request, userContext);
            }
            
            // Step 1: Classify
            Set<Tag> tags = classify(message, previousBotMessage, hasPendingResponse, linkedUsers);
            
            log.info("Classification: tags={}", tags);
            
            // Step 2: MainAgent parse (with full context always)
            var agentRequest = new MainAgent.Request(
                    message, userContext, tags);
            var agentResponse = mainAgent.process(agentRequest);
            MainAgentResponse result = agentResponse.result();
            
            log.info("Parsed: {} actions, pending={}", 
                    result.getActions().size(), result.hasPendingClarifications());
            
            // Step 3: Handle result
            return mainAgentResultHandler.handle(request, result, userContext);
            
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
    
    private Set<Tag> classify(String message, String previousBotMessage, 
                              boolean hasPendingResponse, List<LinkedUserEntry> linkedUsers) {
        // Classify message tags
        var classifierResponse = classifierAgent.classify(message, previousBotMessage);
        
        log.info("ClassifierAgent: tags={}", 
                classifierResponse.tags());
        
        Set<Tag> tags = classifierResponse.tags();
        
        // Remove THIRD_PARTY tag if user has no linked users
        if (tags.contains(Tag.THIRD_PARTY)) {
            if (linkedUsers == null || linkedUsers.isEmpty()) {
                tags = new HashSet<>(tags);
                tags.remove(Tag.THIRD_PARTY);
                log.info("Removed THIRD_PARTY tag - no linked users available");
            }
        }
        
        return tags;
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
