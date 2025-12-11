package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.dto.*;
import com.github.dimka9910.sheets.ai.dto.actions.MainAgentResponse;
import com.github.dimka9910.sheets.ai.services.agents.MainAgent;
import com.github.dimka9910.sheets.ai.services.agents.MessageClassifierAgent;
import com.github.dimka9910.sheets.ai.services.agents.MessageClassifierAgent.Tag;
import com.github.dimka9910.sheets.ai.services.agents.ResponseMatcherAgent;
import com.github.dimka9910.sheets.ai.services.agents.ThirdPartyMatcherAgent;
import com.github.dimka9910.sheets.ai.services.agents.ThirdPartyMatcherAgent.MatchType;
import com.github.dimka9910.sheets.ai.telemetry.RequestTelemetry;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orchestrator - coordinates ALL AI agents and result handling.
 * 
 * Full flow:
 * 1. ClassifierAgent (gpt-4o-mini) + ResponseMatcher (gpt-4o) — parallel
 * 2. ThirdPartyMatcher (gpt-4o-mini) — if needed
 * 3. MainAgent (gpt-5-mini) — parse command
 * 4. ResultHandler — execute and build response
 */
@Slf4j
public class Orchestrator {
    
    private final MessageClassifierAgent classifierAgent;
    private final ResponseMatcherAgent responseMatcherAgent;
    private final ThirdPartyMatcherAgent thirdPartyMatcherAgent;
    private final MainAgent mainAgent;
    private final ResultHandler resultHandler;
    private final ExecutorService executor;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TYPES
    // ═══════════════════════════════════════════════════════════════════════════
    
    public record MatchedLinkedUser(String userName, String displayName) {}
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONSTRUCTORS
    // ═══════════════════════════════════════════════════════════════════════════
    
    public Orchestrator(SQSPublisher sqsPublisher, UserEntityService userContextService) {
        this.classifierAgent = new MessageClassifierAgent();
        this.responseMatcherAgent = new ResponseMatcherAgent();
        this.thirdPartyMatcherAgent = new ThirdPartyMatcherAgent();
        this.mainAgent = new MainAgent();
        this.resultHandler = new ResultHandler(sqsPublisher, userContextService);
        this.executor = Executors.newFixedThreadPool(3);
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN API
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Full processing: classify → parse → handle → ChatResponse
     */
    public ChatResponse process(ChatRequest request, UserEntity userContext, RequestTelemetry telemetry) {
        String message = request.getMessage();
        String previousBotMessage = userContext.getLastBotMessageContent();
        boolean hasPendingResponse = userContext.isAwaitingClarification();
        List<LinkedUserEntry> linkedUsers = userContext.getLinkedUsers();
        
        log.info("=== ORCHESTRATOR ===");
        log.info("Input: \"{}\"", truncate(message, 60));
        
        try {
            // Step 1: Classify (parallel)
            ClassificationResult classification = classify(message, previousBotMessage, hasPendingResponse, linkedUsers, telemetry);
            
            log.info("Classification: tags={}, isResponse={}", classification.tags(), classification.isResponse());
            
            // Step 2: MainAgent parse
            var agentRequest = new MainAgent.Request(
                    message, userContext, classification.tags(), 
                    classification.isResponse(), classification.matchedLinkedUser());
            var agentResponse = mainAgent.process(agentRequest);
            MainAgentResponse result = agentResponse.result();
            
            // Record MainAgent telemetry
            if (telemetry != null) {
                String summary = result.hasPendingClarifications() 
                        ? "PENDING: " + result.getPendingClarifications().size()
                        : "OK: " + result.getActions().size() + " action(s)";
                if (agentResponse.reasoningTokens() > 0) {
                    summary += " (reason: " + agentResponse.reasoningTokens() + ")";
                }
                telemetry.recordAgent("MainAgent", "gpt-5-mini", summary, 
                        agentResponse.latencyMs(), agentResponse.tokensUsed());
            }
            
            log.info("Parsed: {} actions, pending={}", 
                    result.getActions().size(), result.hasPendingClarifications());
            
            // Step 3: Handle result
            return resultHandler.handle(request, result, userContext);
            
        } catch (Exception e) {
            log.error("Orchestration failed: {}", e.getMessage(), e);
            if (telemetry != null) {
                telemetry.setError(e.getMessage());
            }
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
    
    private record ClassificationResult(
        Set<Tag> tags,
        boolean isResponse,
        MatchedLinkedUser matchedLinkedUser
    ) {}
    
    private ClassificationResult classify(String message, String previousBotMessage, 
                                          boolean hasPendingResponse, List<LinkedUserEntry> linkedUsers,
                                          RequestTelemetry telemetry) {
        // No previous message → just classify tags
        if (previousBotMessage == null || previousBotMessage.isBlank()) {
            var classifierResponse = classifierAgent.classify(message, null);
            
            if (telemetry != null) {
                telemetry.recordAgent("ClassifierAgent", "gpt-4o-mini", 
                        classifierResponse.tags().toString(), 
                        classifierResponse.latencyMs(), 
                        classifierResponse.tokensUsed());
            }
            
            ThirdPartyResult thirdParty = processThirdParty(message, classifierResponse.tags(), linkedUsers, telemetry);
            return new ClassificationResult(thirdParty.finalTags(), false, thirdParty.matchedUser());
        }
        
        // PARALLEL: classifier + matcher
        final boolean pending = hasPendingResponse;
        
        CompletableFuture<MessageClassifierAgent.Response> classifierFuture = CompletableFuture.supplyAsync(
                () -> classifierAgent.classify(message, previousBotMessage), executor);
        
        CompletableFuture<ResponseMatcherAgent.Response> matcherFuture = CompletableFuture.supplyAsync(
                () -> responseMatcherAgent.match(message, previousBotMessage, pending), executor);
        
        var classifierResponse = classifierFuture.join();
        var matcherResponse = matcherFuture.join();
        
        if (telemetry != null) {
            telemetry.recordAgent("ClassifierAgent", "gpt-4o-mini", 
                    classifierResponse.tags().toString(), 
                    classifierResponse.latencyMs(), 
                    classifierResponse.tokensUsed());
            telemetry.recordAgent("ResponseMatcher", "gpt-4o", 
                    matcherResponse.isResponse() ? "YES" : "NO", 
                    matcherResponse.latencyMs(), 0);
        }
        
        ThirdPartyResult thirdParty = processThirdParty(message, classifierResponse.tags(), linkedUsers, telemetry);
        return new ClassificationResult(thirdParty.finalTags(), matcherResponse.isResponse(), thirdParty.matchedUser());
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // THIRD_PARTY PROCESSING
    // ═══════════════════════════════════════════════════════════════════════════
    
    private record ThirdPartyResult(Set<Tag> finalTags, MatchedLinkedUser matchedUser) {}
    
    private ThirdPartyResult processThirdParty(String message, Set<Tag> tags, 
                                               List<LinkedUserEntry> linkedUsers, RequestTelemetry telemetry) {
        if (!tags.contains(Tag.THIRD_PARTY)) {
            return new ThirdPartyResult(tags, null);
        }
        
        if (linkedUsers == null || linkedUsers.isEmpty()) {
            Set<Tag> finalTags = new HashSet<>(tags);
            finalTags.remove(Tag.THIRD_PARTY);
            return new ThirdPartyResult(finalTags, null);
        }
        
        var result = thirdPartyMatcherAgent.match(message, linkedUsers);
        
        if (telemetry != null) {
            String matchResult = result.matchType() == MatchType.LINKED_USER 
                    ? "LINKED:" + result.matchedUserName() 
                    : "COMMENT";
            telemetry.recordAgent("ThirdPartyMatcher", "gpt-4o-mini", 
                    matchResult, result.latencyMs(), 0);
        }
        
        if (result.matchType() == MatchType.LINKED_USER) {
            return new ThirdPartyResult(
                    tags, 
                    new MatchedLinkedUser(result.matchedUserName(), result.matchedUserName())
            );
        } else {
            Set<Tag> finalTags = new HashSet<>(tags);
            finalTags.remove(Tag.THIRD_PARTY);
            return new ThirdPartyResult(finalTags, null);
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITIES
    // ═══════════════════════════════════════════════════════════════════════════
    
    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
    
    public void shutdown() {
        executor.shutdown();
    }
}
