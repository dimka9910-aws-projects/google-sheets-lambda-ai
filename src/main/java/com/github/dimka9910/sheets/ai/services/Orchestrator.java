package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.services.MessageClassifier.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Set;

/**
 * Orchestrator - determines what context to load based on classification.
 * 
 * Flow:
 * 1. User message → MessageClassifier
 * 2. Get tags → determine which context sections to load
 * 3. Pass to Main Agent with appropriate context (not implemented yet)
 */
public class Orchestrator {
    
    private static final Logger logger = LoggerFactory.getLogger(Orchestrator.class);
    
    private final MessageClassifier classifier;
    
    /**
     * Result of orchestration - what context to load and which model to use.
     */
    public record OrchestrationResult(
        String originalMessage,
        boolean isResponse,             // Load previous conversation?
        Set<Tag> tags,                  // Classification tags
        Set<ContextSection> sections,   // Context sections to load
        ModelChoice model,              // Which model to use
        Confidence confidence,
        long latencyMs,
        int tokensUsed
    ) {
        public boolean needsPreviousContext() {
            return isResponse;
        }
        
        public boolean isHighConfidence() {
            return confidence == Confidence.HIGH || confidence == Confidence.MEDIUM;
        }
        
        public boolean needsLinkedUsersContext() {
            return tags.contains(Tag.THIRD_PARTY);
        }
        
        public boolean isTransfer() {
            return tags.contains(Tag.TRANSFER);
        }
    }
    
    /**
     * Which model to route the request to.
     */
    public enum ModelChoice {
        FAST,   // gpt-4o-mini - simple requests
        SMART   // gpt-5-mini - complex, corrections, math, ambiguous
    }
    
    /**
     * Actual context sections to load for Main Agent.
     */
    public enum ContextSection {
        PREVIOUS_CONVERSATION,  // pendingCommands, last bot message, conversation history
        ACCOUNTS_AND_FUNDS,     // user's accounts, funds
        LINKED_USERS,           // linked users' accounts, funds, defaults (for THIRD_PARTY)
        DEFAULTS,               // default account, currency, fund
        CUSTOM_INSTRUCTIONS,    // user's custom rules and aliases
        HELP_INFO,              // how to use the bot
        MINIMAL                 // just user name, basic info
    }
    
    public Orchestrator() {
        this.classifier = new MessageClassifier();
    }
    
    public Orchestrator(MessageClassifier classifier) {
        this.classifier = classifier;
    }
    
    /**
     * Process user message and determine what context to load and which model to use.
     */
    public OrchestrationResult process(String message, String previousBotMessage) {
        logger.info("=== ORCHESTRATOR START ===");
        logger.info("Input: \"{}\"", truncate(message, 60));
        if (previousBotMessage != null) {
            logger.info("Previous bot: \"{}\"", truncate(previousBotMessage, 40));
        }
        
        // Classify
        ClassificationResult result = classifier.classify(message, previousBotMessage);
        
        // Convert tags to context sections
        Set<ContextSection> sections = tagsToSections(result.isResponse(), result.tags());
        
        // Choose model based on complexity
        ModelChoice model = result.needsSmartModel() ? ModelChoice.SMART : ModelChoice.FAST;
        
        logger.info("Result: isResponse={}, tags={}, model={}, confidence={}", 
                result.isResponse(), 
                result.tags(),
                model,
                result.confidence());
        logger.info("Sections: {}", sections);
        logger.info("=== ORCHESTRATOR DONE === ({}ms, {} tokens)", 
                result.latencyMs(), result.tokensUsed());
        
        return new OrchestrationResult(
            message,
            result.isResponse(),
            result.tags(),
            sections,
            model,
            result.confidence(),
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
    
    /**
     * Convert tags to actual context sections to load.
     */
    private Set<ContextSection> tagsToSections(boolean isResponse, Set<Tag> tags) {
        Set<ContextSection> sections = EnumSet.noneOf(ContextSection.class);
        
        // Always load previous if it's a response
        if (isResponse) {
            sections.add(ContextSection.PREVIOUS_CONVERSATION);
        }
        
        for (Tag tag : tags) {
            switch (tag) {
                case FINANCIAL, TRANSFER -> {
                    sections.add(ContextSection.ACCOUNTS_AND_FUNDS);
                    sections.add(ContextSection.DEFAULTS);
                    sections.add(ContextSection.CUSTOM_INSTRUCTIONS);
                }
                case THIRD_PARTY -> {
                    sections.add(ContextSection.ACCOUNTS_AND_FUNDS);
                    sections.add(ContextSection.LINKED_USERS); // Need linked user context!
                    sections.add(ContextSection.CUSTOM_INSTRUCTIONS);
                }
                case SETTINGS -> {
                    sections.add(ContextSection.DEFAULTS);
                    sections.add(ContextSection.CUSTOM_INSTRUCTIONS);
                }
                case QUESTION -> {
                    sections.add(ContextSection.HELP_INFO);
                    sections.add(ContextSection.ACCOUNTS_AND_FUNDS);
                }
                case OFF_TOPIC -> {
                    sections.add(ContextSection.MINIMAL);
                }
                case COMPLEX -> {
                    // COMPLEX doesn't add sections, just affects model choice
                }
            }
        }
        
        // If nothing added, at least add minimal
        if (sections.isEmpty()) {
            sections.add(ContextSection.MINIMAL);
        }
        
        return sections;
    }
    
    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
