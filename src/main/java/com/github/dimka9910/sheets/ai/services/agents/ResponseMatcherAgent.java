package com.github.dimka9910.sheets.ai.services.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimka9910.sheets.ai.services.llm.LLMClient;
import com.github.dimka9910.sheets.ai.services.llm.OpenAIClient;
import lombok.extern.slf4j.Slf4j;

/**
 * ResponseMatcherAgent - determines if user's message is a response to bot's message.
 * 
 * Uses gpt-4o (smart model) - better at understanding dialog context.
 */
@Slf4j
public class ResponseMatcherAgent {
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIG
    // ═══════════════════════════════════════════════════════════════════════════
    
    private static final String MODEL = "gpt-4o";
    private static final int MAX_TOKENS = 50;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // REQUEST / RESPONSE
    // ═══════════════════════════════════════════════════════════════════════════
    
    public record Request(
            String message,
            String previousBotMessage,
            boolean hasPendingResponse
    ) {}
    
    public record Response(
            boolean isResponse,
            long latencyMs,
            String errorMessage
    ) {
        public boolean isSuccess() {
            return errorMessage == null;
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PROMPT PARTS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private static final String PROMPT_PENDING_RESPONSE = """
            The bot asked a question and is waiting for an answer.
            Is the user's message a RESPONSE or REACTION to this question?
            
            YES means:
            - User answers the bot's question (provides requested info)
            - User confirms or denies what bot asked
            - User gives the value bot asked for (amount, account, currency, yes/no)
            - Vague/incomplete answers related to bot's question = YES
            - User wants to CANCEL or ABANDON (forget it, nevermind, cancel, skip)
            
            NO means:
            - User starts a completely NEW topic, ignoring the question
            - User asks unrelated question
            - User reports new financial transaction
            
            Bot asked: %s
            User replied: %s
            
            JSON (no explanation): {"answer": "YES"} or {"answer": "NO"}
            """;
    
    private static final String PROMPT_CORRECTION = """
            The bot just confirmed or recorded something.
            Is the user's message a CORRECTION or MODIFICATION of what bot did?
            
            YES means:
            - User disagrees with what bot recorded (wrong, no, not that)
            - User wants to change/fix something (fix it, change to, not X but Y)
            - User says it was wrong amount/account/category
            - User wants to undo or cancel
            - User expresses dissatisfaction with bot's response
            
            NO means:
            - User starts a NEW transaction (even if similar to previous)
            - User says something unrelated to what bot did
            - User accepts and moves on to something new
            
            Bot said: %s
            User said: %s
            
            JSON (no explanation): {"answer": "YES"} or {"answer": "NO"}
            """;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DEPENDENCIES
    // ═══════════════════════════════════════════════════════════════════════════
    
    private final LLMClient client;
    private final ObjectMapper objectMapper;
    
    public ResponseMatcherAgent() {
        this.client = OpenAIClient.getInstance();
        this.objectMapper = new ObjectMapper();
    }
    
    public ResponseMatcherAgent(LLMClient client) {
        this.client = client;
        this.objectMapper = new ObjectMapper();
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PROCESS
    // ═══════════════════════════════════════════════════════════════════════════
    
    public Response process(Request request) {
        long start = System.currentTimeMillis();
        
        // No previous message → definitely NO
        if (request.previousBotMessage() == null || request.previousBotMessage().isBlank()) {
            return new Response(false, 0, null);
        }
        
        try {
            String prompt = buildPrompt(request);
            LLMClient.Response llmResponse = client.complete(MODEL, prompt, MAX_TOKENS);
            return parseResponse(llmResponse, request.hasPendingResponse(), request.previousBotMessage(), start);
            
        } catch (Exception e) {
            log.error("Response matching error: {}", e.getMessage());
            // Fallback: if pending response expected and bot asked question → assume YES
            boolean fallback = request.hasPendingResponse() && request.previousBotMessage().contains("?");
            return new Response(fallback, System.currentTimeMillis() - start, e.getMessage());
        }
    }
    
    /**
     * Convenience method for direct call.
     */
    public Response match(String message, String previousBotMessage, boolean hasPendingResponse) {
        return process(new Request(message, previousBotMessage, hasPendingResponse));
    }
    
    public Response match(String message, String previousBotMessage) {
        return match(message, previousBotMessage, false);
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // BUILD PROMPT
    // ═══════════════════════════════════════════════════════════════════════════
    
    private String buildPrompt(Request request) {
        if (request.hasPendingResponse()) {
            return PROMPT_PENDING_RESPONSE.formatted(request.previousBotMessage(), request.message());
        } else {
            return PROMPT_CORRECTION.formatted(request.previousBotMessage(), request.message());
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PARSE RESPONSE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private Response parseResponse(LLMClient.Response llmResponse, boolean hasPending, 
                                   String prevBotMsg, long startTime) {
        try {
            String json = llmResponse.contentJson();
            JsonNode root = objectMapper.readTree(json);
            String answer = root.path("answer").asText("NO").toUpperCase();
            
            boolean isResponse = "YES".equals(answer);
            long latency = System.currentTimeMillis() - startTime;
            
            log.info("Response matched: {} (pending={}, {}ms)", isResponse, hasPending, latency);
            
            return new Response(isResponse, latency, null);
            
        } catch (Exception e) {
            log.error("Parse error: {}", e.getMessage());
            boolean fallback = hasPending && prevBotMsg.contains("?");
            return new Response(fallback, System.currentTimeMillis() - startTime, "Parse error: " + e.getMessage());
        }
    }
}
