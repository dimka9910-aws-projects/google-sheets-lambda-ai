package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.services.llm.MessageClassifierAgent;
import com.github.dimka9910.sheets.ai.services.llm.MessageClassifierAgent.Tag;
import com.github.dimka9910.sheets.ai.services.llm.MessageClassifierAgent.TagsResult;
import com.github.dimka9910.sheets.ai.services.llm.ResponseMatcherAgent;
import com.github.dimka9910.sheets.ai.services.llm.ResponseMatcherAgent.MatchResult;
import com.github.dimka9910.sheets.ai.services.llm.ResponseMatcherAgent.ResponseType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orchestrator - coordinates classification agents and determines routing.
 * 
 * Calls two agents IN PARALLEL:
 * - MessageClassifierAgent (gpt-4o-mini) → tags for context loading
 * - ResponseMatcherAgent (gpt-4o) → is this a response to previous bot message?
 * 
 * Based on results, determines:
 * - Which model to use (FAST/SMART)
 * - What context to load
 * - Whether this is a response or new command
 */
public class Orchestrator {
    
    private static final Logger logger = LoggerFactory.getLogger(Orchestrator.class);
    
    private final MessageClassifierAgent classifierAgent;
    private final ResponseMatcherAgent responseMatcherAgent;
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
     * Result of orchestration.
     */
    public record OrchestrationResult(
        String originalMessage,
        ResponseType responseType,
        Set<Tag> tags,
        ModelChoice model,
        String rawJson,
        long totalLatencyMs,
        long classifierLatencyMs,
        long matcherLatencyMs,
        int tokensUsed
    ) {
        public boolean isResponse() {
            return responseType == ResponseType.YES;
        }
        
        public boolean needsLinkedUsers() {
            return tags.contains(Tag.THIRD_PARTY) || tags.contains(Tag.TRANSFER);
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
        this.executor = Executors.newFixedThreadPool(2);
    }
    
    public Orchestrator(MessageClassifierAgent classifierAgent, ResponseMatcherAgent responseMatcherAgent) {
        this.classifierAgent = classifierAgent;
        this.responseMatcherAgent = responseMatcherAgent;
        this.executor = Executors.newFixedThreadPool(2);
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Process user message and determine routing.
     * Calls both agents in parallel for speed.
     * 
     * @param message User's message
     * @param previousBotMessage Previous bot message (can be null)
     * @param hasPendingResponse True if bot asked a question and expects answer,
     *                           False if bot confirmed something (user might correct)
     * @return OrchestrationResult with all routing decisions
     */
    public OrchestrationResult process(String message, String previousBotMessage, boolean hasPendingResponse) {
        long startTime = System.currentTimeMillis();
        
        logger.info("=== ORCHESTRATOR ===");
        logger.info("Input: \"{}\"", truncate(message, 60));
        logger.info("Pending: {}", hasPendingResponse);
        if (previousBotMessage != null) {
            logger.info("Prev: \"{}\"", truncate(previousBotMessage, 40));
        }
        
        try {
            // No previous message → just classify tags, responseType = NO
            if (previousBotMessage == null || previousBotMessage.isBlank()) {
                TagsResult tagsResult = classifierAgent.classify(message, null);
                ModelChoice model = tagsResult.isComplex() ? ModelChoice.SMART : ModelChoice.FAST;
                
                logger.info("Result: responseType=NO (no prev), tags={}, model={}", 
                        tagsResult.tags(), model);
                
                return new OrchestrationResult(
                        message,
                        ResponseType.NO,
                        tagsResult.tags(),
                        model,
                        tagsResult.rawJson(),
                        System.currentTimeMillis() - startTime,
                        tagsResult.latencyMs(),
                        0,
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
            
            // Determine model
            ModelChoice model = tagsResult.isComplex() ? ModelChoice.SMART : ModelChoice.FAST;
            
            long totalLatency = System.currentTimeMillis() - startTime;
            
            logger.info("Parallel complete: classifier={}ms, matcher={}ms, total={}ms", 
                    tagsResult.latencyMs(), matchResult.latencyMs(), totalLatency);
            logger.info("Result: responseType={}, tags={}, model={}", 
                    matchResult.responseType(), tagsResult.tags(), model);
            
            return new OrchestrationResult(
                    message,
                    matchResult.responseType(),
                    tagsResult.tags(),
                    model,
                    tagsResult.rawJson() + " | responseType=" + matchResult.responseType(),
                    totalLatency,
                    tagsResult.latencyMs(),
                    matchResult.latencyMs(),
                    tagsResult.tokensUsed()
            );
            
        } catch (Exception e) {
            logger.error("Orchestration failed: {}", e.getMessage(), e);
            return new OrchestrationResult(
                    message,
                    ResponseType.NO,
                    Set.of(Tag.FINANCIAL),
                    ModelChoice.SMART,  // Safe fallback
                    "{\"error\": \"" + e.getMessage() + "\"}",
                    System.currentTimeMillis() - startTime,
                    0,
                    0,
                    0
            );
        }
    }
    
    /**
     * Process with previous message but no pending flag (defaults to false).
     */
    public OrchestrationResult process(String message, String previousBotMessage) {
        return process(message, previousBotMessage, false);
    }
    
    /**
     * Process without previous context.
     */
    public OrchestrationResult process(String message) {
        return process(message, null, false);
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
