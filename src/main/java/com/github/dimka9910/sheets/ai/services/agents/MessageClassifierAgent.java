package com.github.dimka9910.sheets.ai.services.agents;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
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
    
    public record Request(
            String message,
            String previousBotMessage
    ) {}
    
    public record Response(
            Set<Tag> tags,
            String rawJson,
            long latencyMs,
            int tokensUsed,
            String errorMessage
    ) {
        public boolean isSuccess() {
            return errorMessage == null;
        }
        
        public boolean isComplex() {
            return tags.contains(Tag.COMPLEX);
        }
        
        public boolean needsLinkedUsers() {
            return tags.contains(Tag.THIRD_PARTY) || tags.contains(Tag.TRANSFER);
        }
    }
    
    public enum Tag {
        FINANCIAL,      // Money transaction (expense, income)
        UTILS,          // Utilities (settings, help, meta commands, questions)
        OFF_TOPIC,      // Unrelated to finance
        TRANSFER,       // Transfer between OWN accounts
        THIRD_PARTY,    // Involves another person
        COMPLEX         // Needs smarter model
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PROMPT PARTS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private static final String PROMPT_INTRO = """
            You are a context classifier for a personal finance bot.
            Users write in ANY language (Russian, English, Serbian, mixed, etc).
            
            ## Your Task
            Determine what context the main AI agent needs to process this message.
            DO NOT process the message itself - just classify what context to load.
            """;
    
    private static final String PROMPT_TAGS = """
            
            ## Output: tags (array - can have multiple)
            
            **Primary categories:**
            
            **FINANCIAL** - message involves money transactions or purchase record
            - Recording expense, income, or transfer
            - Mentions amount, currency, account, purchase
            - Single word that is a product/service name (coffee, taxi, lunch) = FINANCIAL
            - User says product name meaning "I bought X" - this is FINANCIAL, not OFF_TOPIC!
            
            **UTILS** - utilities: settings, help, meta commands, questions
            - Setting defaults (account, currency, fund)
            - Adding/changing custom instructions or aliases
            - "remember", "btw", "by the way", "just so you know"
            - Help requests, show settings, show user's configuration, funds, accounts, aliases, context, any related to user application data
            - How to use the bot, questions about bot capabilities
            - Questions about user's data: accounts, funds, settings
            - "What can you do?", "How does this work?", "какие у меня фонды?", "настройки", "помощь"
            
            **OFF_TOPIC** - message completely unrelated to finance or the bot
            - Jokes, weather, general knowledge questions
            - NOT questions about bot/user data - those are UTILS!
            
            **Financial sub-tags (add together with FINANCIAL):**
            
            **TRANSFER** - moving money between user's OWN accounts
            - Keywords: transfer, move, перевод, from X to Y (where X and Y are accounts)
            - Cash withdrawal: снял/withdrew cash from card/account
            - Card top-up: пополнил/deposited cash to card/account
            - Moving money between own accounts
            
            **THIRD_PARTY** - involves another person
            - Mentions someone else by name or relationship
            - Paying FOR someone, receiving FROM someone, splitting
            - Sending/receiving money to/from another person (not own accounts)
            - Asking for user details, settings details of some 3rd party
            
            **Complexity indicator:**
            
            **COMPLEX** - needs smarter model
            - Multiple financial operations in one message
            - Math expressions, calculations
            - Corrections referencing previous transactions
            - Ambiguous, slang, abbreviations
            """;
    
    private static final String PROMPT_RULES = """
            
            ## Rules
            - Tags can be MULTIPLE: ["FINANCIAL", "TRANSFER"] or ["FINANCIAL", "THIRD_PARTY", "COMPLEX"]
            - When in doubt about complexity → add COMPLEX (better safe)
            - Simple single expense → just ["FINANCIAL"]
            """;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DTO for Structured Output
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Classification result from LLM (used for structured output parsing).
     */
    public record ClassificationResult(List<String> tags) {}
    
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
        long start = System.currentTimeMillis();
        
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
                            .withModel(MODEL)
                            .withMaxTokens(MAX_TOKENS)
                            .withTemperature(0.3)  // Lower temperature for classification
                            .build()
            );
            
            // Call LLM via Spring AI
            org.springframework.ai.chat.model.ChatResponse chatResponse = chatModel.call(prompt);
            
            // Parse structured output
            String content = chatResponse.getResult().getOutput().getContent();
            ClassificationResult result = outputConverter.convert(content);
            
            // Convert to Set<Tag>
            Set<Tag> tags = parseTags(result.tags());
            
            long latency = System.currentTimeMillis() - start;
            int tokensUsed = chatResponse.getMetadata().getUsage().getTotalTokens().intValue();
            
            log.info("✅ Classification: tags={} ({}ms, {} tokens)", tags, latency, tokensUsed);
            
            return new Response(tags, content, latency, tokensUsed, null);
            
        } catch (Exception e) {
            log.error("❌ Classification error: {}", e.getMessage(), e);
            return new Response(
                    Set.of(Tag.FINANCIAL),  // Safe fallback
                    "{\"error\":\"" + e.getMessage() + "\"}",
                    System.currentTimeMillis() - start,
                    0,
                    e.getMessage()
            );
        }
    }
    
    /**
     * Convenience method for direct call.
     */
    public Response classify(String message, String previousBotMessage) {
        return process(new Request(message, previousBotMessage));
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // BUILD PROMPT (Spring AI uses system + user messages)
    // ═══════════════════════════════════════════════════════════════════════════
    
    private String buildSystemPrompt() {
        return PROMPT_INTRO + PROMPT_TAGS + PROMPT_RULES;
    }
    
    private String buildUserPrompt(Request request) {
        StringBuilder sb = new StringBuilder();
        
        // Context
        if (request.previousBotMessage() != null && !request.previousBotMessage().isBlank()) {
            sb.append("## Previous Bot Message\n```\n")
              .append(request.previousBotMessage())
              .append("\n```\n\n");
        } else {
            sb.append("## Previous Bot Message\nNone (new conversation)\n\n");
        }
        
        sb.append("## User Message\n```\n")
          .append(request.message())
          .append("\n```");
        
        return sb.toString();
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PARSE TAGS (Spring AI handles JSON parsing, we just convert strings to enums)
    // ═══════════════════════════════════════════════════════════════════════════
    
    private Set<Tag> parseTags(List<String> tagStrings) {
        Set<Tag> tags = new java.util.HashSet<>();
        
        for (String tagStr : tagStrings) {
            try {
                Tag tag = Tag.valueOf(tagStr.toUpperCase().trim());
                tags.add(tag);
            } catch (IllegalArgumentException e) {
                log.warn("Unknown tag '{}', ignoring", tagStr);
            }
        }
        
        if (tags.isEmpty()) {
            tags.add(Tag.FINANCIAL);  // Default fallback
        }
        
        return tags;
    }
}
