package com.github.dimka9910.sheets.ai.services.llm;

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
 * MessageClassifierAgent - determines what context to load for Main Agent.
 * 
 * Uses gpt-4o-mini (fast, cheap) to classify tags.
 * 
 * Tags determine:
 * - What prompt sections to include
 * - What user context to load
 * - Whether to use fast or smart model
 */
public class MessageClassifierAgent {
    private static final Logger logger = LoggerFactory.getLogger(MessageClassifierAgent.class);
    
    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String MODEL = "gpt-4o-mini";
    private static final int MAX_TOKENS = 500;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TYPES
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Context tags - what sections to load for Main Agent.
     */
    public enum Tag {
        // Primary categories
        FINANCIAL,      // Any money transaction (expense, income)
        SETTINGS,       // Configuration changes
        QUESTION,       // Questions about system
        OFF_TOPIC,      // Unrelated to finance
        
        // Financial sub-tags (can combine with FINANCIAL)
        TRANSFER,       // Transfer between OWN accounts
        THIRD_PARTY,    // Involves another person (linked user, external)
        
        // Complexity indicator
        COMPLEX         // Multiple operations, math, conditions, corrections
                        // → route to smarter model (gpt-5-mini)
    }
    
    /**
     * Result of tag classification.
     */
    public record TagsResult(
            Set<Tag> tags,
            String rawJson,
            long latencyMs,
            int tokensUsed
    ) {
        public boolean isComplex() {
            return tags.contains(Tag.COMPLEX);
        }
        
        public boolean needsLinkedUsers() {
            return tags.contains(Tag.THIRD_PARTY) || tags.contains(Tag.TRANSFER);
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONSTRUCTORS
    // ═══════════════════════════════════════════════════════════════════════════
    
    public MessageClassifierAgent() {
        this.apiKey = System.getProperty("OPENAI_API_KEY", System.getenv("OPENAI_API_KEY"));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
    }
    
    public MessageClassifierAgent(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Classify message tags.
     * 
     * @param message User's message
     * @param previousBotMessage Previous bot message (for context, can be null)
     * @return TagsResult with classified tags
     */
    public TagsResult classify(String message, String previousBotMessage) {
        long start = System.currentTimeMillis();
        
        try {
            String prompt = buildPrompt(message, previousBotMessage);
            JsonNode response = callOpenAI(prompt);
            
            int tokens = response.path("usage").path("total_tokens").asInt(0);
            String content = response.path("choices").get(0).path("message").path("content").asText();
            String json = extractJson(content);
            
            JsonNode root = objectMapper.readTree(json);
            
            Set<Tag> tags = new HashSet<>();
            JsonNode tagsNode = root.path("tags");
            if (tagsNode.isArray()) {
                for (JsonNode tagNode : tagsNode) {
                    Tag tag = parseTag(tagNode.asText());
                    if (tag != null) tags.add(tag);
                }
            }
            
            // Default to FINANCIAL if no tags
            if (tags.isEmpty()) {
                tags.add(Tag.FINANCIAL);
            }
            
            long latency = System.currentTimeMillis() - start;
            logger.info("Tags classified: {} ({}ms, {} tokens)", tags, latency, tokens);
            
            return new TagsResult(tags, json, latency, tokens);
            
        } catch (Exception e) {
            logger.error("Tags classification error: {}", e.getMessage());
            return new TagsResult(
                    Set.of(Tag.FINANCIAL), 
                    "{\"error\":\"" + e.getMessage() + "\"}", 
                    System.currentTimeMillis() - start, 
                    0
            );
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PROMPT
    // ═══════════════════════════════════════════════════════════════════════════
    
    private String buildPrompt(String message, String previousBotMessage) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("""
            You are a context classifier for a personal finance bot.
            Users write in ANY language (Russian, English, Serbian, mixed, etc).
            
            ## Your Task
            Determine what context the main AI agent needs to process this message.
            DO NOT process the message itself - just classify what context to load.
            
            ## Output: tags (array - can have multiple)
            
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
              "tags": ["FINANCIAL"]
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
    
    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITIES
    // ═══════════════════════════════════════════════════════════════════════════
    
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

