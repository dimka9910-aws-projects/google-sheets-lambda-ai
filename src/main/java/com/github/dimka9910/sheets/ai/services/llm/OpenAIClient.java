package com.github.dimka9910.sheets.ai.services.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimka9910.sheets.ai.config.AppConfig;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAI API client implementation with retry logic.
 * 
 * Supports:
 * - Standard models: gpt-4o, gpt-4o-mini
 * - Reasoning models: gpt-5-mini, o1, o1-mini
 * 
 * Retry policy:
 * - Retries on 429 (rate limit), 500, 502, 503, 504 (server errors)
 * - Max 3 attempts with exponential backoff (1s, 2s, 4s)
 * - No retry on 400, 401, 403 (client errors)
 */
@Slf4j
public class OpenAIClient implements LLMClient {
    
    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    
    // Retry config
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 1000;
    private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(429, 500, 502, 503, 504);
    
    private static OpenAIClient instance;
    
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SINGLETON
    // ═══════════════════════════════════════════════════════════════════════════
    
    private OpenAIClient() {
        this.apiKey = AppConfig.getOpenAiApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new LLMException(
                "OpenAI API key not set. Add to application.properties or set OPENAI_API_KEY env var");
        }
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(DEFAULT_TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
    }
    
    public static synchronized OpenAIClient getInstance() {
        if (instance == null) {
            instance = new OpenAIClient();
        }
        return instance;
    }
    
    public static void setInstance(OpenAIClient client) {
        instance = client;
    }
    
    public OpenAIClient(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(DEFAULT_TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LLMClient IMPLEMENTATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    @Override
    public Response complete(String model, String prompt, int maxTokens, double temperature) throws LLMException {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        body.put("messages", List.of(
                Map.of("role", "user", "content", prompt)
        ));
        
        return executeWithRetry(body);
    }
    
    @Override
    public Response completeWithReasoning(String model, String prompt, int maxCompletionTokens) throws LLMException {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("max_completion_tokens", maxCompletionTokens);
        body.put("messages", List.of(
                Map.of("role", "user", "content", prompt)
        ));
        
        return executeWithRetry(body);
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // RETRY LOGIC
    // ═══════════════════════════════════════════════════════════════════════════
    
    private Response executeWithRetry(Map<String, Object> body) throws LLMException {
        LLMException lastException = null;
        
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return executeRequest(body);
            } catch (LLMException e) {
                lastException = e;
                
                if (!isRetryable(e)) {
                    log.warn("Non-retryable error (status={}), failing immediately", e.getStatusCode());
                    throw e;
                }
                
                if (attempt < MAX_RETRIES) {
                    long backoffMs = INITIAL_BACKOFF_MS * (1L << (attempt - 1)); // 1s, 2s, 4s
                    log.warn("Retryable error (status={}), attempt {}/{}, waiting {}ms", 
                            e.getStatusCode(), attempt, MAX_RETRIES, backoffMs);
                    
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new LLMException("Interrupted during retry backoff", ie);
                    }
                }
            }
        }
        
        log.error("All {} retry attempts failed", MAX_RETRIES);
        throw lastException;
    }
    
    private boolean isRetryable(LLMException e) {
        return RETRYABLE_STATUS_CODES.contains(e.getStatusCode());
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HTTP REQUEST
    // ═══════════════════════════════════════════════════════════════════════════
    
    private Response executeRequest(Map<String, Object> body) throws LLMException {
        try {
            String jsonBody = objectMapper.writeValueAsString(body);
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OPENAI_API_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(DEFAULT_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            
            log.debug("OpenAI request to {}: {} chars", body.get("model"), jsonBody.length());
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                log.error("OpenAI API error {}: {}", response.statusCode(), response.body());
                throw new LLMException(response.statusCode(), response.body());
            }
            
            JsonNode root = objectMapper.readTree(response.body());
            
            String content = root.path("choices").get(0).path("message").path("content").asText();
            int totalTokens = root.path("usage").path("total_tokens").asInt(0);
            int reasoningTokens = root.path("usage")
                    .path("completion_tokens_details")
                    .path("reasoning_tokens").asInt(0);
            
            log.debug("OpenAI response: {} tokens (reasoning: {})", totalTokens, reasoningTokens);
            
            return new Response(content, totalTokens, reasoningTokens, root);
            
        } catch (LLMException e) {
            throw e;
        } catch (Exception e) {
            throw new LLMException("OpenAI request failed: " + e.getMessage(), e);
        }
    }
}
