package com.github.dimka9910.sheets.ai;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimka9910.sheets.ai.dto.ChatRequest;
import com.github.dimka9910.sheets.ai.dto.ChatResponse;
import com.github.dimka9910.sheets.ai.services.ChatCommandService;
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

    public SQSHandler() {
        UserContextService userContextService = new UserContextService();
        this.chatCommandService = new ChatCommandService(userContextService);
        this.telegramSender = new TelegramSender();
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
        
        // Обрабатываем команду
        ChatResponse response = chatCommandService.processCommand(chatRequest);
        
        // Отправляем ответ напрямую в Telegram
        String chatId = chatRequest.getChatId();
        if (chatId == null) {
            chatId = chatRequest.getUserId(); // fallback на userId
        }
        
        if (telegramSender.isConfigured()) {
            telegramSender.sendMessage(chatId, response.getMessage());
            log.info("Response sent to Telegram chat {}", chatId);
        } else {
            log.warn("Telegram not configured, response not sent: {}", response.getMessage());
        }
    }
}

