package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.dto.telegram.ChatRequest;
import com.github.dimka9910.sheets.ai.dto.telegram.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Spring Service - thin coordinator for chat commands.
 * 
 * Flow:
 * 1. Resolve user context from telegramUserId (via UserEntityService)
 * 2. Check admin commands
 * 3. Delegate to Orchestrator for processing
 * 4. Send response via SQS
 * 
 * All business logic is delegated to specialized services.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqsMessageProcessor {

    private final UserEntityService userEntityService;
    private final AdminCommandHandler adminHandler;
    private final Orchestrator orchestrator;
    private final SQSPublisher sqsPublisher;

    /**
     * Process chat command - main entry point.
     */
    public ChatResponse processCommand(ChatRequest request) {
        String telegramUserId = request.getTelegramUserId();
        String message = request.getMessage() != null ? request.getMessage().trim() : "";
        
        log.info("Processing: telegramUserId={}, message={}", telegramUserId, message);

        // 1. Resolve user context with linked users
        var userContextOpt = userEntityService.resolveWithLinkedUsers(telegramUserId);
        if (userContextOpt.isEmpty()) {
            return sendErrorResponse(request, "❌ User not found. Please register first.");
        }

        var userContext = userContextOpt.get();

        // 2. Check admin commands
        ChatResponse adminResponse = adminHandler.handle(request, message, userContext);
        if (adminResponse != null) {
            sqsPublisher.sendResponse(adminResponse);
            return adminResponse;
        }

        // 3. Orchestrate (classify → parse → handle)
        ChatResponse response = orchestrator.process(request, userContext);
        sqsPublisher.sendResponse(response);
        return response;
    }

    /**
     * Send error response to user.
     */
    private ChatResponse sendErrorResponse(ChatRequest request, String errorMessage) {
        ChatResponse response = ChatResponse.builder()
                .chatId(request.getResponseChatId())
                .success(false)
                .message(errorMessage)
                .build();
        sqsPublisher.sendResponse(response);
        return response;
    }
}
