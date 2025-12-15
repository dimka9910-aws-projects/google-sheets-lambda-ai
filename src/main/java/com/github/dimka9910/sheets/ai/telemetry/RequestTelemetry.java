package com.github.dimka9910.sheets.ai.telemetry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Collects telemetry data for a single user request.
 * Thread-safe for parallel agent calls.
 * 
 * Usage:
 * ```java
 * RequestTelemetry telemetry = new RequestTelemetry(userId, message);
 * telemetry.recordAgent("ClassifierAgent", "gpt-4o-mini", tags, 150, 45);
 * telemetry.recordAgent("MainAgent", "gpt-5-mini", "OK: 2 action(s)", 800, 250);
 * // ... at the end
 * String report = telemetry.formatForTelegram();
 * ```
 */
public class RequestTelemetry {
    
    private final String requestId;
    private final String userId;
    private final String userMessage;
    private final Instant startTime;
    private final List<AgentCall> agentCalls;
    
    private String finalResult;
    private boolean success;
    private String errorMessage;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INNER CLASSES
    // ═══════════════════════════════════════════════════════════════════════════
    
    public record AgentCall(
        String agentName,
        String model,
        String result,
        long latencyMs,
        int tokensUsed,
        Instant timestamp
    ) {
        public String format() {
            StringBuilder sb = new StringBuilder();
            sb.append("• ").append(agentName);
            if (model != null) {
                sb.append(" (").append(model).append(")");
            }
            sb.append("\n");
            sb.append("  ⏱ ").append(latencyMs).append("ms");
            if (tokensUsed > 0) {
                sb.append(" | 🔢 ").append(tokensUsed).append(" tokens");
            }
            sb.append("\n");
            sb.append("  → ").append(truncate(result, 100));
            return sb.toString();
        }
        
        private static String truncate(String s, int max) {
            if (s == null) return "null";
            return s.length() <= max ? s : s.substring(0, max) + "...";
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONSTRUCTOR
    // ═══════════════════════════════════════════════════════════════════════════
    
    public RequestTelemetry(String userId, String userMessage) {
        this.requestId = generateRequestId();
        this.userId = userId;
        this.userMessage = userMessage;
        this.startTime = Instant.now();
        this.agentCalls = new CopyOnWriteArrayList<>(); // Thread-safe for parallel calls
        this.success = true;
    }
    
    private static String generateRequestId() {
        return "req_" + System.currentTimeMillis() % 100000;
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // RECORDING METHODS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Record an agent call with all details.
     */
    public void recordAgent(String agentName, String model, String result, long latencyMs, int tokensUsed) {
        agentCalls.add(new AgentCall(agentName, model, result, latencyMs, tokensUsed, Instant.now()));
    }
    
    /**
     * Record agent call without model info.
     */
    public void recordAgent(String agentName, String result, long latencyMs, int tokensUsed) {
        recordAgent(agentName, null, result, latencyMs, tokensUsed);
    }
    
    /**
     * Record agent call without token info.
     */
    public void recordAgent(String agentName, String model, String result, long latencyMs) {
        recordAgent(agentName, model, result, latencyMs, 0);
    }
    
    /**
     * Set the final result of the request.
     */
    public void setFinalResult(String result) {
        this.finalResult = result;
    }
    
    /**
     * Mark request as failed.
     */
    public void setError(String errorMessage) {
        this.success = false;
        this.errorMessage = errorMessage;
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // FORMATTING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Format telemetry for Telegram message (human-readable).
     */
    public String formatForTelegram() {
        StringBuilder sb = new StringBuilder();
        
        // Header
        sb.append("📊 TELEMETRY [").append(requestId).append("]\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━\n");
        
        // User info
        sb.append("👤 User: ").append(userId).append("\n");
        sb.append("💬 \"").append(truncate(userMessage, 50)).append("\"\n\n");
        
        // Agent calls
        sb.append("🤖 AGENTS:\n");
        for (AgentCall call : agentCalls) {
            sb.append(call.format()).append("\n");
        }
        
        // Summary
        sb.append("━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("📈 SUMMARY:\n");
        sb.append("  Total time: ").append(getTotalLatencyMs()).append("ms\n");
        sb.append("  Total tokens: ").append(getTotalTokens()).append("\n");
        sb.append("  Agents called: ").append(agentCalls.size()).append("\n");
        
        if (!success) {
            sb.append("  ❌ ERROR: ").append(errorMessage).append("\n");
        } else {
            sb.append("  ✅ Success\n");
        }
        
        // Final result preview
        if (finalResult != null) {
            sb.append("\n📤 Result: ").append(truncate(finalResult, 100));
        }
        
        return sb.toString();
    }
    
    /**
     * Format as compact one-liner for logs.
     */
    public String formatCompact() {
        return String.format("[%s] user=%s agents=%d time=%dms tokens=%d %s",
                requestId, userId, agentCalls.size(), getTotalLatencyMs(), 
                getTotalTokens(), success ? "OK" : "ERR:" + errorMessage);
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // GETTERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    public long getTotalLatencyMs() {
        return agentCalls.stream().mapToLong(AgentCall::latencyMs).sum();
    }
    
    public int getTotalTokens() {
        return agentCalls.stream().mapToInt(AgentCall::tokensUsed).sum();
    }
    
    public String getRequestId() {
        return requestId;
    }
    
    public List<AgentCall> getAgentCalls() {
        return new ArrayList<>(agentCalls);
    }
    
    public boolean isSuccess() {
        return success;
    }
    
    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}

