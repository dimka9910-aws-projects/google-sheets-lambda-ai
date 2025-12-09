package com.github.dimka9910.sheets.ai.services.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimka9910.sheets.ai.config.AppConfig;
import com.github.dimka9910.sheets.ai.dto.OperationTypeEnum;
import com.github.dimka9910.sheets.ai.dto.ParsedCommand;
import com.github.dimka9910.sheets.ai.dto.ParsedCommandList;
import com.github.dimka9910.sheets.ai.dto.UserContext;
import com.github.dimka9910.sheets.ai.services.Orchestrator.MatchedLinkedUser;
import com.github.dimka9910.sheets.ai.services.Orchestrator.OrchestrationResult;
import com.github.dimka9910.sheets.ai.services.llm.MessageClassifierAgent.Tag;
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

@Slf4j
public class AICommandParser {

    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    
    // Model and pricing
    private static final String MODEL = "gpt-5-mini";
    private static final double INPUT_PRICE_PER_1M = 0.25;
    private static final double OUTPUT_PRICE_PER_1M = 2.00;
    
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final MainAgent mainAgent;

    public AICommandParser() {
        this.apiKey = AppConfig.getOpenAiApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "OpenAI API key not set. Add it to application.properties or set OPENAI_API_KEY env variable");
        }

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
        this.mainAgent = new MainAgent();
    }

    public AICommandParser(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
        this.mainAgent = new MainAgent();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIMARY API - Use with Orchestrator
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Parse user message with orchestration result (tags, isResponse, matchedLinkedUser).
     * This is the preferred method - uses dynamic context loading.
     */
    public ParsedCommandList parse(String userMessage, 
                                   UserContext userContext, 
                                   OrchestrationResult orchestration) {
        return parse(userMessage, userContext, 
                orchestration.tags(), 
                orchestration.isResponse(), 
                orchestration.matchedLinkedUser());
    }

    /**
     * Parse with explicit tags and response flag.
     */
    public ParsedCommandList parse(String userMessage, 
                                   UserContext userContext,
                                   Set<Tag> tags,
                                   boolean isResponse,
                                   MatchedLinkedUser matchedLinkedUser) {
        log.info("Parsing: \"{}\" | tags={} | isResponse={}", 
                truncate(userMessage, 50), tags, isResponse);

        try {
            String prompt = mainAgent.buildPrompt(userContext, userMessage, tags, isResponse, matchedLinkedUser);
            log.debug("Prompt length: {} chars", prompt.length());
            
            JsonNode apiResponse = callOpenAI(prompt);
            String content = apiResponse.path("choices").get(0).path("message").path("content").asText();
            log.info("AI response: {}", truncate(content, 200));
            
            String tokenUsageStr = extractTokenUsage(apiResponse);
            String cleanJson = cleanJsonResponse(content);
            
            ParsedCommandList result = objectMapper.readValue(cleanJson, ParsedCommandList.class);
            result.setTokenUsage(tokenUsageStr);
            
            // TODO: Handle needsContext response - re-run with additional context
            
            return result;

        } catch (Exception e) {
            log.error("Error parsing command: {}", e.getMessage(), e);
            return ParsedCommandList.builder()
                    .commands(List.of())
                    .understood(false)
                    .errorMessage("Error: " + e.getMessage())
                    .clarification("Sorry, please try again.")
                    .build();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LEGACY API - For backward compatibility
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Legacy method - uses all tags (full context).
     * @deprecated Use parse(message, context, orchestrationResult) instead
     */
    @Deprecated
    public ParsedCommandList parseMultiple(String userMessage, UserContext userContext) {
        // Load full context for backward compatibility
        Set<Tag> allTags = Set.of(Tag.FINANCIAL, Tag.TRANSFER, Tag.THIRD_PARTY, Tag.SETTINGS);
        return parse(userMessage, userContext, allTags, false, null);
    }
    
    /**
     * Вызывает OpenAI API напрямую через HTTP
     */
    private JsonNode callOpenAI(String prompt) throws Exception {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", MODEL);
        // gpt-5-mini (reasoning model): use max_completion_tokens, no temperature
        // Increased from 2000 to 4000 for complex multi-person expenses
        requestBody.put("max_completion_tokens", 4000);
        requestBody.put("messages", List.of(
                Map.of("role", "user", "content", prompt)
        ));
        
        String jsonBody = objectMapper.writeValueAsString(requestBody);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OPENAI_API_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            log.error("OpenAI API error: {} - {}", response.statusCode(), response.body());
            throw new RuntimeException("OpenAI API error: " + response.statusCode());
        }
        
        return objectMapper.readTree(response.body());
    }
    
    /**
     * Извлекает информацию о токенах и считает стоимость
     */
    private String extractTokenUsage(JsonNode apiResponse) {
        JsonNode usage = apiResponse.path("usage");
        if (usage.isMissingNode()) {
            return null;
        }
        
        int inputTokens = usage.path("prompt_tokens").asInt();
        int outputTokens = usage.path("completion_tokens").asInt();
        
        // Проверяем reasoning tokens (для gpt-5-mini и подобных)
        JsonNode completionDetails = usage.path("completion_tokens_details");
        int reasoningTokens = 0;
        if (!completionDetails.isMissingNode()) {
            reasoningTokens = completionDetails.path("reasoning_tokens").asInt();
        }
        
        double inputCost = inputTokens * INPUT_PRICE_PER_1M / 1_000_000;
        double outputCost = outputTokens * OUTPUT_PRICE_PER_1M / 1_000_000;
        double totalCost = inputCost + outputCost;
        
        String result;
        if (reasoningTokens > 0) {
            result = String.format("🔢 in=%d, out=%d (reason=%d) | 💰 ~$%.5f (%s)", 
                    inputTokens, outputTokens, reasoningTokens, totalCost, MODEL);
        } else {
            result = String.format("🔢 in=%d, out=%d | 💰 ~$%.5f (%s)", 
                    inputTokens, outputTokens, totalCost, MODEL);
        }
        
        log.info("Token usage: {}", result);
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private String cleanJsonResponse(String response) {
        String cleaned = response.trim();

        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }

        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }

        return cleaned.trim();
    }
}
