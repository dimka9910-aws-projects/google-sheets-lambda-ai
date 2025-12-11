package com.github.dimka9910.sheets.ai.services.llm;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * LLM Client interface - abstraction for any LLM provider.
 * 
 * Implementations:
 * - OpenAIClient (gpt-4o, gpt-4o-mini, gpt-5-mini, o1)
 * - Future: AnthropicClient, LocalLLMClient, etc.
 */
public interface LLMClient {
    
    /**
     * Response from LLM completion.
     */
    record Response(
            String content,
            int totalTokens,
            int reasoningTokens,
            JsonNode raw
    ) {
        /**
         * Extract JSON from response content (handles markdown code blocks).
         */
        public String contentJson() {
            if (content == null) return "{}";
            int start = content.indexOf("{");
            int end = content.lastIndexOf("}");
            if (start >= 0 && end > start) {
                return content.substring(start, end + 1);
            }
            return content;
        }
    }
    
    /**
     * Standard completion (for models like gpt-4o, gpt-4o-mini, Claude).
     * 
     * @param model Model name
     * @param prompt User prompt
     * @param maxTokens Max tokens in response
     * @param temperature Temperature (0.0 - 2.0), lower = more deterministic
     * @return Response with content and token usage
     */
    Response complete(String model, String prompt, int maxTokens, double temperature) throws LLMException;
    
    /**
     * Standard completion with default temperature (0.1).
     */
    default Response complete(String model, String prompt, int maxTokens) throws LLMException {
        return complete(model, prompt, maxTokens, 0.1);
    }
    
    /**
     * Reasoning model completion (for models like gpt-5-mini, o1, o1-mini).
     * Uses max_completion_tokens, no temperature parameter.
     * 
     * @param model Model name
     * @param prompt User prompt
     * @param maxCompletionTokens Max completion tokens (including reasoning)
     * @return Response with content, total tokens, and reasoning tokens
     */
    Response completeWithReasoning(String model, String prompt, int maxCompletionTokens) throws LLMException;
    
    /**
     * LLM API exception.
     */
    class LLMException extends RuntimeException {
        private final int statusCode;
        private final String responseBody;
        
        public LLMException(String message) {
            super(message);
            this.statusCode = 0;
            this.responseBody = null;
        }
        
        public LLMException(int statusCode, String responseBody) {
            super("LLM API error: " + statusCode);
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }
        
        public LLMException(String message, Throwable cause) {
            super(message, cause);
            this.statusCode = 0;
            this.responseBody = null;
        }
        
        public int getStatusCode() { return statusCode; }
        public String getResponseBody() { return responseBody; }
    }
}

