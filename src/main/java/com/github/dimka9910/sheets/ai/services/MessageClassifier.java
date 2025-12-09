package com.github.dimka9910.sheets.ai.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Message Classifier - determines what context to load for Main Agent.
 * 
 * NO SPLITTING! Just tags for context loading.
 * Main Agent (gpt-5-mini) handles complex messages itself.
 * 
 * Output:
 * - isResponse: should we load previous conversation context?
 * - tags: which context sections to load (can be multiple)
 * - confidence: how sure is the classifier
 */
public class MessageClassifier {
    private static final Logger logger = LoggerFactory.getLogger(MessageClassifier.class);
    
    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String MODEL = "gpt-4o-mini";
    private static final int MAX_TOKENS = 500;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    // Context tags - what sections to load for Main Agent
    public enum Tag {
        // Primary categories
        FINANCIAL,      // Any money transaction (expense, income)
        SETTINGS,       // Configuration changes
        QUESTION,       // Questions about system
        OFF_TOPIC,      // Unrelated to finance
        
        // Financial sub-tags (can combine with FINANCIAL)
        TRANSFER,           // Transfer between OWN accounts
        THIRD_PARTY,        // Involves another person (linked user, external)
        
        // Complexity indicator
        COMPLEX             // Multiple operations, math, conditions, corrections
                            // → route to smarter model (gpt-5-mini)
    }
    
    public enum Confidence { HIGH, MEDIUM, LOW }
    
    // Classification result
    public record ClassificationResult(
            String originalMessage,
            boolean isResponse,         // Load previous conversation context?
            Set<Tag> tags,              // Which context sections to load
            Confidence confidence,
            String rawJson,             // Raw JSON from model (for debug)
            long latencyMs,
            int tokensUsed
    ) {
        public boolean needsPreviousContext() {
            return isResponse;
        }
        
        public boolean hasTag(Tag tag) {
            return tags.contains(tag);
        }
        
        public boolean isFinancial() {
            return tags.contains(Tag.FINANCIAL);
        }
        
        public boolean isSettings() {
            return tags.contains(Tag.SETTINGS);
        }
        
        public boolean isTransfer() {
            return tags.contains(Tag.TRANSFER);
        }
        
        public boolean involvesThirdParty() {
            return tags.contains(Tag.THIRD_PARTY);
        }
        
        public boolean isComplex() {
            return tags.contains(Tag.COMPLEX);
        }
        
        /**
         * Should this request go to gpt-5-mini (smart) instead of gpt-4o-mini (fast)?
         */
        public boolean needsSmartModel() {
            return isComplex() || confidence == Confidence.LOW;
        }
    }
    
    public MessageClassifier() {
        this.apiKey = System.getProperty("OPENAI_API_KEY", System.getenv("OPENAI_API_KEY"));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
    }
    
    public MessageClassifier(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * Classify message with previous bot message context.
     */
    public ClassificationResult classify(String message, String previousBotMessage) {
        long startTime = System.currentTimeMillis();
        
        try {
            String prompt = buildPrompt(message, previousBotMessage);
            JsonNode response = callOpenAI(prompt);
            
            int tokens = response.path("usage").path("total_tokens").asInt(0);
            String content = response.path("choices").get(0).path("message").path("content").asText();
            
            long latency = System.currentTimeMillis() - startTime;
            return parseResponse(content, message, latency, tokens);
            
        } catch (Exception e) {
            logger.error("Classification failed: {}", e.getMessage(), e);
            long latency = System.currentTimeMillis() - startTime;
            // Fallback: assume FINANCIAL with LOW confidence
            return new ClassificationResult(
                    message,
                    false,
                    Set.of(Tag.FINANCIAL),
                    Confidence.LOW,
                    "{\"error\": \"" + e.getMessage() + "\"}",
                    latency,
                    0
            );
        }
    }
    
    /**
     * Classify without previous context (new conversation).
     */
    public ClassificationResult classify(String message) {
        return classify(message, null);
    }
    
    private String buildPrompt(String message, String previousBotMessage) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("""
            You are a context classifier for a personal finance bot.
            Users write in ANY language (Russian, English, Serbian, mixed, etc).
            
            ## Your Task
            Determine what context the main AI agent needs to process this message.
            DO NOT process the message itself - just classify what context to load.
            
            ## Output Two Things
            
            ### 1. isResponse (true/false)
            Is this message semantically connected to the previous bot message?
            
            TRUE when:
            - Message answers a question the bot asked
            - Message confirms, denies, or modifies something bot proposed
            - Message provides information that bot requested
            - Message would be unclear without knowing what bot said before
            - Short message (1-3 words) right after bot asked something
            
            FALSE when:
            - Message is a new standalone request
            - Message makes complete sense without previous context
            - Message starts a new topic
            
            ### 2. tags (array - can have multiple)
            
            **Primary categories:**
            
            **FINANCIAL** - message involves money transactions or purchase record
            - Recording expense, income, or transfer
            - Mentions amount, currency, account, purchase
            - IMPORTANT: Single word that is a product/service name (coffee, taxi, lunch, etc.) = FINANCIAL
            - User might just say product name meaning "I bought X" - this is FINANCIAL, not OFF_TOPIC!
            - if it looks like a product/service name, but it seems like user forgot to provide amount, it's FINANCIAL, not OFF_TOPIC!
            
            **SETTINGS** - message involves configuration
            - Setting defaults (account, currency, fund)
            - Adding/changing custom instructions or aliases
            - Request to remember something, adjust behavior, teaching you something, noting something, "btw", "by the way", "just so you know", etc.
            
            **QUESTION** - message asks about the system or its capabilities
            - How to use the bot
            - What can you do? What are your capabilities? How to do this and that?
            - Help requests
            - Questions about user's data: accounts, funds, settings
            
            **OFF_TOPIC** - message completely unrelated to finance or the bot
            - Jokes, weather, general knowledge questions
            - Requests that a finance bot cannot fulfill
            - NOTE: "What can you do?" is QUESTION, not OFF_TOPIC!
            
            **Financial sub-tags (add together with FINANCIAL):**
            
            **TRANSFER** - moving money between user's OWN accounts
            - Message describes moving money from one personal account to another
            - Both source and destination belong to the same user
            - Key words: transfer, move, from X to Y (where X and Y are accounts)
            
            **THIRD_PARTY** - involves another person
            - Message mentions someone else by name or relationship
            - Paying FOR someone, receiving FROM someone, splitting with someone
            - Any person reference: names, nicknames, "him", "here", "friend", "mom", "girlfriend", etc.
            
            **Complexity indicator:**
            
            **COMPLEX** - needs smarter model (gpt-5-mini)
            Add this tag when:
            - Multiple financial operations in one message
            - Mathematical expressions or calculations needed
            - Conditional logic ("if... then...")
            - Corrections referencing previous transactions
            - Ambiguous or hard to parse request
            - Slang, abbreviations, incomplete sentences
            
            ## Rules
            - Tags can be MULTIPLE: ["FINANCIAL", "TRANSFER"] or ["FINANCIAL", "THIRD_PARTY", "COMPLEX"]
            - When in doubt about complexity → add COMPLEX (better safe)
            - Simple single expense → just ["FINANCIAL"]
            - Transfer to another person + expense → ["FINANCIAL", "THIRD_PARTY", "COMPLEX"]
            
            ## Response Format (JSON only, no explanation)
            
            ```json
            {
              "isResponse": false,
              "tags": ["FINANCIAL"],
              "confidence": "HIGH"
            }
            ```
            
            """);
        
        // Add context if available
        if (previousBotMessage != null && !previousBotMessage.isBlank()) {
            sb.append("\n## Previous Bot Message\n");
            sb.append("```\n").append(previousBotMessage).append("\n```\n\n");
        } else {
            sb.append("\n## Previous Bot Message\nNone (new conversation)\n\n");
        }
        
        sb.append("## User Message\n```\n").append(message).append("\n```\n\n");
        sb.append("JSON response:");
        
        return sb.toString();
    }
    
    private ClassificationResult parseResponse(String content, String originalMessage, long latency, int tokens) {
        String json = extractJson(content);
        
        try {
            JsonNode root = objectMapper.readTree(json);
            
            boolean isResponse = root.path("isResponse").asBoolean(false);
            String confidenceStr = root.path("confidence").asText("MEDIUM");
            Confidence confidence = parseConfidence(confidenceStr);
            
            Set<Tag> tags = new HashSet<>();
            JsonNode tagsNode = root.path("tags");
            
            if (tagsNode.isArray()) {
                for (JsonNode tagNode : tagsNode) {
                    Tag tag = parseTag(tagNode.asText());
                    if (tag != null) {
                        tags.add(tag);
                    }
                }
            }
            
            // If no valid tags, default to FINANCIAL
            if (tags.isEmpty()) {
                tags.add(Tag.FINANCIAL);
                confidence = Confidence.LOW;
            }
            
            return new ClassificationResult(originalMessage, isResponse, tags, confidence, json, latency, tokens);
            
        } catch (Exception e) {
            logger.error("Failed to parse response: {}", content, e);
            return new ClassificationResult(
                    originalMessage,
                    false,
                    Set.of(Tag.FINANCIAL),
                    Confidence.LOW,
                    json,
                    latency,
                    tokens
            );
        }
    }
    
    private String extractJson(String content) {
        int start = content.indexOf("{");
        int end = content.lastIndexOf("}");
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return content;
    }
    
    private Tag parseTag(String tagStr) {
        try {
            return Tag.valueOf(tagStr.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            logger.warn("Unknown tag '{}', ignoring", tagStr);
            return null;
        }
    }
    
    private Confidence parseConfidence(String str) {
        try {
            return Confidence.valueOf(str.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            return Confidence.MEDIUM;
        }
    }
    
    private JsonNode callOpenAI(String prompt) throws Exception {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", MODEL);
        requestBody.put("max_tokens", MAX_TOKENS);
        requestBody.put("temperature", 0.1);
        requestBody.put("messages", List.of(
                Map.of("role", "user", "content", prompt)
        ));
        
        String jsonBody = objectMapper.writeValueAsString(requestBody);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OPENAI_API_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new RuntimeException("OpenAI API error: " + response.statusCode() + " - " + response.body());
        }
        
        return objectMapper.readTree(response.body());
    }
}
