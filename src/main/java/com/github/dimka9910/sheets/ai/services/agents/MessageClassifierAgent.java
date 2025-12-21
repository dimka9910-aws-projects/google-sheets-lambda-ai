package com.github.dimka9910.sheets.ai.services.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimka9910.sheets.ai.services.llm.LLMClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Spring Component for message classification using LLM.
 * Uses gpt-4o-mini (fast, cheap) for classification.
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
    
    private static final String PROMPT_FORMAT = """
            
            ## Response Format (JSON only, no explanation)
            ```json
            {"tags": ["FINANCIAL"]}
            ```
            """;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DEPENDENCIES (injected by Spring)
    // ═══════════════════════════════════════════════════════════════════════════
    
    private final LLMClient client;
    private final ObjectMapper objectMapper;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PROCESS
    // ═══════════════════════════════════════════════════════════════════════════
    
    public Response process(Request request) {
        long start = System.currentTimeMillis();
        
        try {
            String prompt = buildPrompt(request);
            LLMClient.Response llmResponse = client.complete(MODEL, prompt, MAX_TOKENS);
            return parseResponse(llmResponse, start);
            
        } catch (Exception e) {
            log.error("Classification error: {}", e.getMessage());
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
    // BUILD PROMPT
    // ═══════════════════════════════════════════════════════════════════════════
    
    private String buildPrompt(Request request) {
        StringBuilder sb = new StringBuilder();
        
        sb.append(PROMPT_INTRO);
        sb.append(PROMPT_TAGS);
        sb.append(PROMPT_RULES);
        sb.append(PROMPT_FORMAT);
        
        // Context
        if (request.previousBotMessage() != null && !request.previousBotMessage().isBlank()) {
            sb.append("\n## Previous Bot Message\n```\n")
              .append(request.previousBotMessage())
              .append("\n```\n");
        } else {
            sb.append("\n## Previous Bot Message\nNone (new conversation)\n");
        }
        
        sb.append("\n## User Message\n```\n")
          .append(request.message())
          .append("\n```\n\nJSON response:");
        
        return sb.toString();
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PARSE RESPONSE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private Response parseResponse(LLMClient.Response llmResponse, long startTime) {
        try {
            String json = llmResponse.contentJson();
            JsonNode root = objectMapper.readTree(json);
            
            Set<Tag> tags = new HashSet<>();
            JsonNode tagsNode = root.path("tags");
            if (tagsNode.isArray()) {
                for (JsonNode tagNode : tagsNode) {
                    Tag tag = parseTag(tagNode.asText());
                    if (tag != null) tags.add(tag);
                }
            }
            
            if (tags.isEmpty()) {
                tags.add(Tag.FINANCIAL);  // Default
            }
            
            long latency = System.currentTimeMillis() - startTime;
            log.info("Classified: {} ({}ms, {} tokens)", tags, latency, llmResponse.totalTokens());
            
            return new Response(tags, json, latency, llmResponse.totalTokens(), null);
            
        } catch (Exception e) {
            log.error("Parse error: {}", e.getMessage());
            return new Response(
                    Set.of(Tag.FINANCIAL),
                    llmResponse.content(),
                    System.currentTimeMillis() - startTime,
                    llmResponse.totalTokens(),
                    "Parse error: " + e.getMessage()
            );
        }
    }
    
    private Tag parseTag(String tagStr) {
        try {
            return Tag.valueOf(tagStr.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown tag '{}', ignoring", tagStr);
            return null;
        }
    }
}
