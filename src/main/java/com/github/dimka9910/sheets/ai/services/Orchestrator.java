package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.services.llm.MessageClassifierAgent;
import com.github.dimka9910.sheets.ai.services.llm.MessageClassifierAgent.Tag;
import com.github.dimka9910.sheets.ai.services.llm.MessageClassifierAgent.TagsResult;
import com.github.dimka9910.sheets.ai.services.llm.ResponseMatcherAgent;
import com.github.dimka9910.sheets.ai.services.llm.ResponseMatcherAgent.MatchResult;
import com.github.dimka9910.sheets.ai.services.llm.ResponseMatcherAgent.ResponseType;
import com.github.dimka9910.sheets.ai.services.llm.ThirdPartyMatcherAgent;
import com.github.dimka9910.sheets.ai.services.llm.ThirdPartyMatcherAgent.LinkedUser;
import com.github.dimka9910.sheets.ai.services.llm.ThirdPartyMatcherAgent.MatchType;
import com.github.dimka9910.sheets.ai.telemetry.RequestTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orchestrator - coordinates classification agents and determines routing.
 * 
 * Calls agents:
 * - MessageClassifierAgent (gpt-4o-mini) → tags for context loading
 * - ResponseMatcherAgent (gpt-4o) → is this a response to previous bot message?
 * - ThirdPartyMatcherAgent (gpt-4o-mini) → if THIRD_PARTY tag, resolve linked user
 * 
 * Based on results, determines:
 * - Which model to use (FAST/SMART)
 * - What context to load
 * - Whether this is a response or new command
 * - Matched linked user (if THIRD_PARTY)
 */
public class Orchestrator {
    
    private static final Logger logger = LoggerFactory.getLogger(Orchestrator.class);
    
    private final MessageClassifierAgent classifierAgent;
    private final ResponseMatcherAgent responseMatcherAgent;
    private final ThirdPartyMatcherAgent thirdPartyMatcherAgent;
    private final ExecutorService executor;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TYPES
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Which model to route the request to.
     */
    public enum ModelChoice {
        FAST,   // gpt-4o-mini - simple requests
        SMART   // gpt-5-mini - complex, corrections, math, ambiguous
    }
    
    /**
     * Matched linked user info (if THIRD_PARTY resolved to a linked user).
     */
    public record MatchedLinkedUser(
        String userId,
        String name
    ) {}
    
    /**
     * Result of orchestration.
     */
    public record OrchestrationResult(
        String originalMessage,
        ResponseType responseType,
        Set<Tag> tags,
        ModelChoice model,
        MatchedLinkedUser matchedLinkedUser,  // null if no THIRD_PARTY or just COMMENT
        String rawJson,
        long totalLatencyMs,
        long classifierLatencyMs,
        long matcherLatencyMs,
        long thirdPartyLatencyMs,
        int tokensUsed
    ) {
        public boolean isResponse() {
            return responseType == ResponseType.YES;
        }
        
        public boolean hasLinkedUser() {
            return matchedLinkedUser != null;
        }
        
        public boolean needsTransferContext() {
            return tags.contains(Tag.TRANSFER);
        }
        
        public boolean isComplex() {
            return tags.contains(Tag.COMPLEX);
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONSTRUCTORS
    // ═══════════════════════════════════════════════════════════════════════════
    
    public Orchestrator() {
        this.classifierAgent = new MessageClassifierAgent();
        this.responseMatcherAgent = new ResponseMatcherAgent();
        this.thirdPartyMatcherAgent = new ThirdPartyMatcherAgent();
        this.executor = Executors.newFixedThreadPool(3);
    }
    
    public Orchestrator(MessageClassifierAgent classifierAgent, 
                       ResponseMatcherAgent responseMatcherAgent,
                       ThirdPartyMatcherAgent thirdPartyMatcherAgent) {
        this.classifierAgent = classifierAgent;
        this.responseMatcherAgent = responseMatcherAgent;
        this.thirdPartyMatcherAgent = thirdPartyMatcherAgent;
        this.executor = Executors.newFixedThreadPool(3);
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Process user message with telemetry collection.
     * PRIMARY METHOD - use this for full telemetry support.
     */
    public OrchestrationResult process(String message, String previousBotMessage, 
                                       boolean hasPendingResponse, List<LinkedUser> linkedUsers,
                                       RequestTelemetry telemetry) {
        long startTime = System.currentTimeMillis();
        
        logger.info("=== ORCHESTRATOR ===");
        logger.info("Input: \"{}\"", truncate(message, 60));
        
        try {
            // No previous message → just classify tags, responseType = NO
            if (previousBotMessage == null || previousBotMessage.isBlank()) {
                TagsResult tagsResult = classifierAgent.classify(message, null);
                
                // Record telemetry
                if (telemetry != null) {
                    telemetry.recordAgent("ClassifierAgent", "gpt-4o-mini", 
                            tagsResult.tags().toString(), tagsResult.latencyMs(), tagsResult.tokensUsed());
                }
                
                // Process THIRD_PARTY tag
                ThirdPartyResult thirdPartyResult = processThirdParty(message, tagsResult.tags(), linkedUsers, telemetry);
                
                ModelChoice model = tagsResult.isComplex() ? ModelChoice.SMART : ModelChoice.FAST;
                
                return new OrchestrationResult(
                        message,
                        ResponseType.NO,
                        thirdPartyResult.finalTags(),
                        model,
                        thirdPartyResult.matchedUser(),
                        tagsResult.rawJson(),
                        System.currentTimeMillis() - startTime,
                        tagsResult.latencyMs(),
                        0,
                        thirdPartyResult.latencyMs(),
                        tagsResult.tokensUsed()
                );
            }
            
            // PARALLEL: classifier (gpt-4o-mini) + matcher (gpt-4o)
            final boolean pending = hasPendingResponse;
            
            CompletableFuture<TagsResult> classifierFuture = CompletableFuture.supplyAsync(
                    () -> classifierAgent.classify(message, previousBotMessage), executor);
            
            CompletableFuture<MatchResult> matcherFuture = CompletableFuture.supplyAsync(
                    () -> responseMatcherAgent.match(message, previousBotMessage, pending), executor);
            
            // Wait for both
            TagsResult tagsResult = classifierFuture.join();
            MatchResult matchResult = matcherFuture.join();
            
            // Record telemetry
            if (telemetry != null) {
                telemetry.recordAgent("ClassifierAgent", "gpt-4o-mini", 
                        tagsResult.tags().toString(), tagsResult.latencyMs(), tagsResult.tokensUsed());
                telemetry.recordAgent("ResponseMatcher", "gpt-4o", 
                        matchResult.responseType().name(), matchResult.latencyMs(), 0);
            }
            
            // Process THIRD_PARTY tag (after classifier completes)
            ThirdPartyResult thirdPartyResult = processThirdParty(message, tagsResult.tags(), linkedUsers, telemetry);
            
            // Determine model
            ModelChoice model = tagsResult.isComplex() ? ModelChoice.SMART : ModelChoice.FAST;
            
            long totalLatency = System.currentTimeMillis() - startTime;
            
            logger.info("Parallel complete: classifier={}ms, matcher={}ms, thirdParty={}ms, total={}ms", 
                    tagsResult.latencyMs(), matchResult.latencyMs(), thirdPartyResult.latencyMs(), totalLatency);
            
            return new OrchestrationResult(
                    message,
                    matchResult.responseType(),
                    thirdPartyResult.finalTags(),
                    model,
                    thirdPartyResult.matchedUser(),
                    tagsResult.rawJson() + " | responseType=" + matchResult.responseType(),
                    totalLatency,
                    tagsResult.latencyMs(),
                    matchResult.latencyMs(),
                    thirdPartyResult.latencyMs(),
                    tagsResult.tokensUsed()
            );
            
        } catch (Exception e) {
            logger.error("Orchestration failed: {}", e.getMessage(), e);
            if (telemetry != null) {
                telemetry.setError(e.getMessage());
            }
            return new OrchestrationResult(
                    message,
                    ResponseType.NO,
                    Set.of(Tag.FINANCIAL),
                    ModelChoice.SMART,
                    null,
                    "{\"error\": \"" + e.getMessage() + "\"}",
                    System.currentTimeMillis() - startTime,
                    0,
                    0,
                    0,
                    0
            );
        }
    }
    
    /**
     * Process without telemetry (backward compatible).
     */
    public OrchestrationResult process(String message, String previousBotMessage, 
                                       boolean hasPendingResponse, List<LinkedUser> linkedUsers) {
        return process(message, previousBotMessage, hasPendingResponse, linkedUsers, null);
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // THIRD_PARTY PROCESSING
    // ═══════════════════════════════════════════════════════════════════════════
    
    private record ThirdPartyResult(
        Set<Tag> finalTags,
        MatchedLinkedUser matchedUser,
        long latencyMs
    ) {}
    
    /**
     * Process THIRD_PARTY tag:
     * - If tag present → call ThirdPartyMatcherAgent
     * - If COMMENT → remove THIRD_PARTY tag (just a description)
     * - If LINKED_USER → keep tag + return matched user
     */
    private ThirdPartyResult processThirdParty(String message, Set<Tag> tags, 
                                               List<LinkedUser> linkedUsers, RequestTelemetry telemetry) {
        // No THIRD_PARTY tag → return as-is
        if (!tags.contains(Tag.THIRD_PARTY)) {
            return new ThirdPartyResult(tags, null, 0);
        }
        
        // No linked users → treat as COMMENT, remove tag
        if (linkedUsers == null || linkedUsers.isEmpty()) {
            Set<Tag> finalTags = new HashSet<>(tags);
            finalTags.remove(Tag.THIRD_PARTY);
            logger.info("THIRD_PARTY: no linked users → COMMENT, removing tag");
            return new ThirdPartyResult(finalTags, null, 0);
        }
        
        // Call ThirdPartyMatcherAgent
        var result = thirdPartyMatcherAgent.match(message, linkedUsers);
        
        // Record telemetry
        if (telemetry != null) {
            String matchResult = result.matchType() == MatchType.LINKED_USER 
                    ? "LINKED:" + result.matchedUserName() 
                    : "COMMENT";
            telemetry.recordAgent("ThirdPartyMatcher", "gpt-4o-mini", 
                    matchResult, result.latencyMs(), 0);
        }
        
        if (result.matchType() == MatchType.LINKED_USER) {
            // Keep THIRD_PARTY tag + return matched user
            logger.info("THIRD_PARTY: matched {} ({})", result.matchedUserName(), result.reasoning());
            return new ThirdPartyResult(
                    tags, 
                    new MatchedLinkedUser(result.matchedUserId(), result.matchedUserName()),
                    result.latencyMs()
            );
        } else {
            // COMMENT → remove tag
            Set<Tag> finalTags = new HashSet<>(tags);
            finalTags.remove(Tag.THIRD_PARTY);
            logger.info("THIRD_PARTY: COMMENT ({}), removing tag", result.reasoning());
            return new ThirdPartyResult(finalTags, null, result.latencyMs());
        }
    }
    
    /**
     * Process with all params except linkedUsers.
     */
    public OrchestrationResult process(String message, String previousBotMessage, boolean hasPendingResponse) {
        return process(message, previousBotMessage, hasPendingResponse, null);
    }
    
    /**
     * Process with previous message but no pending flag (defaults to false).
     */
    public OrchestrationResult process(String message, String previousBotMessage) {
        return process(message, previousBotMessage, false, null);
    }
    
    /**
     * Process without previous context.
     */
    public OrchestrationResult process(String message) {
        return process(message, null, false, null);
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITIES
    // ═══════════════════════════════════════════════════════════════════════════
    
    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
    
    /**
     * Shutdown executor (call on app shutdown).
     */
    public void shutdown() {
        executor.shutdown();
    }
}
