package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.services.llm.MessageClassifier;
import com.github.dimka9910.sheets.ai.services.llm.MessageClassifier.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Orchestrator - thin wrapper around MessageClassifier.
 * Just adds model routing logic based on tags.
 */
public class Orchestrator {
    
    private static final Logger logger = LoggerFactory.getLogger(Orchestrator.class);
    
    private final MessageClassifier classifier;
    
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
        String rawJson,         // Raw JSON from classifier
        long latencyMs,
        int tokensUsed
    ) {
        public boolean needsPreviousContext() {
            return responseType != ResponseType.NO;
        }
        
        public boolean needsLinkedUsers() {
            return tags.contains(Tag.THIRD_PARTY);
        }
        
        public boolean isComplex() {
            return tags.contains(Tag.COMPLEX);
        }
    }
    
    public Orchestrator() {
        this.classifier = new MessageClassifier();
    }
    
    public Orchestrator(MessageClassifier classifier) {
        this.classifier = classifier;
    }
    
    /**
     * Process user message and determine routing.
     */
    public OrchestrationResult process(String message, String previousBotMessage) {
        logger.info("=== ORCHESTRATOR ===");
        logger.info("Input: \"{}\"", truncate(message, 60));
        if (previousBotMessage != null) {
            logger.info("Prev: \"{}\"", truncate(previousBotMessage, 40));
        }
        
        // Classify
        ClassificationResult result = classifier.classify(message, previousBotMessage);
        
        // Choose model: COMPLEX tag → SMART model
        ModelChoice model = result.tags().contains(Tag.COMPLEX) ? ModelChoice.SMART : ModelChoice.FAST;
        
        logger.info("Result: responseType={}, tags={}, model={}", 
                result.responseType(), result.tags(), model);
        logger.info("({}ms, {} tokens)", result.latencyMs(), result.tokensUsed());
        
        return new OrchestrationResult(
            message,
            result.responseType(),
            result.tags(),
            model,
            result.rawJson(),
            result.latencyMs(),
            result.tokensUsed()
        );
    }
    
    /**
     * Process without previous context.
     */
    public OrchestrationResult process(String message) {
        return process(message, null);
    }
    
    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
