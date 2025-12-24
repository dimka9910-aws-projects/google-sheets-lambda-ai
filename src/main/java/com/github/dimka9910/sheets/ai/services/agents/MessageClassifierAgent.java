package com.github.dimka9910.sheets.ai.services.agents;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    
    public record Request(
            String message,
            boolean hasLinkedUsers
    ) {}
    
    public record Response(
            Category category,
            String rawJson,
            String errorMessage
    ) {}
    
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
    // PROMPT TEMPLATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private static final String PROMPT_TEMPLATE = """
            You are a message classifier for a personal finance bot.
            Users write in ANY language (Russian, English, Serbian, mixed, etc).
            
            ## Your Task
            Classify the message into EXACTLY ONE category.
            Choose the most specific category that matches.
            
            ## Categories (choose EXACTLY ONE)
            
            **SIMPLE_EXPENSE** - Single straightforward expense
            - One amount + optional item name
            - Examples: "coffee 200", "taxi 500", "groceries 3000", "200"
            - NO person names, NO transfers between accounts
            
            **INTERNAL_TRANSFER** - Transfer between user's OWN accounts
            - Keywords: transfer, move, withdraw, deposit, top up (any language)
            - From/to user's accounts (not to other people)
            - Examples: "transfer 1000 from card A to cash", "withdrew 500 from card"
            
            {thirdPartyCategory}
            
            **SIMPLE_CUSTOM_INSTRUCTION** - Remember/alias instructions
            - User wants to save a setting, alias, or custom instruction
            - Keywords: remember, btw, by the way, just so you know (any language)
            - Setting aliases: "Sarah = USER_X", "card nickname = bank account"
            - Examples: "remember that Sarah is USER_X", "main card is account Y"
            
            **COMPLEX_ACTION** - Everything else (default fallback)
            - Multiple operations in one message
            - Questions about settings, help, show data
            - Corrections to previous transactions
            - Math expressions, calculations
            - Unclear, ambiguous, slang
            - When in doubt → COMPLEX_ACTION
            
            ## Rules
            - Return ONLY ONE category
            - If unclear or doesn't fit simple patterns → COMPLEX_ACTION
            - When in doubt → COMPLEX_ACTION (safe default)
            """;
    
    private static final String THIRD_PARTY_CATEGORY_TEXT = """
            **THIRD_PARTY_ACTION** - Involves another person (linked user)
            - Mentions person by name or relationship (Sarah, girlfriend, wife, partner)
            - Paying FOR someone, receiving FROM someone, transfers to/from people
            - Examples: "to Sarah 200", "for girlfriend 1500", "from partner 500"
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
            // Build system + user messages (dynamic based on user context)
            String systemPrompt = buildSystemPrompt(request);
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
    public Category classify(String message, boolean hasLinkedUsers) {

      var classifierResponse = process(new Request(message, hasLinkedUsers));

      if (classifierResponse.errorMessage() != null) {
        log.error("Classification failed: {}", classifierResponse.errorMessage());
        return Category.COMPLEX_ACTION; // Safe fallback
      }

      Category category = classifierResponse.category();
      log.info("ClassifierAgent: category={} (hasLinkedUsers={})", category, hasLinkedUsers);

      return category;
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // BUILD PROMPT (Spring AI uses system + user messages)
    // ═══════════════════════════════════════════════════════════════════════════
    
    private String buildSystemPrompt(Request request) {
        Map<String, Object> params = new HashMap<>();
        
        // Only include THIRD_PARTY_ACTION category if user has linked users
        String thirdPartyCategory = request.hasLinkedUsers() ? THIRD_PARTY_CATEGORY_TEXT : "";
        params.put("thirdPartyCategory", thirdPartyCategory);
        
        PromptTemplate template = new PromptTemplate(PROMPT_TEMPLATE);
        return template.render(params);
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
