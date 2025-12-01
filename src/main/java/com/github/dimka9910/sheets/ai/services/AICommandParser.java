package com.github.dimka9910.sheets.ai.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimka9910.sheets.ai.config.AppConfig;
import com.github.dimka9910.sheets.ai.dto.OperationTypeEnum;
import com.github.dimka9910.sheets.ai.dto.ParsedCommand;
import com.github.dimka9910.sheets.ai.dto.UserContext;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

@Slf4j
public class AICommandParser {

    private final ChatLanguageModel model;
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
                .modelName("gpt-4o-mini")
                .temperature(0.1)
                .timeout(Duration.ofSeconds(30))
                .build();

        this.objectMapper = new ObjectMapper();
        this.promptBuilder = new PromptBuilder();
    }

    // Конструктор для тестирования
    public AICommandParser(ChatLanguageModel model) {
        this.model = model;
        this.objectMapper = new ObjectMapper();
        this.promptBuilder = new PromptBuilder();
    }

    /**
     * Парсит команду с учётом контекста пользователя
     */
    public ParsedCommand parse(String userMessage, UserContext userContext) {
        log.info("Parsing message with context: {}", userMessage);

        try {
            String prompt = promptBuilder.buildPrompt(userContext, userMessage);
            log.debug("Full prompt: {}", prompt);
            
            String response = model.generate(prompt);
            log.info("AI response: {}", response);

            String cleanJson = cleanJsonResponse(response);
            return objectMapper.readValue(cleanJson, ParsedCommand.class);

        } catch (Exception e) {
            log.error("Error parsing command: {}", e.getMessage(), e);
            return ParsedCommand.builder()
                    .operationType(OperationTypeEnum.UNKNOWN)
                    .understood(false)
                    .errorMessage("Ошибка обработки: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Парсит команду без контекста (для обратной совместимости и тестов)
     */
    public ParsedCommand parse(String userMessage) {
        log.info("Parsing message without context: {}", userMessage);

        try {
            String prompt = promptBuilder.buildSimplePrompt(userMessage);
            String response = model.generate(prompt);
            log.info("AI response: {}", response);

            String cleanJson = cleanJsonResponse(response);
            return objectMapper.readValue(cleanJson, ParsedCommand.class);

        } catch (Exception e) {
            log.error("Error parsing command: {}", e.getMessage(), e);
            return ParsedCommand.builder()
                    .operationType(OperationTypeEnum.UNKNOWN)
                    .understood(false)
                    .errorMessage("Ошибка обработки: " + e.getMessage())
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
