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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ResponseMatcherAgent - determines if user's message is a response to bot's message.
 * 
 * Uses gpt-4o (smart model) - better at understanding dialog context.
 * Small prompt = cheap even with gpt-4o.
 * 
 * Output:
 * - YES: Definitely a response to previous bot message
 * - NO: New topic, not related to bot's message
 * - NEED_HISTORY: Looks like response but need more conversation history
 */
public class ResponseMatcherAgent {
    private static final Logger logger = LoggerFactory.getLogger(ResponseMatcherAgent.class);
    
    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String MODEL = "gpt-4o";  // Smart model for dialog understanding
    private static final int MAX_TOKENS = 50;      // Very simple task
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TYPES
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Response type - is user's message a response to bot's message?
     */
    public enum ResponseType {
        YES,           // Definitely a response to previous bot message
        NO,            // New topic, not related to bot's message
        NEED_HISTORY   // Looks like response but need more conversation history to understand context
    }
    
    /**
     * Result of response matching.
     */
    public record MatchResult(
            ResponseType responseType,
            long latencyMs
    ) {
        public boolean isResponse() {
            return responseType == ResponseType.YES;
        }
        
        public boolean needsHistory() {
            return responseType == ResponseType.NEED_HISTORY;
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONSTRUCTORS
    // ═══════════════════════════════════════════════════════════════════════════
    
    public ResponseMatcherAgent() {
        this.apiKey = System.getProperty("OPENAI_API_KEY", System.getenv("OPENAI_API_KEY"));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
    }
    
    public ResponseMatcherAgent(String apiKey) {
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
     * Determine if user's message is a response to bot's message.
     * 
     * @param message User's message
     * @param previousBotMessage Previous bot message (required)
     * @return MatchResult with response type
     */
    public MatchResult match(String message, String previousBotMessage) {
        long start = System.currentTimeMillis();
        
        // No previous message → definitely NO
        if (previousBotMessage == null || previousBotMessage.isBlank()) {
            return new MatchResult(ResponseType.NO, 0);
        }
        
        try {
            String prompt = buildPrompt(message, previousBotMessage);
            JsonNode response = callOpenAI(prompt);
            
            String content = response.path("choices").get(0).path("message").path("content").asText().trim();
            String json = extractJson(content);
            JsonNode root = objectMapper.readTree(json);
            
            String answer = root.path("answer").asText("NO").toUpperCase();
            
            ResponseType result = switch (answer) {
                case "YES" -> ResponseType.YES;
                case "NEED_HISTORY" -> ResponseType.NEED_HISTORY;
                default -> ResponseType.NO;
            };
            
            long latency = System.currentTimeMillis() - start;
            logger.info("Response matched: {} ({}ms)", result, latency);
            
            return new MatchResult(result, latency);
            
        } catch (Exception e) {
            logger.error("Response matching error: {}", e.getMessage());
            // Fallback: if bot asked question, assume it's a response
            ResponseType fallback = previousBotMessage.contains("?") 
                    ? ResponseType.YES 
                    : ResponseType.NO;
            return new MatchResult(fallback, System.currentTimeMillis() - start);
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PROMPT
    // ═══════════════════════════════════════════════════════════════════════════
    
    private String buildPrompt(String message, String previousBotMessage) {
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

