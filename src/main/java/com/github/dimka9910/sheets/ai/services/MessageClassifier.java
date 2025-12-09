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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Message Classifier - determines what context to load for Main Agent.
 * 
 * NO SPLITTING! Just tags for context loading.
 * Main Agent (gpt-5-mini) handles complex messages itself.
 * 
 * Output:
 * - responseType: YES/NO/NEED_HISTORY
 * - tags: which context sections to load (can be multiple)
 */
public class MessageClassifier {
    private static final Logger logger = LoggerFactory.getLogger(MessageClassifier.class);
    
    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    
    // Fast model for tags classification
    private static final String MODEL_FAST = "gpt-4o-mini";
    // Smart model for isResponse (parallel) - better at understanding dialog context
    private static final String MODEL_SMART = "gpt-4o";
    
    private static final int MAX_TOKENS_TAGS = 500;
    private static final int MAX_TOKENS_IS_RESPONSE = 50;  // Very simple task
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    
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
    
    // Response type from gpt-4o
    public enum ResponseType {
        YES,           // Definitely a response to previous bot message
        NO,            // New topic, not related to bot's message
        NEED_HISTORY   // Looks like response but need more conversation history to understand context
    }
    
    // Classification result
    public record ClassificationResult(
            ResponseType responseType,  // YES/NO/NEED_HISTORY
            Set<Tag> tags,              // Which context sections to load
            String rawJson,             // Raw JSON from model (for debug)
            long latencyMs,
            int tokensUsed
    ) {}
    
    public MessageClassifier() {
        this.apiKey = System.getProperty("OPENAI_API_KEY", System.getenv("OPENAI_API_KEY"));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
        this.executor = Executors.newFixedThreadPool(2); // For parallel calls
    }
    
    public MessageClassifier(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
        this.executor = Executors.newFixedThreadPool(2);
    }
    
    /**
     * Classify message with previous bot message context.
     * 
     * PARALLEL EXECUTION:
     * - gpt-4o-mini → tags classification (fast, cheap)
     * - gpt-4o → isResponse detection (smart, better at dialog understanding)
     */
    public ClassificationResult classify(String message, String previousBotMessage) {
        long startTime = System.currentTimeMillis();
        
        try {
            // No previous message → just classify tags, responseType = NO
            if (previousBotMessage == null || previousBotMessage.isBlank()) {
                TagsResult tagsResult = classifyTags(message);
                long latency = System.currentTimeMillis() - startTime;
                return new ClassificationResult(
                        ResponseType.NO,
                        tagsResult.tags,
                        tagsResult.rawJson,
                        latency,
                        tagsResult.tokens
                );
            }
            
            // PARALLEL: tags (gpt-4o-mini) + responseType (gpt-4o)
            CompletableFuture<TagsResult> tagsFuture = CompletableFuture.supplyAsync(
                    () -> classifyTags(message), executor);
            
            CompletableFuture<ResponseType> responseFuture = CompletableFuture.supplyAsync(
                    () -> classifyResponseType(message, previousBotMessage), executor);
            
            TagsResult tagsResult = tagsFuture.join();
            ResponseType responseType = responseFuture.join();
            
            long latency = System.currentTimeMillis() - startTime;
            
            logger.info("Parallel: tags={}ms, responseType={}, total={}ms", 
                    tagsResult.latencyMs, responseType, latency);
            
            return new ClassificationResult(
                    responseType,
                    tagsResult.tags,
                    tagsResult.rawJson + " | responseType=" + responseType,
                    latency,
                    tagsResult.tokens
            );
            
        } catch (Exception e) {
            logger.error("Classification failed: {}", e.getMessage(), e);
            return new ClassificationResult(
                    ResponseType.NO,
                    Set.of(Tag.FINANCIAL),
                    "{\"error\": \"" + e.getMessage() + "\"}",
                    System.currentTimeMillis() - startTime,
                    0
            );
        }
    }
    
    // Internal result for tags
    private record TagsResult(Set<Tag> tags, String rawJson, long latencyMs, int tokens) {}
    
    /**
     * Classify tags using gpt-4o-mini.
     */
    private TagsResult classifyTags(String message) {
        long start = System.currentTimeMillis();
        try {
            String prompt = buildTagsPrompt(message, null);
            JsonNode response = callOpenAI(MODEL_FAST, MAX_TOKENS_TAGS, prompt);
            
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
            if (tags.isEmpty()) {
                tags.add(Tag.FINANCIAL);
            }
            
            return new TagsResult(tags, json, System.currentTimeMillis() - start, tokens);
            
        } catch (Exception e) {
            logger.error("Tags classification error: {}", e.getMessage());
            return new TagsResult(Set.of(Tag.FINANCIAL), "{\"error\":\"" + e.getMessage() + "\"}", 
                    System.currentTimeMillis() - start, 0);
        }
    }
    
    /**
     * Classify isResponse using gpt-4o (smart).
     * Simple task = small prompt = cheap even with gpt-4o.
     * Returns: YES / NO / NEED_HISTORY
     */
    private ResponseType classifyResponseType(String message, String previousBotMessage) {
        try {
            String prompt = buildIsResponsePrompt(message, previousBotMessage);
            JsonNode response = callOpenAI(MODEL_SMART, MAX_TOKENS_IS_RESPONSE, prompt);
            
            String content = response.path("choices").get(0).path("message").path("content").asText().trim();
            String json = extractJson(content);
            JsonNode root = objectMapper.readTree(json);
            
            String answer = root.path("answer").asText("NO").toUpperCase();
            
            ResponseType result = switch (answer) {
                case "YES" -> ResponseType.YES;
                case "NEED_HISTORY" -> ResponseType.NEED_HISTORY;
                default -> ResponseType.NO;
            };
            
            logger.debug("gpt-4o isResponse: {} → {}", json, result);
            return result;
            
        } catch (Exception e) {
            logger.error("isResponse classification error: {}", e.getMessage());
            // Fallback: if bot asked question, assume it's a response
            return previousBotMessage != null && previousBotMessage.contains("?") 
                    ? ResponseType.YES 
                    : ResponseType.NO;
        }
    }
    
    /**
     * Simple prompt for isResponse (gpt-4o).
     * Returns JSON with strict format.
     */
    private String buildIsResponsePrompt(String message, String previousBotMessage) {
        return """
            Is the user's message a RESPONSE to the bot's LAST message?
            
            Possible answers:
            - YES: User responds to THIS bot message (answering, confirming, correcting it)
            - NO: New topic, not related to bot's message
            - NEED_HISTORY: Looks like response but to an EARLIER message (not this one)
            
            When NEED_HISTORY:
            - User corrects something not mentioned in THIS bot message
            - User references earlier actions: "as I said", "you already recorded", "remember what you did"
            - User asks to recall something clearly not in THIS message
            - User says "that one", "the previous one", "like before" about something not shown here
            
            Bot: %s
            User: %s
            
            JSON response (no explanation):
            {"answer": "YES"} or {"answer": "NO"} or {"answer": "NEED_HISTORY"}
            """.formatted(previousBotMessage, message);
    }
    
    /**
     * Classify without previous context (new conversation).
     */
    public ClassificationResult classify(String message) {
        return classify(message, null);
    }
    
    private String buildTagsPrompt(String message, String previousBotMessage) {
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
    
    private JsonNode callOpenAI(String model, int maxTokens, String prompt) throws Exception {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("max_tokens", maxTokens);
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
