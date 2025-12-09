package com.github.dimka9910.sheets.ai.services.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ThirdPartyMatcherAgent - determines if a person mentioned in message is a linked user
 * or just a comment/description.
 * 
 * Examples:
 * - "transferred to girlfriend 500" + linkedUsers=[KIKI (girlfriend)] → LINKED_USER: KIKI
 * - "transferred to mom for a gift" + linkedUsers=[KIKI] → COMMENT (mom not in linked users)
 * - "coffee with friend" + linkedUsers=[] → COMMENT
 * - "bought for Kiki" + linkedUsers=[KIKI] → LINKED_USER: KIKI
 */
public class ThirdPartyMatcherAgent {
    private static final Logger logger = LoggerFactory.getLogger(ThirdPartyMatcherAgent.class);
    
    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String MODEL = "gpt-4o-mini";  // Fast model, task is not complex
    private static final int MAX_TOKENS = 100;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TYPES
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Match type - is the mentioned person a linked user?
     */
    public enum MatchType {
        LINKED_USER,  // Person matches a linked user → use their context
        COMMENT       // Just a description/comment → no special handling
    }
    
    /**
     * Result of third party matching.
     */
    public record MatchResult(
            MatchType matchType,
            String matchedUserId,   // If LINKED_USER, which one (null for COMMENT)
            String matchedUserName, // Human-readable name
            String reasoning,       // Why this match (for debug)
            long latencyMs
    ) {
        public boolean isLinkedUser() {
            return matchType == MatchType.LINKED_USER;
        }
        
        public boolean isComment() {
            return matchType == MatchType.COMMENT;
        }
    }
    
    /**
     * Linked user info for matching.
     */
    public record LinkedUser(
            String userId,
            String name,           // Display name (KIKI, DIMA)
            List<String> aliases   // Possible references: girlfriend, девушка, her, она, etc.
    ) {}
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONSTRUCTORS
    // ═══════════════════════════════════════════════════════════════════════════
    
    public ThirdPartyMatcherAgent() {
        this.apiKey = System.getProperty("OPENAI_API_KEY", System.getenv("OPENAI_API_KEY"));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
    }
    
    public ThirdPartyMatcherAgent(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Determine if a person mentioned in message is a linked user.
     * 
     * @param message User's message containing person reference
     * @param linkedUsers List of linked users with their aliases
     * @return MatchResult with match type and matched user (if any)
     */
    public MatchResult match(String message, List<LinkedUser> linkedUsers) {
        long start = System.currentTimeMillis();
        
        // No linked users → always COMMENT
        if (linkedUsers == null || linkedUsers.isEmpty()) {
            return new MatchResult(MatchType.COMMENT, null, null, "No linked users", 0);
        }
        
        try {
            String prompt = buildPrompt(message, linkedUsers);
            JsonNode response = callOpenAI(prompt);
            
            String content = response.path("choices").get(0).path("message").path("content").asText().trim();
            String json = extractJson(content);
            JsonNode root = objectMapper.readTree(json);
            
            String matchType = root.path("match").asText("COMMENT").toUpperCase();
            String matchedUser = root.path("user").asText(null);
            String reasoning = root.path("reasoning").asText("");
            
            long latency = System.currentTimeMillis() - start;
            
            if ("LINKED_USER".equals(matchType) && matchedUser != null && !matchedUser.isBlank()) {
                // Find the matched linked user
                LinkedUser matched = findLinkedUser(matchedUser, linkedUsers);
                if (matched != null) {
                    logger.info("Third party matched: {} → {} ({}ms)", message, matched.name(), latency);
                    return new MatchResult(MatchType.LINKED_USER, matched.userId(), matched.name(), reasoning, latency);
                }
            }
            
            logger.info("Third party: COMMENT ({}ms)", latency);
            return new MatchResult(MatchType.COMMENT, null, null, reasoning, latency);
            
        } catch (Exception e) {
            logger.error("Third party matching error: {}", e.getMessage());
            return new MatchResult(MatchType.COMMENT, null, null, "Error: " + e.getMessage(), 
                    System.currentTimeMillis() - start);
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PROMPT
    // ═══════════════════════════════════════════════════════════════════════════
    
    private String buildPrompt(String message, List<LinkedUser> linkedUsers) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("""
            The user's message mentions another person. Determine if this person is one of the LINKED USERS
            (partner, spouse, shared finance person) or just a COMMENT (casual mention, not for accounting).
            
            ## LINKED USERS (these are known people for shared finances):
            """);
        
        for (LinkedUser user : linkedUsers) {
            sb.append("- **").append(user.name()).append("**");
            if (user.aliases() != null && !user.aliases().isEmpty()) {
                sb.append(" (also known as: ").append(String.join(", ", user.aliases())).append(")");
            }
            sb.append("\n");
        }
        
        sb.append("""
            
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
            
            ## Message:
            "%s"
            
            ## Response (JSON only):
            If LINKED_USER: {"match": "LINKED_USER", "user": "USER_NAME", "reasoning": "brief explanation"}
            If COMMENT: {"match": "COMMENT", "reasoning": "brief explanation"}
            """.formatted(message));
        
        return sb.toString();
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITIES
    // ═══════════════════════════════════════════════════════════════════════════
    
    private LinkedUser findLinkedUser(String name, List<LinkedUser> linkedUsers) {
        String nameLower = name.toLowerCase().trim();
        for (LinkedUser user : linkedUsers) {
            if (user.name().toLowerCase().equals(nameLower)) {
                return user;
            }
            if (user.userId().toLowerCase().equals(nameLower)) {
                return user;
            }
            if (user.aliases() != null) {
                for (String alias : user.aliases()) {
                    if (alias.toLowerCase().equals(nameLower)) {
                        return user;
                    }
                }
            }
        }
        return null;
    }
    
    private String extractJson(String content) {
        int start = content.indexOf("{");
        int end = content.lastIndexOf("}");
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return content;
    }
    
    private JsonNode callOpenAI(String prompt) throws Exception {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", MODEL);
        requestBody.put("max_tokens", MAX_TOKENS);
        requestBody.put("temperature", 0.1);
        requestBody.put("messages", List.of(
                Map.of("role", "user", "content", prompt)
        ));
        
        String jsonBody = objectMapper.writeValueAsString(requestBody);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OPENAI_API_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new RuntimeException("OpenAI API error: " + response.statusCode() + " - " + response.body());
        }
        
        return objectMapper.readTree(response.body());
    }
}

