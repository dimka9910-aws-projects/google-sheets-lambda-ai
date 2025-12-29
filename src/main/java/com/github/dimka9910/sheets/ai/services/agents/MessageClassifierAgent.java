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
            List<String> linkedUserNamesAndAliases  // All names + aliases of linked users
    ) {}
    
    public record Response(
            Category category,
            String rawJson,
            String errorMessage
    ) {}
    
    /**
     * Message categories (ONLY ONE per message).
     * Split financial operations for token optimization.
     */
    public enum Category {
        SIMPLE_FINANCIAL,         // Financial operation WITHOUT linked users → FinancialAgent (minimal context, cheap)
        THIRD_PARTY_FINANCIAL,    // Financial operation WITH linked users → FinancialAgent (with linked users context)
        COMPLEX_ACTION            // Everything else → MainAgent with full context (corrections, multi-step, custom instructions)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PROMPT TEMPLATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private static final String PROMPT_TEMPLATE = """
            You are a message classifier for a personal finance bot.
            Users write in ANY language (Russian, English, Serbian, mixed, etc).
            
            ## Your Task
            Classify the message into EXACTLY ONE category.
            
            ## Categories (choose EXACTLY ONE)
            
            **SIMPLE_FINANCIAL** - Financial operation WITHOUT linked users
            This includes:
            - Simple expenses: "coffee 200", "taxi 500", "groceries 3000"
            - Transfers between own accounts: "transfer 1000 from card to cash", "withdrew 500"
            - Expenses with generic person mentions (not specific linked users): "for friends", "brothers", "guys"
            
            Characteristics:
            - ONE clear operation (not multiple)
            - No specific linked users mentioned
            - All information is straightforward
            
            **THIRD_PARTY_FINANCIAL** - Financial operation WITH specific linked users
            This includes:
            - Transfers to/from specific linked users: "sent 500 to {linkedUsersExample}", "got 200 from {linkedUsersExample}"
            - Expenses for specific linked users: "bought coffee for {linkedUsersExample} 200"
            
            Characteristics:
            - ONE clear operation (not multiple)
            - Explicitly mentions SPECIFIC linked user by name or alias
            - All information is straightforward
            
            **COMPLEX_ACTION** - Everything else (default fallback)
            This includes:
            - Multiple operations in one message
            - Corrections to previous transactions (keywords: "не", "not", "actually", "изменить", "change")
            - Questions about settings, help, show data
            - Custom instructions, aliases, settings (e.g. "remember Sarah is USER_X")
            - Math expressions, calculations
            - Unclear, ambiguous, or slang-heavy messages
            - When in doubt → COMPLEX_ACTION
            
            ## Special Rules
            - If message contains NEGATION ("не", "not", "нет", "actually") → COMPLEX_ACTION (likely a correction)
            - If person mentioned is NOT in linked users list → SIMPLE_FINANCIAL (treated as expense with comment)
            - Generic words like "friends", "brothers", "guys" (not specific names) → SIMPLE_FINANCIAL (expense with comment)
            - When in doubt → COMPLEX_ACTION (safe default)
            
            {linkedUsersContext}
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
                            .maxCompletionTokens(MAX_TOKENS)
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
    public Category classify(String message, List<String> linkedUserNamesAndAliases) {

      var classifierResponse = process(new Request(message, linkedUserNamesAndAliases));

      if (classifierResponse.errorMessage() != null) {
        log.error("Classification failed: {}", classifierResponse.errorMessage());
        return Category.COMPLEX_ACTION; // Safe fallback
      }

      Category category = classifierResponse.category();
      log.info("ClassifierAgent: category={} (linkedUsers={})", category, linkedUserNamesAndAliases);

      return category;
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // BUILD PROMPT (Spring AI uses system + user messages)
    // ═══════════════════════════════════════════════════════════════════════════
    
    private String buildSystemPrompt(Request request) {
        Map<String, Object> params = new HashMap<>();
        
        // Include linked users context if available (for better classification)
        if (request.linkedUserNamesAndAliases() != null && !request.linkedUserNamesAndAliases().isEmpty()) {
            String linkedUsersStr = String.join(", ", request.linkedUserNamesAndAliases());
            String linkedUsersExample = request.linkedUserNamesAndAliases().get(0);
            
            String linkedUsersContext = String.format(
                "## Linked Users\nUser has these linked users: %s\n" +
                "Messages mentioning these specific names/aliases can be SIMPLE_FINANCIAL.\n" +
                "Generic terms (friends, brothers, etc.) → SIMPLE_FINANCIAL (expense with comment).",
                linkedUsersStr
            );
            
            params.put("linkedUsersContext", linkedUsersContext);
            params.put("linkedUsersExample", linkedUsersExample);
        } else {
            params.put("linkedUsersContext", "## Linked Users\nUser has no linked users.");
            params.put("linkedUsersExample", "friend");
        }
        
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
