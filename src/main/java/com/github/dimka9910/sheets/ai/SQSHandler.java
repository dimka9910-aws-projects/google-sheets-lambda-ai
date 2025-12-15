package com.github.dimka9910.sheets.ai;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimka9910.sheets.ai.dto.ChatRequest;
import com.github.dimka9910.sheets.ai.dto.ChatResponse;
import com.github.dimka9910.sheets.ai.dto.user.UserEntity;
import com.github.dimka9910.sheets.ai.services.ChatCommandService;
import com.github.dimka9910.sheets.ai.services.SQSPublisher;
import com.github.dimka9910.sheets.ai.services.UserEntityService;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * SQS Lambda handler.
 * 
 * Flow: Telegram Bot → SQS Requests → this handler → ChatCommandService → SQS Responses
 *       Telegram Bot слушает Response Queue и отправляет в Telegram API.
 * 
 * Resolves telegramUserId → userName before processing.
 */
@Slf4j
public class SQSHandler implements RequestHandler<SQSEvent, Void> {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatCommandService chatCommandService;
    private final SQSPublisher sqsPublisher;
    private final UserEntityService userContextService;

    public SQSHandler() {
        this.userContextService = new UserEntityService();
        this.chatCommandService = new ChatCommandService(userContextService);
        this.sqsPublisher = new SQSPublisher();
    }

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        log.info("Received {} SQS messages", event.getRecords().size());

        for (SQSEvent.SQSMessage message : event.getRecords()) {
            try {
                processMessage(message);
            } catch (Exception e) {
                log.error("Error processing SQS message: {}", e.getMessage(), e);
            }
        }

        return null;
    }

    private void processMessage(SQSEvent.SQSMessage message) throws Exception {
        String body = message.getBody();
        log.info("Processing SQS message: {}", body);

        ChatRequest chatRequest = objectMapper.readValue(body, ChatRequest.class);
        
        // Resolve telegramUserId → userName
        String telegramUserId = chatRequest.getTelegramUserId();
        if (telegramUserId != null && chatRequest.getUserName() == null) {
            Optional<UserEntity> userContext = userContextService.getByTelegramId(telegramUserId);
            if (userContext.isPresent()) {
                chatRequest.setUserName(userContext.get().getUserName());
                log.info("Resolved telegramUserId {} → userName {}", telegramUserId, chatRequest.getUserName());
            } else {
                // New user from Telegram — userName not set yet
                log.info("New Telegram user {}, userName not yet assigned", telegramUserId);
            }
        }
        
        ChatResponse response = chatCommandService.processCommand(chatRequest);
        
        // Устанавливаем chatId для ответа
        String chatId = chatRequest.getResponseChatId();
        response.setChatId(chatId);
        
        // Отправляем ответ через SQS Response Queue → Telegram Bot отправит в Telegram
        sqsPublisher.sendResponse(response);
        log.info("Response sent to Response Queue for chat {}", chatId);
    }
}
