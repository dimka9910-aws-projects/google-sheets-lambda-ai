package com.github.dimka9910.sheets.ai.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimka9910.sheets.ai.config.AppConfig;
import com.github.dimka9910.sheets.ai.dto.OperationTypeEnum;
import com.github.dimka9910.sheets.ai.dto.ParsedCommand;
import com.github.dimka9910.sheets.ai.dto.ParsedCommandList;
import com.github.dimka9910.sheets.ai.dto.UserContext;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class AICommandParser {

    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    
    // Модель и цены - легко менять
    private static final String MODEL = "gpt-5-mini";
    private static final double INPUT_PRICE_PER_1M = 0.25;  // gpt-5-mini
    private static final double OUTPUT_PRICE_PER_1M = 2.00; // gpt-5-mini (includes reasoning)
    
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final PromptBuilder promptBuilder;

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
        this.promptBuilder = new PromptBuilder();
    }

    // Конструктор для тестирования
    public AICommandParser(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
        this.promptBuilder = new PromptBuilder();
    }

    /**
     * Парсит команду(ы) с учётом контекста пользователя.
     * Поддерживает multi-command: "кофе 300, такси 500" → 2 операции
     */
    public ParsedCommandList parseMultiple(String userMessage, UserContext userContext) {
        log.info("Parsing message (multi-command) with context: {}", userMessage);

        try {
            String prompt = promptBuilder.buildPrompt(userContext, userMessage);
            log.debug("Full prompt length: {} chars", prompt.length());
            
            // Вызываем OpenAI API напрямую
            JsonNode apiResponse = callOpenAI(prompt);
            
            // Извлекаем ответ
            String content = apiResponse.path("choices").get(0).path("message").path("content").asText();
            log.info("AI response: {}", content);
            
            // Извлекаем token usage
            String tokenUsageStr = extractTokenUsage(apiResponse);
            
            // Парсим JSON ответ
            String cleanJson = cleanJsonResponse(content);
            ParsedCommandList result = objectMapper.readValue(cleanJson, ParsedCommandList.class);
            result.setTokenUsage(tokenUsageStr);
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
    
    /**
     * Вызывает OpenAI API напрямую через HTTP
     */
    private JsonNode callOpenAI(String prompt) throws Exception {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", MODEL);
        // gpt-5-mini (reasoning model): use max_completion_tokens, no temperature
        requestBody.put("max_completion_tokens", 2000);
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

    /**
     * Парсит команду с учётом контекста пользователя.
     * @deprecated Используй parseMultiple() для поддержки нескольких команд
     */
    @Deprecated
    public ParsedCommand parse(String userMessage, UserContext userContext) {
        log.info("Parsing message with context: {}", userMessage);

        ParsedCommandList result = parseMultiple(userMessage, userContext);
        
        if (result.getCommands() != null && !result.getCommands().isEmpty()) {
            ParsedCommand first = result.getFirst();
            first.setUnderstood(result.isUnderstood());
            if (result.getClarification() != null) {
                first.setClarification(result.getClarification());
            }
            if (result.getErrorMessage() != null) {
                first.setErrorMessage(result.getErrorMessage());
            }
            return first;
        }
        
        return ParsedCommand.builder()
                .operationType(OperationTypeEnum.UNKNOWN)
                .understood(result.isUnderstood())
                .clarification(result.getClarification())
                .errorMessage(result.getErrorMessage())
                .build();
    }

    /**
     * Парсит команду без контекста (для обратной совместимости и тестов)
     * @deprecated Используй parseMultiple() для поддержки нескольких команд
     */
    @Deprecated
    public ParsedCommand parse(String userMessage) {
        log.info("Parsing message without context: {}", userMessage);

        try {
            String prompt = promptBuilder.buildSimplePrompt(userMessage);
            JsonNode apiResponse = callOpenAI(prompt);
            String content = apiResponse.path("choices").get(0).path("message").path("content").asText();
            log.info("AI response: {}", content);

            String cleanJson = cleanJsonResponse(content);
            ParsedCommandList result = objectMapper.readValue(cleanJson, ParsedCommandList.class);
            
            if (result.getCommands() != null && !result.getCommands().isEmpty()) {
                ParsedCommand first = result.getFirst();
                first.setUnderstood(result.isUnderstood());
                if (result.getClarification() != null) {
                    first.setClarification(result.getClarification());
                }
                return first;
            }
            
            return ParsedCommand.builder()
                    .operationType(OperationTypeEnum.UNKNOWN)
                    .understood(result.isUnderstood())
                    .clarification(result.getClarification())
                    .errorMessage(result.getErrorMessage())
                    .build();

        } catch (Exception e) {
            log.error("Error parsing command: {}", e.getMessage(), e);
            return ParsedCommand.builder()
                    .operationType(OperationTypeEnum.UNKNOWN)
                    .understood(false)
                    .errorMessage("Error: " + e.getMessage())
                    .clarification("Sorry, please try again.")
                    .build();
        }
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
