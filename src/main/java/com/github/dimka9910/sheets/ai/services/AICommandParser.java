package com.github.dimka9910.sheets.ai.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimka9910.sheets.ai.config.AppConfig;
import com.github.dimka9910.sheets.ai.dto.OperationTypeEnum;
import com.github.dimka9910.sheets.ai.dto.ParsedCommand;
import com.github.dimka9910.sheets.ai.dto.ParsedCommandList;
import com.github.dimka9910.sheets.ai.dto.UserContext;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;

@Slf4j
public class AICommandParser {

    private final OpenAiChatModel model;
    private final ObjectMapper objectMapper;
    private final PromptBuilder promptBuilder;

    public AICommandParser() {
        String apiKey = AppConfig.getOpenAiApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "OpenAI API key not set. Add it to application.properties or set OPENAI_API_KEY env variable");
        }

        this.model = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName("gpt-4o")
                .temperature(0.1)
                .timeout(Duration.ofSeconds(30))
                .build();

        this.objectMapper = new ObjectMapper();
        this.promptBuilder = new PromptBuilder();
    }

    // Конструктор для тестирования
    public AICommandParser(OpenAiChatModel model) {
        this.model = model;
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
            log.debug("Full prompt: {}", prompt);
            
            // Используем chat() для получения информации о токенах
            ChatRequest chatRequest = ChatRequest.builder()
                    .messages(List.of(UserMessage.from(prompt)))
                    .build();
            ChatResponse chatResponse = model.chat(chatRequest);
            String response = chatResponse.aiMessage().text();
            log.info("AI response: {}", response);
            
            // Формируем строку с информацией о токенах
            String tokenUsageStr = null;
            TokenUsage usage = chatResponse.tokenUsage();
            if (usage != null) {
                tokenUsageStr = String.format("🔢 Токены: in=%d, out=%d, total=%d", 
                    usage.inputTokenCount(), 
                    usage.outputTokenCount(), 
                    usage.totalTokenCount());
                log.info("Token usage: {}", tokenUsageStr);
            }

            String cleanJson = cleanJsonResponse(response);
            ParsedCommandList result = objectMapper.readValue(cleanJson, ParsedCommandList.class);
            result.setTokenUsage(tokenUsageStr);
            return result;

        } catch (Exception e) {
            log.error("Error parsing command: {}", e.getMessage(), e);
            return ParsedCommandList.builder()
                    .commands(List.of())
                    .understood(false)
                    .errorMessage("Error: " + e.getMessage()) // Technical error, will be logged
                    .clarification("Sorry, please try again.") // Neutral fallback
                    .build();
        }
    }

    /**
     * Парсит команду с учётом контекста пользователя.
     * @deprecated Используй parseMultiple() для поддержки нескольких команд
     */
    @Deprecated
    public ParsedCommand parse(String userMessage, UserContext userContext) {
        log.info("Parsing message with context: {}", userMessage);

        ParsedCommandList result = parseMultiple(userMessage, userContext);
        
        // Для обратной совместимости возвращаем первую команду или ошибку
        if (result.getCommands() != null && !result.getCommands().isEmpty()) {
            ParsedCommand first = result.getFirst();
            // Переносим общий статус в команду
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
            String response = model.generate(prompt);
            log.info("AI response: {}", response);

            String cleanJson = cleanJsonResponse(response);
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
