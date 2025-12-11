package com.github.dimka9910.sheets.ai.services.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimka9910.sheets.ai.dto.LinkedUserEntry;
import com.github.dimka9910.sheets.ai.services.llm.LLMClient;
import com.github.dimka9910.sheets.ai.services.llm.OpenAIClient;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * ThirdPartyMatcherAgent - determines if a person mentioned in message is a linked user.
 * 
 * Uses gpt-4o-mini (fast model).
 */
@Slf4j
public class ThirdPartyMatcherAgent {
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIG
    // ═══════════════════════════════════════════════════════════════════════════
    
    private static final String MODEL = "gpt-4o-mini";
    private static final int MAX_TOKENS = 100;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // REQUEST / RESPONSE
    // ═══════════════════════════════════════════════════════════════════════════
    
    public record Request(
            String message,
            List<LinkedUserEntry> linkedUsers
    ) {}
    
    public record Response(
            MatchType matchType,
            String matchedUserName,  // userName of matched linked user
            String matchedDisplayName,
            String reasoning,
            long latencyMs,
            String errorMessage
    ) {
        public boolean isSuccess() {
            return errorMessage == null;
        }
        
        public boolean isLinkedUser() {
            return matchType == MatchType.LINKED_USER;
        }
        
        public boolean isComment() {
            return matchType == MatchType.COMMENT;
        }
    }
    
    public enum MatchType {
        LINKED_USER,  // Person matches a linked user
        COMMENT       // Just a description/comment
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PROMPT PARTS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private static final String PROMPT_INTRO = """
            The user's message mentions another person. Determine if this person is one of the LINKED USERS
            (partner, spouse, shared finance person) or just a COMMENT (casual mention, not for accounting).
            """;
    
    private static final String PROMPT_RULES = """
            
            ## Rules:
            
            **LINKED_USER** - return this when:
            - User explicitly names a linked user (by name or alias)
            - User uses relationship words that match linked user (girlfriend, boyfriend, wife, husband, partner)
            - User says "her", "him", "she", "he" and context implies linked user
            - Financial operation INVOLVES the linked user (transfer TO them, expense FOR them)
            
            **COMMENT** - return this when:
            - Person mentioned is NOT in linked users list (mom, dad, friend, colleague, stranger)
            - Person is just mentioned as description/comment, not financial participant
            - "bought coffee with a friend" - friend is description, not linked user
            - "gift for mom" - mom is not a linked user, just description
            - No person is actually mentioned
            """;
    
    private static final String PROMPT_RESPONSE_FORMAT = """
            
            ## Response (JSON only):
            If LINKED_USER: {"match": "LINKED_USER", "user": "USER_NAME", "reasoning": "brief explanation"}
            If COMMENT: {"match": "COMMENT", "reasoning": "brief explanation"}
            """;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // FIELDS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private final LLMClient llmClient;
    private final ObjectMapper objectMapper;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONSTRUCTORS
    // ═══════════════════════════════════════════════════════════════════════════
    
    public ThirdPartyMatcherAgent() {
        this.llmClient = OpenAIClient.getInstance();
        this.objectMapper = new ObjectMapper();
    }
    
    public ThirdPartyMatcherAgent(LLMClient llmClient) {
        this.llmClient = llmClient;
        this.objectMapper = new ObjectMapper();
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════
    
    public Response match(String message, List<LinkedUserEntry> linkedUsers) {
        return process(new Request(message, linkedUsers));
    }
    
    public Response process(Request request) {
        long startTime = System.currentTimeMillis();
        
        List<LinkedUserEntry> linkedUsers = request.linkedUsers();
        
        // No linked users → always COMMENT
        if (linkedUsers == null || linkedUsers.isEmpty()) {
            return new Response(MatchType.COMMENT, null, null, "No linked users defined",
                    System.currentTimeMillis() - startTime, null);
        }
        
        try {
            String prompt = buildPrompt(request);
            LLMClient.Response llmResponse = llmClient.complete(MODEL, prompt, MAX_TOKENS);
            
            JsonNode root = objectMapper.readTree(llmResponse.contentJson());
            
            String matchType = root.path("match").asText("COMMENT").toUpperCase();
            String matchedUser = root.path("user").asText(null);
            String reasoning = root.path("reasoning").asText("");
            
            long latency = System.currentTimeMillis() - startTime;
            
            if ("LINKED_USER".equals(matchType) && matchedUser != null && !matchedUser.isBlank()) {
                LinkedUserEntry matched = findLinkedUser(matchedUser, linkedUsers);
                if (matched != null) {
                    log.info("Third party matched: {} ({}ms)", matched.getName(), latency);
                    return new Response(MatchType.LINKED_USER, matched.getUserName(), matched.getName(), 
                            reasoning, latency, null);
                }
            }
            
            log.info("Third party: COMMENT ({}ms)", latency);
            return new Response(MatchType.COMMENT, null, null, reasoning, latency, null);
            
        } catch (Exception e) {
            log.error("Parse error: {}", e.getMessage());
            return new Response(MatchType.COMMENT, null, null, "Parse error",
                    System.currentTimeMillis() - startTime, e.getMessage());
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PROMPT BUILDER
    // ═══════════════════════════════════════════════════════════════════════════
    
    private String buildPrompt(Request request) {
        StringBuilder sb = new StringBuilder();
        sb.append(PROMPT_INTRO);
        
        // Linked users list
        sb.append("\n## LINKED USERS (these are known people for shared finances):\n");
        for (LinkedUserEntry user : request.linkedUsers()) {
            sb.append("- **").append(user.getName()).append("**");
            if (user.getAliases() != null && !user.getAliases().isEmpty()) {
                sb.append(" (also known as: ").append(String.join(", ", user.getAliases())).append(")");
            }
            sb.append("\n");
        }
        
        sb.append(PROMPT_RULES);
        sb.append(PROMPT_RESPONSE_FORMAT);
        
        // Message
        sb.append("\n## Message:\n\"").append(request.message()).append("\"\n");
        
        return sb.toString();
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITIES
    // ═══════════════════════════════════════════════════════════════════════════
    
    private LinkedUserEntry findLinkedUser(String name, List<LinkedUserEntry> linkedUsers) {
        String nameLower = name.toLowerCase().trim();
        for (LinkedUserEntry user : linkedUsers) {
            if (user.getName() != null && user.getName().toLowerCase().equals(nameLower)) {
                return user;
            }
            if (user.getUserName() != null && user.getUserName().toLowerCase().equals(nameLower)) {
                return user;
            }
            if (user.getAliases() != null) {
                for (String alias : user.getAliases()) {
                    if (alias.toLowerCase().equals(nameLower)) {
                        return user;
                    }
                }
            }
        }
        return null;
    }
}
