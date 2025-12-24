package com.github.dimka9910.sheets.ai.services.agents;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Spring Component for message classification using Spring AI + OpenAI.
 * Uses gpt-4o-mini (fast, cheap) for classification.
 * 
 * Leverages Spring AI features:
 * - ChatModel for LLM calls
 * - BeanOutputConverter for structured JSON output
 * - Automatic retry and error handling
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageClassifierAgent {
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIG
    // ═══════════════════════════════════════════════════════════════════════════
    
    private static final String MODEL = "gpt-4o-mini";
    private static final int MAX_TOKENS = 500;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // REQUEST / RESPONSE
    // ═══════════════════════════════════════════════════════════════════════════
    
    public record Request(String message) {}
    
    public record Response(
            Category category,
            String rawJson,
            String errorMessage
    ) {
        public boolean isSuccess() {
            return errorMessage == null;
        }
        
        public boolean needsFullContext() {
            return category == Category.COMPLEX_ACTION;
        }
    }
    
    /**
     * Message categories (ONLY ONE per message).
     */
    public enum Category {
        SIMPLE_EXPENSE,           // Single expense: "кофе 200", "такси 500"
        INTERNAL_TRANSFER,        // Transfer between own accounts: "перевод 1000 с визы на кеш"
        THIRD_PARTY_ACTION,       // Operations with linked users: "Ксюше 200", "за девушку"
        SIMPLE_CUSTOM_INSTRUCTION, // Custom instruction: "Ксюша = KIKI", "запомни райф это виза"
        COMPLEX_ACTION            // Everything else → MainAgent with full context
    }

    // ═══════════════════════════════════════════════════════════════════════════
    
    private static final String PROMPT_INTRO = """
            You are a message classifier for a personal finance bot.
            Users write in ANY language (Russian, English, Serbian, mixed, etc).
            
            ## Your Task
            Classify the message into EXACTLY ONE category.
            Choose the most specific category that matches.
            """;

    private static final String PROMPT_CATEGORIES = """
            
            ## Categories (choose EXACTLY ONE)
            
            **SIMPLE_EXPENSE** - Single straightforward expense
            - One amount + optional item name
            - Examples: "кофе 200", "такси 500", "продукты 3000", "200"
            - NO person names, NO transfers between accounts
            
            **INTERNAL_TRANSFER** - Transfer between user's OWN accounts
            - Keywords: перевод, transfer, move, снял (withdrew), пополнил (topped up)
            - From/to user's accounts (not to other people)
            - Examples: "перевод 1000 с визы на кеш", "снял 500 с карты"
            
            **THIRD_PARTY_ACTION** - Involves another person (linked user)
            - Mentions person by name or relationship (Ксюша, девушка, girlfriend, wife)
            - Paying FOR someone, receiving FROM someone, transfers to/from people
            - Examples: "Ксюше 200", "за девушку 1500", "от Димы 500"
            
            **SIMPLE_CUSTOM_INSTRUCTION** - Remember/alias instructions
            - User wants to save a setting, alias, or custom instruction
            - Keywords: запомни (remember), btw, by the way, just so you know
            - Setting aliases: "Ксюша = KIKI", "райф = visa raiffeisen"
            - Examples: "запомни что Ксюша это KIKI", "райф это моя основная карта"
            
            **COMPLEX_ACTION** - Everything else (default fallback)
            - Multiple operations in one message
            - Questions about settings, help, show data
            - Corrections to previous transactions
            - Math expressions, calculations
            - Unclear, ambiguous, slang
            - When in doubt → COMPLEX_ACTION
            """;
    
    private static final String PROMPT_RULES = """
            
            ## Rules
            - Return ONLY ONE category
            - If message matches SIMPLE_* category → use it (faster processing)
            - If unclear or doesn't fit simple patterns → COMPLEX_ACTION
            - When in doubt → COMPLEX_ACTION (safe default)
            """;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DTO for Structured Output
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Classification result from LLM (used for structured output parsing).
     */
    public record ClassificationResult(String category) {}
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DEPENDENCIES (injected by Spring)
    // ═══════════════════════════════════════════════════════════════════════════
    
    private final ChatModel chatModel;
    private final BeanOutputConverter<ClassificationResult> outputConverter;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONSTRUCTOR (for manual converter creation)
    // ═══════════════════════════════════════════════════════════════════════════
    
    public MessageClassifierAgent(ChatModel chatModel) {
        this.chatModel = chatModel;
        this.outputConverter = new BeanOutputConverter<>(ClassificationResult.class);
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PROCESS (using Spring AI)
    // ═══════════════════════════════════════════════════════════════════════════
    
    public Response process(Request request) {
        try {
            // Build system + user messages
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(request);
            
            // Add JSON schema for structured output
            String format = outputConverter.getFormat();
            systemPrompt += "\n\n## Response Format\nReturn JSON following this schema:\n" + format;
            
            // Create Spring AI Prompt with messages
            Prompt prompt = new Prompt(
                    List.of(
                            new SystemMessage(systemPrompt),
                            new UserMessage(userPrompt)
                    ),
                    OpenAiChatOptions.builder()
                            .model(MODEL)
                            .maxTokens(MAX_TOKENS)
                            .temperature(0.3)  // Lower temperature for classification
                            .build()
            );
            
            // Call LLM via Spring AI (observability handled automatically)
            ChatResponse chatResponse = chatModel.call(prompt);
            
            // Parse structured output
            String content = chatResponse.getResult().getOutput().getText();
            ClassificationResult result = outputConverter.convert(content);
            
            // Convert to Category enum
            Category category = parseCategory(result.category());
            
            log.info("✅ Classification: category={}", category);
            
            return new Response(category, content, null);
            
        } catch (Exception e) {
            log.error("❌ Classification error: {}", e.getMessage(), e);
            return new Response(
                    Category.COMPLEX_ACTION,  // Safe fallback
                    "{\"error\":\"" + e.getMessage() + "\"}",
                    e.getMessage()
            );
        }
    }
    
    /**
     * Convenience method for direct call.
     */
    public Response classify(String message) {
        return process(new Request(message));
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // BUILD PROMPT (Spring AI uses system + user messages)
    // ═══════════════════════════════════════════════════════════════════════════
    
    private String buildSystemPrompt() {
        return PROMPT_INTRO + PROMPT_CATEGORIES + PROMPT_RULES;
    }
    
    private String buildUserPrompt(Request request) {
        // Simplified: only the current message
        return "## User Message\n```\n" + request.message() + "\n```";
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PARSE CATEGORY (Spring AI handles JSON parsing, we just convert string to enum)
    // ═══════════════════════════════════════════════════════════════════════════
    
    private Category parseCategory(String categoryStr) {
        try {
            return Category.valueOf(categoryStr.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown category '{}', falling back to COMPLEX_ACTION", categoryStr);
            return Category.COMPLEX_ACTION;  // Safe fallback
        }
    }
}
