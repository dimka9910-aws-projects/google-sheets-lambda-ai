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
 * Logic depends on hasPendingResponse flag:
 * - hasPendingResponse=true  → bot asked a question, expecting direct answer
 * - hasPendingResponse=false → bot just confirmed something, user might correct/change
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
        YES,  // User responds to previous bot message
        NO    // New topic, not related to bot's message
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
     * @param hasPendingResponse True if bot asked a question and expects direct answer,
     *                           False if bot just confirmed and user might correct
     * @return MatchResult with response type
     */
    public MatchResult match(String message, String previousBotMessage, boolean hasPendingResponse) {
        long start = System.currentTimeMillis();
        
        // No previous message → definitely NO
        if (previousBotMessage == null || previousBotMessage.isBlank()) {
            return new MatchResult(ResponseType.NO, 0);
        }
        
        try {
            String prompt = buildPrompt(message, previousBotMessage, hasPendingResponse);
            JsonNode response = callOpenAI(prompt);
            
            String content = response.path("choices").get(0).path("message").path("content").asText().trim();
            String json = extractJson(content);
            JsonNode root = objectMapper.readTree(json);
            
            String answer = root.path("answer").asText("NO").toUpperCase();
            
            ResponseType result = "YES".equals(answer) ? ResponseType.YES : ResponseType.NO;
            
            long latency = System.currentTimeMillis() - start;
            logger.info("Response matched: {} (pending={}, {}ms)", result, hasPendingResponse, latency);
            
            return new MatchResult(result, latency);
            
        } catch (Exception e) {
            logger.error("Response matching error: {}", e.getMessage());
            // Fallback: if pending response expected and bot asked question → assume YES
            ResponseType fallback = hasPendingResponse && previousBotMessage.contains("?") 
                    ? ResponseType.YES 
                    : ResponseType.NO;
            return new MatchResult(fallback, System.currentTimeMillis() - start);
        }
    }
    
    /**
     * Simplified version without pending flag (defaults to false).
     */
    public MatchResult match(String message, String previousBotMessage) {
        return match(message, previousBotMessage, false);
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PROMPTS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private String buildPrompt(String message, String previousBotMessage, boolean hasPendingResponse) {
        if (hasPendingResponse) {
            return buildPendingResponsePrompt(message, previousBotMessage);
        } else {
            return buildCorrectionPrompt(message, previousBotMessage);
        }
    }
    
    /**
     * Prompt for when bot asked a question (hasPendingResponse=true).
     * User should provide a direct answer to the question.
     */
    private String buildPendingResponsePrompt(String message, String previousBotMessage) {
        return """
            The bot asked a question and is waiting for an answer.
            Is the user's message a RESPONSE or REACTION to this question?
            
            YES means:
            - User answers the bot's question (provides requested info)
            - User confirms or denies what bot asked
            - User gives the value bot asked for (amount, account, currency, yes/no, etc.)
            - Even vague/incomplete answers that seem related to bot's question count as YES
            - User asks additional question related to bot's question
            - User gives a complex answer, part of which is related to bot's question
            - User wants to CANCEL or ABANDON the question (forget it, nevermind, cancel, skip)
            - User expresses frustration or wants to move on (whatever, ok forget it)
            
            NO means:
            - User starts a completely NEW topic, ignoring the question
            - User asks their own question completely unrelated to bot's question
            - User gives a command that has nothing to do with what was asked
            - User reports new financial transaction
            
            Bot asked: %s
            User replied: %s
            
            JSON (no explanation): {"answer": "YES"} or {"answer": "NO"}
            """.formatted(previousBotMessage, message);
    }
    
    /**
     * Prompt for when bot just confirmed something (hasPendingResponse=false).
     * User might want to correct, disagree, or modify.
     */
    private String buildCorrectionPrompt(String message, String previousBotMessage) {
        return """
            The bot just confirmed or recorded something.
            Is the user's message a CORRECTION or MODIFICATION of what bot did?
            
            YES means:
            - User disagrees with what bot recorded (wrong, no, not that)
            - User wants to change/fix something (fix it, change to, not X but Y)
            - User says it was wrong amount/account/category
            - User wants to undo or cancel (undo, delete, cancel)
            - User references the previous action to modify it
            - User expresses dissatisfaction with bot's response (angry, complaining, upset)
            
            NO means:
            - User starts a NEW transaction (even if similar to previous)
            - User says something unrelated to what bot did
            - User accepts and moves on to something new
            - New expense after confirmation = NEW transaction, not correction
            
            Bot said: %s
            User said: %s
            
            JSON (no explanation): {"answer": "YES"} or {"answer": "NO"}
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
