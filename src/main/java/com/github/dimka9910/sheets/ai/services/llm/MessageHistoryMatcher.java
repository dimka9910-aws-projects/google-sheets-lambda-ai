package com.github.dimka9910.sheets.ai.services.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimka9910.sheets.ai.dto.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Находит на какое сообщение из истории был ответ пользователя.
 * Вызывается когда MessageClassifier возвращает NEED_HISTORY.
 */
public class MessageHistoryMatcher {
    
    private static final Logger logger = LoggerFactory.getLogger(MessageHistoryMatcher.class);
    
    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String MODEL = "gpt-4o-mini"; // Быстрая модель, задача несложная
    private static final int MAX_TOKENS = 100;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    public MessageHistoryMatcher() {
        this.apiKey = System.getProperty("OPENAI_API_KEY", System.getenv("OPENAI_API_KEY"));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
    }
    
    public MessageHistoryMatcher(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * Один кандидат с вероятностью.
     */
    public record MatchCandidate(
            String messageId,
            double probability,  // 0.0 - 1.0
            String reasoning
    ) {}
    
    /**
     * Результат поиска - может быть несколько кандидатов с разными вероятностями.
     */
    public record MatchResult(
            List<MatchCandidate> candidates,  // Отсортированы по probability DESC
            long latencyMs,
            int tokensUsed
    ) {
        public boolean found() {
            return candidates != null && !candidates.isEmpty();
        }
        
        /** Лучший кандидат (с наибольшей вероятностью). */
        public MatchCandidate best() {
            return found() ? candidates.get(0) : null;
        }
        
        /** ID лучшего кандидата. */
        public String bestMessageId() {
            return found() ? candidates.get(0).messageId() : null;
        }
    }
    
    /**
     * Найти на какое сообщение из истории был ответ.
     * Может вернуть несколько кандидатов с разными вероятностями.
     * 
     * @param userMessage текущее сообщение пользователя
     * @param history последние сообщения (от старых к новым)
     * @return результат с кандидатами отсортированными по probability DESC
     */
    public MatchResult findMatch(String userMessage, List<Message> history) {
        long startTime = System.currentTimeMillis();
        
        if (history == null || history.isEmpty()) {
            return new MatchResult(List.of(), 0, 0);
        }
        
        try {
            String prompt = buildPrompt(userMessage, history);
            JsonNode response = callOpenAI(prompt);
            
            int tokens = response.path("usage").path("total_tokens").asInt(0);
            String content = response.path("choices").get(0).path("message").path("content").asText().trim();
            String json = extractJson(content);
            
            JsonNode root = objectMapper.readTree(json);
            List<MatchCandidate> candidates = new ArrayList<>();
            
            JsonNode matchesNode = root.path("matches");
            if (matchesNode.isArray()) {
                for (JsonNode match : matchesNode) {
                    String msgId = match.path("messageId").asText(null);
                    double prob = match.path("probability").asDouble(0.0);
                    String reasoning = match.path("reasoning").asText("");
                    
                    // Проверяем что ID существует в истории
                    if (msgId != null && !msgId.equals("null") && !msgId.isBlank()) {
                        final String idToCheck = msgId;
                        boolean exists = history.stream()
                                .anyMatch(m -> idToCheck.equals(m.getMessageId()));
                        if (exists) {
                            candidates.add(new MatchCandidate(msgId, prob, reasoning));
                        } else {
                            logger.warn("Model returned non-existent messageId: {}", msgId);
                        }
                    }
                }
            }
            
            // Сортируем по вероятности (от большей к меньшей)
            candidates.sort(Comparator.comparingDouble(MatchCandidate::probability).reversed());
            
            long latency = System.currentTimeMillis() - startTime;
            logger.info("HistoryMatcher: found {} candidates, best={}, latency={}ms", 
                    candidates.size(), 
                    candidates.isEmpty() ? "none" : candidates.get(0).messageId(),
                    latency);
            
            return new MatchResult(candidates, latency, tokens);
            
        } catch (Exception e) {
            logger.error("History matching failed: {}", e.getMessage(), e);
            return new MatchResult(List.of(), System.currentTimeMillis() - startTime, 0);
        }
    }
    
    private String buildPrompt(String userMessage, List<Message> history) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("""
            The user's message appears to be a response to an EARLIER message in the conversation,
            not the most recent one. Find which message the user is responding to.
            
            ## Conversation History (oldest first)
            
            """);
        
        for (Message msg : history) {
            String role = msg.isUser() ? "USER" : "BOT";
            sb.append(String.format("[%s] id=%s: %s\n", role, msg.getMessageId(), msg.getContent()));
        }
        
        sb.append("""
            
            ## Current User Message
            """);
        sb.append(userMessage);
        
        sb.append("""
            
            
            ## Task
            Which message(s) from history is the user most likely responding to?
            Look for:
            - Corrections (user changes a number/detail from a previous bot message)
            - References ("that one", "the coffee", "like I said")
            - Context that only makes sense with a specific earlier message
            
            You can return MULTIPLE candidates if unsure, with probability weights (0.0-1.0).
            Probabilities should sum to ~1.0.
            
            ## Response (JSON only)
            {
              "matches": [
                {"messageId": "123", "probability": 0.7, "reasoning": "most likely - user corrects amount"},
                {"messageId": "120", "probability": 0.3, "reasoning": "possible - also mentioned amount"}
              ]
            }
            
            If confident in one match:
            {"matches": [{"messageId": "123", "probability": 1.0, "reasoning": "clear match"}]}
            
            If no match found:
            {"matches": []}
            """);
        
        return sb.toString();
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
        Map<String, Object> requestBody = Map.of(
                "model", MODEL,
                "max_tokens", MAX_TOKENS,
                "temperature", 0.1,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );
        
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

