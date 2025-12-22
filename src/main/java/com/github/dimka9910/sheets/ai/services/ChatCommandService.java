package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.dto.telegram.ChatRequest;
import com.github.dimka9910.sheets.ai.dto.telegram.ChatResponse;
import com.github.dimka9910.sheets.ai.dto.user.LinkedUserEntry;
import com.github.dimka9910.sheets.ai.dto.user.UserEntity;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Spring Service - main entry point for chat commands.
 * 
 * Flow:
 * 1. Check admin commands
 * 2. Check user setup (accounts, funds)
 * 3. Delegate to Orchestrator → ChatResponse
 * 4. Send response via SQS
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatCommandService {

    private final Orchestrator orchestrator;
    private final SQSPublisher sqsPublisher;
    private final UserEntityService userContextService;
    private final AdminCommandHandler adminHandler;
    
    @PostConstruct
    public void init() {
        log.info("✅ ChatCommandService initialized");
    }

    /**
     * Process chat command.
     */
    public ChatResponse processCommand(ChatRequest request) {
        String telegramUserId = request.getTelegramUserId();
        String message = request.getMessage() != null ? request.getMessage().trim() : "";
        
        log.info("Processing: telegramUserId={}, message={}", telegramUserId, message);

        // Resolve user from Telegram ID via DynamoDB
        if (telegramUserId == null || telegramUserId.isBlank()) {
            log.warn("No telegramUserId provided in request");
            return handleNewUser(request);
        }
        
        var found = userContextService.getByTelegramId(telegramUserId);
        if (found.isEmpty()) {
            // New user — need to create account first
            return handleNewUser(request);
        }
        
        UserEntity userContext = found.get();
        String userName = userContext.getUserName();
        log.info("Resolved telegramUserId={} → userName={}", telegramUserId, userName);
        
        loadLinkedUserEntitys(userContext);
        
        // 1. Admin commands (/debug, /reset, etc.)
        ChatResponse adminResponse = adminHandler.handle(request, message, userContext);
        if (adminResponse != null) {
            sqsPublisher.sendResponse(adminResponse);
            return adminResponse;
        }

        // 2. Check setup (must have at least 1 account and 1 fund)
        if (!hasBasicSetup(userContext)) {
            ChatResponse setupResponse = buildSetupRequiredResponse(request, userContext);
            sqsPublisher.sendResponse(setupResponse);
            return setupResponse;
        }

        // 3. Orchestrate (classify → parse → handle)
        ChatResponse response = orchestrator.process(request, userContext);

        sqsPublisher.sendResponse(response);
        return response;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Handle new user from Telegram (no userName assigned yet).
     */
    private ChatResponse handleNewUser(ChatRequest request) {
        String telegramUserId = request.getTelegramUserId();
        log.info("New user from Telegram: {}", telegramUserId);
        
        String welcomeMessage = """
                👋 Welcome! I'm your personal finance assistant.
                
                To get started, please tell me your name:
                "My name is DIMA" or "I am KIKI"
                
                This will be your identifier in the system.
                """;
        
        ChatResponse response = ChatResponse.builder()
                .chatId(request.getResponseChatId())
                .success(true)
                .message(welcomeMessage)
                .build();
        
        sqsPublisher.sendResponse(response);
        return response;
    }

    private boolean hasBasicSetup(UserEntity userContext) {
        boolean hasAccounts = userContext.getAccounts() != null && !userContext.getAccounts().isEmpty();
        boolean hasFunds = userContext.getFunds() != null && !userContext.getFunds().isEmpty();
        return hasAccounts && hasFunds;
    }

    private ChatResponse buildSetupRequiredResponse(ChatRequest request, UserEntity userContext) {
        log.info("User {} missing setup", request.getUserName());
        
        boolean hasAccounts = userContext.getAccounts() != null && !userContext.getAccounts().isEmpty();
        boolean hasFunds = userContext.getFunds() != null && !userContext.getFunds().isEmpty();
        
        StringBuilder sb = new StringBuilder();
        sb.append("Before I can help you track expenses, please set up:\n\n");
        
        if (!hasAccounts) {
            sb.append("📋 **Accounts** - where your money is stored\n");
            sb.append("   Example: \"add account CARD\" or \"add account CASH\"\n\n");
        }
        
        if (!hasFunds) {
            sb.append("📂 **Funds/Categories** - how you categorize expenses\n");
            sb.append("   Example: \"add fund FOOD\" or \"add fund TRANSPORT\"\n\n");
        }
        
        sb.append("After setup, you can start tracking: \"coffee 200\" ☕");
        
        return ChatResponse.builder()
                .chatId(request.getResponseChatId())
                .success(true)
                .message(sb.toString())
                .build();
    }

    private void loadLinkedUserEntitys(UserEntity userContext) {
        List<LinkedUserEntry> linkedUsers = userContext.getLinkedUsers();
        if (linkedUsers == null || linkedUsers.isEmpty()) {
            return;
        }
        
        for (LinkedUserEntry linkedUser : linkedUsers) {
            String linkedUserName = linkedUser.getUserName();
            if (linkedUserName != null && !linkedUserName.equals(userContext.getUserName())) {
                try {
                    UserEntity linkedContext = userContextService.getByUserName(linkedUserName);
                    if (linkedContext != null && linkedContext.getUserName() != null) {
                        userContext.addLinkedUserEntity(linkedUserName, linkedContext);
                    }
                } catch (Exception e) {
                    log.warn("Failed to load linked user context: {}", linkedUserName);
                }
            }
        }
    }
}
