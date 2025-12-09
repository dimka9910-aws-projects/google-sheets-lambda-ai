package com.github.dimka9910.sheets.ai;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimka9910.sheets.ai.dto.ChatRequest;
import com.github.dimka9910.sheets.ai.dto.ChatResponse;
import com.github.dimka9910.sheets.ai.services.ChatCommandService;
import com.github.dimka9910.sheets.ai.services.Orchestrator;
import com.github.dimka9910.sheets.ai.services.TelegramSender;
import com.github.dimka9910.sheets.ai.services.UserContextService;
import lombok.extern.slf4j.Slf4j;

/**
 * Обрабатывает SQS события от Telegram Bot.
 * Асинхронная архитектура: Telegram Bot -> SQS -> этот handler -> Telegram API
 */
@Slf4j
public class SQSHandler implements RequestHandler<SQSEvent, Void> {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatCommandService chatCommandService;
    private final TelegramSender telegramSender;
    private final Orchestrator orchestrator;
    
    // Debug mode: messages starting with ">" are treated as classifier tests
    private static final boolean DEBUG_CLASSIFIER = "dev".equalsIgnoreCase(
            System.getenv().getOrDefault("ENVIRONMENT", "prod"));

    public SQSHandler() {
        UserContextService userContextService = new UserContextService();
        this.chatCommandService = new ChatCommandService(userContextService);
        this.telegramSender = new TelegramSender();
        this.orchestrator = new Orchestrator();
    }

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        log.info("Received {} SQS messages", event.getRecords().size());

        for (SQSEvent.SQSMessage message : event.getRecords()) {
            try {
                processMessage(message);
            } catch (Exception e) {
                log.error("Error processing SQS message: {}", e.getMessage(), e);
                // Не бросаем исключение чтобы не retry всю batch
            }
        }

        return null;
    }

    private void processMessage(SQSEvent.SQSMessage message) throws Exception {
        String body = message.getBody();
        log.info("Processing SQS message: {}", body);

        // Парсим запрос
        ChatRequest chatRequest = objectMapper.readValue(body, ChatRequest.class);
        
        String chatId = chatRequest.getChatId();
        if (chatId == null) {
            chatId = chatRequest.getUserId();
        }
        
        String userMessage = chatRequest.getMessage();
        
        // DEBUG MODE: Test classifier with "> prev\nreply" format
        if (DEBUG_CLASSIFIER && userMessage != null && userMessage.startsWith(">")) {
            String debugResponse = handleDebugClassifier(userMessage);
            if (telegramSender.isConfigured()) {
                telegramSender.sendMessage(chatId, debugResponse);
            }
            return;
        }
        
        // Normal flow
        ChatResponse response = chatCommandService.processCommand(chatRequest);
        
        if (telegramSender.isConfigured()) {
            telegramSender.sendMessage(chatId, response.getMessage());
            log.info("Response sent to Telegram chat {}", chatId);
        } else {
            log.warn("Telegram not configured, response not sent: {}", response.getMessage());
        }
    }
    
    /**
     * DEBUG: Test classifier with format:
     * 
     * > previous bot message
     * user reply
     * 
     * Or just:
     * > single message (no previous context)
     */
    private String handleDebugClassifier(String input) {
        try {
            String previousBotMessage = null;
            String userMessage;
            
            // Parse format: "> prev\nreply" or just "> message"
            String content = input.substring(1).trim(); // Remove ">"
            
            if (content.contains("\n")) {
                // Two parts: first line is previous, rest is current
                int newlineIndex = content.indexOf("\n");
                previousBotMessage = content.substring(0, newlineIndex).trim();
                userMessage = content.substring(newlineIndex + 1).trim();
            } else {
                // Single message, no previous context
                userMessage = content;
            }
            
            log.info("DEBUG Classifier: prev='{}', msg='{}'", previousBotMessage, userMessage);
            
            var result = orchestrator.process(userMessage, previousBotMessage);
            
            StringBuilder sb = new StringBuilder();
            sb.append("🔍 CLASSIFIER\n\n");
            
            if (previousBotMessage != null) {
                sb.append("bot: ").append(previousBotMessage).append("\n");
            }
            sb.append("user: ").append(userMessage).append("\n\n");
            
            sb.append("model: ").append(result.model()).append("\n");
            sb.append("⏱ ").append(result.totalLatencyMs()).append("ms\n\n");
            
            sb.append(result.rawJson());
            
            return sb.toString();
            
        } catch (Exception e) {
            log.error("Debug classifier error: {}", e.getMessage(), e);
            return "❌ Error: " + e.getMessage();
        }
    }
}

