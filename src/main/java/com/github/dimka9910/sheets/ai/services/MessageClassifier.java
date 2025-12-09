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
 * Message Classifier - determines context for Main Agent.
 * 
 * Parallel execution:
 * - gpt-4o-mini → tags (fast, cheap)
 * - gpt-4o → responseType (smart, dialog understanding)
 */
public class MessageClassifier {
    
    private static final Logger logger = LoggerFactory.getLogger(MessageClassifier.class);
    
    // ==================== CONSTANTS ====================
    
    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String MODEL_FAST = "gpt-4o-mini";
    private static final String MODEL_SMART = "gpt-4o";
    private static final int MAX_TOKENS_TAGS = 500;
    private static final int MAX_TOKENS_RESPONSE_TYPE = 50;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    
    // ==================== FIELDS ====================
    
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    
    // ==================== TYPES ====================
    
    public enum Tag {
        FINANCIAL,    // Money transaction
        SETTINGS,     // Configuration
        QUESTION,     // Help/capabilities
        OFF_TOPIC,    // Unrelated
        TRANSFER,     // Between own accounts
        THIRD_PARTY,  // Involves another person
        COMPLEX       // Needs smart model
    }
    
    public enum ResponseType {
        YES,          // Response to last bot message
        NO,           // New topic
        NEED_HISTORY  // Response to earlier message
    }
    
    public record ClassificationResult(
            ResponseType responseType,
            Set<Tag> tags,
            String rawJson,
            long latencyMs,
            int tokensUsed
    ) {}
    
    private record TagsResult(Set<Tag> tags, String rawJson, long latencyMs, int tokens) {}
    
    // ==================== CONSTRUCTORS ====================
    
    public MessageClassifier() {
        this.apiKey = System.getProperty("OPENAI_API_KEY", System.getenv("OPENAI_API_KEY"));
        this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        this.objectMapper = new ObjectMapper();
        this.executor = Executors.newFixedThreadPool(2);
    }
    
    public MessageClassifier(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        this.objectMapper = new ObjectMapper();
        this.executor = Executors.newFixedThreadPool(2);
    }
    
    // ==================== PUBLIC API ====================
    
    /**
     * Classify message. If previousBotMessage is null, responseType = NO.
     * Otherwise runs parallel classification for tags and responseType.
     */
    public ClassificationResult classify(String message, String previousBotMessage) {
        long startTime = System.currentTimeMillis();
        
        try {
            if (previousBotMessage == null || previousBotMessage.isBlank()) {
                TagsResult tagsResult = classifyTags(message, null);
                return new ClassificationResult(
                        ResponseType.NO,
                        tagsResult.tags,
                        tagsResult.rawJson,
                        System.currentTimeMillis() - startTime,
                        tagsResult.tokens
                );
            }
            
            // Parallel: tags + responseType
            CompletableFuture<TagsResult> tagsFuture = CompletableFuture.supplyAsync(
                    () -> classifyTags(message, previousBotMessage), executor);
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
    
    // ==================== CLASSIFICATION ====================
    
    private TagsResult classifyTags(String message, String previousBotMessage) {
        long start = System.currentTimeMillis();
        try {
            String prompt = buildTagsPrompt(message, previousBotMessage);
            JsonNode response = callOpenAI(MODEL_FAST, MAX_TOKENS_TAGS, prompt);
            
            int tokens = response.path("usage").path("total_tokens").asInt(0);
            String content = response.path("choices").get(0).path("message").path("content").asText();
            String json = extractJson(content);
            
            Set<Tag> tags = parseTags(objectMapper.readTree(json));
            return new TagsResult(tags, json, System.currentTimeMillis() - start, tokens);
            
        } catch (Exception e) {
            logger.error("Tags classification error: {}", e.getMessage());
            return new TagsResult(Set.of(Tag.FINANCIAL), "{\"error\":\"" + e.getMessage() + "\"}", 
                    System.currentTimeMillis() - start, 0);
        }
    }
    
    private ResponseType classifyResponseType(String message, String previousBotMessage) {
        try {
            String prompt = buildResponseTypePrompt(message, previousBotMessage);
            JsonNode response = callOpenAI(MODEL_SMART, MAX_TOKENS_RESPONSE_TYPE, prompt);
            
            String content = response.path("choices").get(0).path("message").path("content").asText().trim();
            String json = extractJson(content);
            String answer = objectMapper.readTree(json).path("answer").asText("NO").toUpperCase();
            
            ResponseType result = switch (answer) {
                case "YES" -> ResponseType.YES;
                case "NEED_HISTORY" -> ResponseType.NEED_HISTORY;
                default -> ResponseType.NO;
            };
            
            logger.debug("gpt-4o responseType: {} → {}", json, result);
            return result;
            
        } catch (Exception e) {
            logger.error("ResponseType classification error: {}", e.getMessage());
            return previousBotMessage != null && previousBotMessage.contains("?") 
                    ? ResponseType.YES : ResponseType.NO;
        }
    }
    
    // ==================== PROMPTS ====================
    
    private String buildTagsPrompt(String message, String previousBotMessage) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("""
            You are a context classifier for a personal finance bot.
            Users write in ANY language (Russian, English, Serbian, mixed, etc).
            
            ## Task
            Classify what context to load. DO NOT process the message.
            
            ## Tags
            
            **FINANCIAL** - money transaction, expense, income
            - Single product/service name = FINANCIAL (user means "I bought X")
            
            **SETTINGS** - configuration, defaults, remember something
            
            **QUESTION** - help, capabilities, "what can you do?"
            
            **OFF_TOPIC** - unrelated to finance
            
            **TRANSFER** - between user's OWN accounts (add with FINANCIAL)
            
            **THIRD_PARTY** - involves another person (add with FINANCIAL)
            
            **COMPLEX** - multiple operations, math, conditions, corrections, ambiguous
            
            ## Rules
            - Tags can be multiple: ["FINANCIAL", "THIRD_PARTY"]
            - When in doubt → add COMPLEX
            
            ## Response (JSON only)
            {"tags": ["FINANCIAL"]}
            
            """);
        
        if (previousBotMessage != null && !previousBotMessage.isBlank()) {
            sb.append("## Previous Bot Message\n```\n").append(previousBotMessage).append("\n```\n\n");
        }
        
        sb.append("## User Message\n```\n").append(message).append("\n```\n\nJSON:");
        return sb.toString();
    }
    
    private String buildResponseTypePrompt(String message, String previousBotMessage) {
        return """
            Is user's message a RESPONSE to bot's LAST message?
            
            - YES: responds to THIS message
            - NO: new topic
            - NEED_HISTORY: responds to EARLIER message (not this one)
            
            NEED_HISTORY when:
            - Corrects something not in THIS message
            - References "as I said", "you already recorded", "that one"
            
            Bot: %s
            User: %s
            
            JSON: {"answer": "YES"} or {"answer": "NO"} or {"answer": "NEED_HISTORY"}
            """.formatted(previousBotMessage, message);
    }
    
    // ==================== UTILS ====================
    
    private Set<Tag> parseTags(JsonNode root) {
        Set<Tag> tags = new HashSet<>();
        JsonNode tagsNode = root.path("tags");
        if (tagsNode.isArray()) {
            for (JsonNode tagNode : tagsNode) {
                try {
                    tags.add(Tag.valueOf(tagNode.asText().toUpperCase().trim()));
                } catch (IllegalArgumentException e) {
                    logger.warn("Unknown tag: {}", tagNode.asText());
                }
            }
        }
        return tags.isEmpty() ? Set.of(Tag.FINANCIAL) : tags;
    }
    
    private String extractJson(String content) {
        int start = content.indexOf("{");
        int end = content.lastIndexOf("}");
        return (start >= 0 && end > start) ? content.substring(start, end + 1) : content;
    }
    
    private JsonNode callOpenAI(String model, int maxTokens, String prompt) throws Exception {
        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", maxTokens,
                "temperature", 0.1,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OPENAI_API_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new RuntimeException("OpenAI error: " + response.statusCode() + " - " + response.body());
        }
        
        return objectMapper.readTree(response.body());
    }
}
