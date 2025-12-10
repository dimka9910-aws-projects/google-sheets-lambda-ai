package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.dto.*;
import com.github.dimka9910.sheets.ai.services.Orchestrator.OrchestrationResult;
import com.github.dimka9910.sheets.ai.services.llm.AICommandParser;
import com.github.dimka9910.sheets.ai.telemetry.RequestTelemetry;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Основной сервис обработки команд из чата.
 * Координирует Orchestrator, AI парсинг, управление контекстом и отправку в очереди.
 * 
 * Flow:
 * 1. Orchestrator: classify message → tags, isResponse, linkedUser
 * 2. AICommandParser: parse with dynamic context based on tags
 * 3. Process commands (execute or ask clarification)
 * 4. Send response
 */
@Slf4j
public class ChatCommandService {

    private final Orchestrator orchestrator;
    private final AICommandParser aiCommandParser;
    private final SQSPublisher sqsPublisher;
    private final UserContextService userContextService;
    private final ConversationService conversationService;
    private final OnboardingService onboardingService;

    public ChatCommandService() {
        this.orchestrator = new Orchestrator();
        this.aiCommandParser = new AICommandParser();
        this.sqsPublisher = new SQSPublisher();
        this.userContextService = new UserContextService();
        this.conversationService = new ConversationService();
        this.onboardingService = new OnboardingService(this.userContextService);
    }

    public ChatCommandService(UserContextService userContextService) {
        this.orchestrator = new Orchestrator();
        this.aiCommandParser = new AICommandParser();
        this.sqsPublisher = new SQSPublisher();
        this.userContextService = userContextService;
        this.conversationService = new ConversationService();
        this.onboardingService = new OnboardingService(userContextService);
    }

    public ChatCommandService(AICommandParser aiCommandParser, SQSPublisher sqsPublisher, 
                              UserContextService userContextService) {
        this.orchestrator = new Orchestrator();
        this.aiCommandParser = aiCommandParser;
        this.sqsPublisher = sqsPublisher;
        this.userContextService = userContextService;
        this.conversationService = new ConversationService();
        this.onboardingService = new OnboardingService(userContextService);
    }

    /**
     * Обрабатывает запрос из чата
     */
    public ChatResponse processCommand(ChatRequest request) {
        log.info("Processing command from user {}: {}", request.getUserName(), request.getMessage());

        String userId = request.getUserId();
        String message = request.getMessage() != null ? request.getMessage().trim() : "";

        // Получаем контекст пользователя
        UserContext userContext = userContextService.getContext(userId);
        
        // Загружаем контексты linked users для полного контекста в промпте
        loadLinkedUserContexts(userContext);
        
        // Admin commands — обрабатываем ДО всего остального
        // Это служебные команды, не зависят от языка, начинаются с /
        if (message.startsWith("/")) {
            ChatResponse adminResponse = handleAdminCommand(request, message, userContext);
            if (adminResponse != null) {
                sqsPublisher.sendResponse(adminResponse);
                return adminResponse;
            }
        }

        // Проверяем: нужен ли онбординг (новый пользователь без настроек)
        if (onboardingService.needsOnboarding(userContext)) {
            log.info("User {} needs onboarding", userId);
            ChatResponse onboardingResponse = onboardingService.handleOnboarding(request, message, userContext);
            sqsPublisher.sendResponse(onboardingResponse);
            return onboardingResponse;
        }

        // Добавляем сообщение пользователя в историю
        conversationService.addToHistory(userContext, ConversationMessage.userMessage(message));

        // Create telemetry for debug
        RequestTelemetry telemetry = new RequestTelemetry(userId, message);
        
        // Get previous bot message for response detection
        String previousBotMessage = conversationService.getLastBotMessage(userContext);
        boolean hasPendingResponse = userContext.isAwaitingClarification();
        
        log.info("Response detection: hasPendingResponse={}, previousBotMessage={}", 
                hasPendingResponse, previousBotMessage != null ? previousBotMessage.substring(0, Math.min(50, previousBotMessage.length())) : "null");
        
        // Step 1: Orchestrate - classify message, determine routing
        OrchestrationResult orchestration = orchestrator.process(
                message, previousBotMessage, hasPendingResponse, null, telemetry);
        
        log.info("Orchestration: tags={}, isResponse={}, model={}", 
                orchestration.tags(), orchestration.isResponse(), orchestration.model());

        // Step 2: Parse command with dynamic context based on orchestration
        ParsedCommandList parsedList = aiCommandParser.parse(
                message, userContext, orchestration.tags(), 
                orchestration.isResponse(), orchestration.matchedLinkedUser(), telemetry);
        log.info("Parsed commands: {} (count: {}), metaCommand: {}", 
                parsedList, parsedList.size(), parsedList.getMetaCommand());
        
        // Мержим с pending командами если есть (для уточнений)
        List<ParsedCommand> pendingCmds = userContext.getPendingCommands();
        if (pendingCmds != null && !pendingCmds.isEmpty() && parsedList.size() > 0) {
            // Мержим каждую pending команду с соответствующей новой (если есть)
            List<ParsedCommand> newCmds = parsedList.getCommands();
            for (int i = 0; i < pendingCmds.size(); i++) {
                ParsedCommand pending = pendingCmds.get(i);
                // Если AI вернул команду для этого индекса — мержим
                // Иначе берём из pending и обновляем amount из первой новой команды
                if (i < newCmds.size()) {
                    ParsedCommand merged = mergePendingWithNew(pending, newCmds.get(i));
                    newCmds.set(i, merged);
                    log.info("Merged pending command {} with new: {}", i, merged);
                } else if (newCmds.size() > 0 && newCmds.get(0).getAmount() != null) {
                    // AI вернул только одну команду с amount — возможно это ответ типа "пополам"
                    // В этом случае нужно распределить сумму по всем pending командам
                    // Пока просто добавляем pending команду как есть (AI должен был уточнить)
                    ParsedCommand merged = mergePendingWithNew(pending, newCmds.get(0));
                    newCmds.add(merged);
                    log.info("Added pending command {} with merged amount: {}", i, merged);
                }
            }
            parsedList.setCommands(newCmds);
        }
        
        // Проверяем: это мета-команда? (AI определил)
        if (parsedList.getMetaCommand() != null && parsedList.getMetaCommand().isPresent()) {
            ChatResponse metaResponse = handleAIMetaCommand(request, parsedList, userContext);
            if (metaResponse != null) {
                userContextService.saveContext(userContext);
                sqsPublisher.sendResponse(metaResponse);
                return metaResponse;
            }
        }

        // Строим ответ
        ChatResponse response = buildResponse(request, parsedList, userContext);
        
        // Определяем, был ли это уточняющий вопрос
        boolean wasClarification = !parsedList.isUnderstood() && parsedList.getClarification() != null;
        
        // Добавляем ответ ассистента в историю (используем первую команду для совместимости)
        ParsedCommand firstCmd = parsedList.getFirst();
        conversationService.addToHistory(userContext, 
                ConversationMessage.assistantMessage(response.getMessage(), firstCmd, wasClarification));

        // Управление pending commands для накопления ответов на уточнения
        if (wasClarification && parsedList.size() > 0) {
            // Сохраняем ВСЕ частично заполненные команды для следующего запроса
            userContext.setPendingCommands(new ArrayList<>(parsedList.getCommands()));
            log.info("Saved {} pending commands for clarification", parsedList.size());
        }

        // Если успешно распарсили — отправляем команды в sheets
        if (response.isSuccess()) {
            // Очищаем pending commands — команды завершены
            userContext.getPendingCommands().clear();
            
            // Если это коррекция — сначала отменяем старую операцию (отрицательная сумма)
            if (parsedList.isCorrection()) {
                ParsedCommand lastOp = userContext.popLastOperation();
                if (lastOp != null) {
                    log.info("Correction detected. Canceling old operation: {}", lastOp);
                    sendCancelOperation(userContext, lastOp);
                }
            }
            
            // Отправляем новые команды
            for (ParsedCommand cmd : parsedList.getCommands()) {
                sendToSheetsLambda(userContext, cmd);
                // Сохраняем для возможности отмены
                userContext.addOperation(cmd);
            }
            
            // SetAsDefault: если пользователь попросил установить дефолты
            // AI уже генерирует сообщение, здесь только применяем настройки
            if (parsedList.getSetAsDefault() != null && parsedList.getSetAsDefault().hasAny()) {
                ParsedCommandList.SetAsDefault defaults = parsedList.getSetAsDefault();
                if (defaults.getAccount() != null) {
                    userContext.setDefaultAccount(defaults.getAccount());
                }
                if (defaults.getCurrency() != null) {
                    userContext.setDefaultCurrency(defaults.getCurrency());
                }
                if (defaults.getFund() != null) {
                    userContext.setDefaultFund(defaults.getFund());
                }
                log.info("Updated defaults for user: {}", defaults);
            }
            
            // После успешной операции — очищаем историю (но НЕ pendingSuggestion!)
            conversationService.clearHistory(userContext);
        }

        // Сохраняем контекст (с историей)
        userContextService.saveContext(userContext);

        // Debug mode — добавляем телеметрию и подробную информацию
        if (Boolean.TRUE.equals(userContext.getDebugMode())) {
            String debugInfo = buildDebugInfo(parsedList, userContext, orchestration, telemetry);
            response.setMessage(response.getMessage() + "\n\n" + debugInfo);
        }

        sqsPublisher.sendResponse(response);
        return response;
    }
    
    /**
     * Формирует debug информацию с телеметрией агентов
     */
    private String buildDebugInfo(ParsedCommandList parsedList, UserContext userContext,
                                  OrchestrationResult orchestration, RequestTelemetry telemetry) {
        StringBuilder sb = new StringBuilder();
        
        // Full telemetry from all agents
        sb.append(telemetry.formatForTelegram());
        
        sb.append("\n\n🎯 ORCHESTRATION:\n");
        sb.append("  tags: ").append(orchestration.tags()).append("\n");
        sb.append("  isResponse: ").append(orchestration.isResponse()).append("\n");
        sb.append("  model: ").append(orchestration.model()).append("\n");
        if (orchestration.matchedLinkedUser() != null) {
            sb.append("  linkedUser: ").append(orchestration.matchedLinkedUser().name()).append("\n");
        }
        
        sb.append("\n📝 RESULT:\n");
        sb.append("  understood: ").append(parsedList.isUnderstood()).append("\n");
        sb.append("  commands: ").append(parsedList.size()).append("\n");
        
        if (parsedList.getMetaCommand() != null && parsedList.getMetaCommand().isPresent()) {
            sb.append("  metaCommand: ").append(parsedList.getMetaCommand().getType())
              .append(" = ").append(parsedList.getMetaCommand().getValue()).append("\n");
        }
        
        if (parsedList.isCorrection()) {
            sb.append("  correction: true\n");
        }
        
        // Commands details
        if (parsedList.getCommands() != null && !parsedList.getCommands().isEmpty()) {
            sb.append("\n📋 Operations:\n");
            for (int i = 0; i < parsedList.getCommands().size(); i++) {
                ParsedCommand cmd = parsedList.getCommands().get(i);
                sb.append("  ").append(i + 1).append(". ")
                  .append(cmd.getOperationType())
                  .append(" ").append(cmd.getAmount())
                  .append(" ").append(cmd.getCurrency())
                  .append(" → ").append(cmd.getAccountName())
                  .append(" / ").append(cmd.getFundName())
                  .append("\n");
            }
        }
        
        // Context state
        sb.append("\n💾 Context:\n");
        sb.append("  pendingCommands: ").append(userContext.getPendingCommands() != null ? userContext.getPendingCommands().size() : 0).append("\n");
        sb.append("  awaitingClarification: ").append(userContext.isAwaitingClarification()).append("\n");
        sb.append("  historySize: ").append(
                userContext.getConversationHistory() != null ? userContext.getConversationHistory().size() : 0
        ).append("\n");
        
        sb.append("━━━━━━━━━━━━━━━━━━━━");
        return sb.toString();
    }


    /**
     * Обрабатывает admin/debug команды.
     * Это служебные команды, не обрабатываются AI.
     */
    private ChatResponse handleAdminCommand(ChatRequest request, String message, UserContext userContext) {
        String chatId = request.getChatId();
        String userId = request.getUserId();
        String msgLower = message.toLowerCase().trim();
        
        // /info — показать список команд
        if (msgLower.equals("/info") || msgLower.equals("/help") || msgLower.equals("/commands")) {
            String info = """
                🛠️ Admin Commands:
                
                /debug on  — enable debug mode (show internal data)
                /debug off — disable debug mode
                /reset     — delete user and start fresh
                /note TEXT — save note to logs for developer
                /info      — show this help
                """;
            return ChatResponse.builder()
                    .chatId(chatId)
                    .success(true)
                    .message(info)
                    .build();
        }
        
        // /debug on|off
        if (msgLower.startsWith("/debug")) {
            String arg = msgLower.replace("/debug", "").trim();
            if (arg.equals("on") || arg.equals("1") || arg.equals("true")) {
                userContext.setDebugMode(true);
                userContextService.saveContext(userContext);
                return ChatResponse.builder()
                        .chatId(chatId)
                        .success(true)
                        .message("🔧 Debug mode ON — you'll see internal data with each response")
                        .build();
            } else if (arg.equals("off") || arg.equals("0") || arg.equals("false")) {
                userContext.setDebugMode(false);
                userContextService.saveContext(userContext);
                return ChatResponse.builder()
                        .chatId(chatId)
                        .success(true)
                        .message("🔧 Debug mode OFF")
                        .build();
            } else {
                String status = Boolean.TRUE.equals(userContext.getDebugMode()) ? "ON" : "OFF";
                return ChatResponse.builder()
                        .chatId(chatId)
                        .success(true)
                        .message("🔧 Debug mode: " + status + "\nUse: /debug on or /debug off")
                        .build();
            }
        }
        
        // /reset — удалить пользователя
        if (msgLower.equals("/reset") || msgLower.equals("/restart") || msgLower.equals("/clear")) {
            userContextService.deleteUser(userId);
            log.info("[ADMIN] User {} deleted by /reset command", userId);
            return ChatResponse.builder()
                    .chatId(chatId)
                    .success(true)
                    .message("🗑️ User deleted. Send any message to start fresh!")
                    .build();
        }
        
        // /note или ps: — сохранить заметку в логи
        if (msgLower.startsWith("/note")) {
            String note = message.startsWith("/note") 
                    ? message.substring(5).trim() 
                    : message.substring(3).trim();
            log.warn("[USER_FEEDBACK] userId={} note={}", userId, note);
            return ChatResponse.builder()
                    .chatId(chatId)
                    .success(true)
                    .message("📝 Noted! (saved to logs for developer)")
                    .build();
        }
        
        return null; // Не admin команда
    }

    /**
     * Обрабатывает мета-команды на основе ответа AI.
     * AI определяет тип команды на ЛЮБОМ языке — без regex!
     */
    private ChatResponse handleAIMetaCommand(ChatRequest request, ParsedCommandList parsedList, UserContext userContext) {
        ParsedCommandList.MetaCommand meta = parsedList.getMetaCommand();
        if (meta == null || !meta.isPresent()) {
            return null;
        }
        
        String chatId = request.getChatId();
        String userId = request.getUserId();
        String type = meta.getType();
        String value = meta.getValue();
        
        // AI уже сгенерировал сообщение для пользователя в clarification
        String aiMessage = parsedList.getClarification();
        
        log.info("Processing meta command: type={}, value={}", type, value);
        
        switch (type.toUpperCase()) {
            case "SHOW_SETTINGS" -> {
                String summary = userContextService.getContextSummary(userId);
                // AI должен был сгенерировать сообщение, но добавляем summary
                return ChatResponse.builder()
                        .chatId(chatId)
                        .success(true)
                        .message(aiMessage != null ? aiMessage + "\n\n" + summary : summary)
                        .build();
            }
            
            case "ADD_ACCOUNT" -> {
                if (value != null && !value.isBlank()) {
                    String account = value.toUpperCase().replaceAll("\\s+", "_");
                    userContext.addAccount(account);
                }
                return ChatResponse.builder()
                        .chatId(chatId)
                        .success(true)
                        .message(aiMessage)
                        .build();
            }
            
            case "ADD_FUND" -> {
                if (value != null && !value.isBlank()) {
                    String fund = value.toUpperCase().replaceAll("\\s+", "_");
                    userContext.addFund(fund);
                }
                return ChatResponse.builder()
                        .chatId(chatId)
                        .success(true)
                        .message(aiMessage)
                        .build();
            }
            
            case "ADD_INSTRUCTION" -> {
                if (value != null && !value.isBlank()) {
                    // Проверка на дубликаты - если уже есть, просто логируем
                    List<String> existing = userContext.getCustomInstructions();
                    if (existing != null && existing.contains(value)) {
                        log.info("Instruction already exists for user {}: {}", userId, value);
                    } else {
                        userContext.addInstruction(value);
                        log.info("Added instruction for user {}: {}", userId, value);
                    }
                } else {
                    log.warn("ADD_INSTRUCTION called but value is empty for user {}", userId);
                }
                return ChatResponse.builder()
                        .chatId(chatId)
                        .success(true)
                        .message(aiMessage)
                        .build();
            }
            
            case "REMOVE_INSTRUCTION" -> {
                if (value != null && !value.isBlank()) {
                    try {
                        int index = Integer.parseInt(value.trim());
                        List<String> instructions = userContext.getCustomInstructions();
                        if (instructions != null && index >= 0 && index < instructions.size()) {
                            String removed = instructions.get(index);
                            userContext.removeInstruction(index);
                            log.info("Removed instruction [{}] for user {}: {}", index, userId, removed);
                        } else {
                            log.warn("REMOVE_INSTRUCTION: invalid index {} for user {} (has {} instructions)", 
                                    index, userId, instructions != null ? instructions.size() : 0);
                        }
                    } catch (NumberFormatException e) {
                        log.warn("REMOVE_INSTRUCTION: invalid index '{}' for user {}", value, userId);
                    }
                }
                return ChatResponse.builder()
                        .chatId(chatId)
                        .success(true)
                        .message(aiMessage)
                        .build();
            }
            
            case "SET_DEFAULT_CURRENCY" -> {
                if (value != null && !value.isBlank()) {
                    userContext.setDefaultCurrency(value.toUpperCase());
                }
                return ChatResponse.builder()
                        .chatId(chatId)
                        .success(true)
                        .message(aiMessage)
                        .build();
            }
            
            case "SET_DEFAULT_ACCOUNT" -> {
                if (value != null && !value.isBlank()) {
                    userContext.setDefaultAccount(value.toUpperCase());
                }
                return ChatResponse.builder()
                        .chatId(chatId)
                        .success(true)
                        .message(aiMessage)
                        .build();
            }
            
            case "SET_DEFAULT_FUND" -> {
                if (value != null && !value.isBlank()) {
                    userContext.setDefaultFund(value.toUpperCase());
                }
                return ChatResponse.builder()
                        .chatId(chatId)
                        .success(true)
                        .message(aiMessage)
                        .build();
            }
            
            case "CLEAR_INSTRUCTIONS" -> {
                userContext.clearInstructions();
                return ChatResponse.builder()
                        .chatId(chatId)
                        .success(true)
                        .message(aiMessage)
                        .build();
            }
            
            case "UNDO" -> {
                return handleUndo(request, userContext, aiMessage);
            }
            
            case "HELP" -> {
                // AI сам генерирует помощь на языке пользователя
                return ChatResponse.builder()
                        .chatId(chatId)
                        .success(true)
                        .message(aiMessage)
                        .build();
            }
            
            default -> {
                log.warn("Unknown meta command type: {}", type);
                return null;
            }
        }
    }

    /**
     * Обрабатывает команду отмены последней операции.
     * AI message используется если доступен, иначе генерируем технический fallback.
     */
    private ChatResponse handleUndo(ChatRequest request, UserContext userContext, String aiMessage) {
        String chatId = request.getChatId();
        
        if (!userContext.hasOperationsToUndo()) {
            // Fallback если AI не сгенерировал сообщение
            String msg = aiMessage != null ? aiMessage : "No operations to undo";
            return ChatResponse.builder()
                    .chatId(chatId)
                    .success(false)
                    .message(msg)
                    .operationsCount(0)
                    .build();
        }
        
        ParsedCommand lastOp = userContext.popLastOperation();
        log.info("Undoing operation: {}", lastOp);
        
        // Отправляем команду отмены в SQS
        SheetsRecordDTO undoRecord = SheetsRecordDTO.fromParsedCommand(lastOp, request.getUserName());
        undoRecord.setUndo(true);  // Флаг отмены
        sqsPublisher.sendToSheetsLambda(undoRecord);
        
        // Сохраняем контекст (без отменённой операции)
        userContextService.saveContext(userContext);
        
        // AI должен сгенерировать сообщение, но если нет — fallback
        String msg = aiMessage != null ? aiMessage : "Undo: " + formatUndoDescription(lastOp);
        return ChatResponse.builder()
                .chatId(chatId)
                .success(true)
                .message(msg)
                .parsedCommand(lastOp)
                .operationsCount(0)
                .build();
    }

    private String formatUndoDescription(ParsedCommand cmd) {
        String comment = cmd.getComment() != null ? cmd.getComment() : "";
        return String.format("%.0f %s — %s", 
                cmd.getAmount() != null ? cmd.getAmount() : 0, 
                cmd.getCurrency() != null ? cmd.getCurrency() : "", 
                comment);
    }

    private ChatResponse buildResponse(ChatRequest request, ParsedCommandList parsedList, UserContext userContext) {
        List<ParsedCommand> commands = parsedList.getCommands();
        
        // Проверяем что все команды валидны
        boolean allValid = parsedList.isUnderstood() 
                && commands != null 
                && !commands.isEmpty()
                && commands.stream().allMatch(cmd -> 
                        cmd.getOperationType() != null && cmd.getOperationType() != OperationTypeEnum.UNKNOWN);
        
        // AI генерирует сообщение в clarification — используем его
        // НЕ хардкодим сообщения на конкретном языке!
        String message = parsedList.getClarification();
        if (message == null || message.isBlank()) {
            message = parsedList.getErrorMessage();
        }
        if (message == null || message.isBlank()) {
            // Minimal fallback — AI должен всегда генерировать сообщение
            log.warn("No clarification or error message from AI for user {}", request.getUserId());
            message = allValid ? "✓" : "?";
        }
        
        return ChatResponse.builder()
                .chatId(request.getChatId())
                .success(allValid)
                .message(message)
                .parsedCommands(commands)
                .parsedCommand(parsedList.getFirst())
                .operationsCount(allValid ? commands.size() : 0)
                .build();
    }

    private void sendToSheetsLambda(UserContext userContext, ParsedCommand parsedCommand) {
        // Используем userName из DynamoDB (DIMA, KIKI), а не из Telegram (Dima, Ksenija)
        String userName = userContext.getUserName() != null ? userContext.getUserName() : userContext.getUserId();
        SheetsRecordDTO sheetsRecord = SheetsRecordDTO.fromParsedCommand(
                parsedCommand,
                userName
        );
        sqsPublisher.sendToSheetsLambda(sheetsRecord);
    }
    
    /**
     * Отправляет операцию отмены с отрицательной суммой (Event Sourcing style)
     */
    private void sendCancelOperation(UserContext userContext, ParsedCommand originalOp) {
        // Создаём копию с отрицательной суммой
        ParsedCommand cancelOp = ParsedCommand.builder()
                .operationType(originalOp.getOperationType())
                .amount(-originalOp.getAmount())  // Отрицательная сумма!
                .currency(originalOp.getCurrency())
                .accountName(originalOp.getAccountName())
                .fundName(originalOp.getFundName())
                .comment("CANCEL: " + originalOp.getComment())
                .secondPerson(originalOp.getSecondPerson())
                .secondAccount(originalOp.getSecondAccount())
                .secondCurrency(originalOp.getSecondCurrency())
                .understood(true)
                .build();
        
        sendToSheetsLambda(userContext, cancelOp);
    }

    
    /**
     * Мержит pending команду с новым ответом AI.
     * Новые non-null значения перезаписывают, остальные берутся из pending.
     */
    private ParsedCommand mergePendingWithNew(ParsedCommand pending, ParsedCommand newCmd) {
        return ParsedCommand.builder()
                .operationType(newCmd.getOperationType() != null ? newCmd.getOperationType() : pending.getOperationType())
                .amount(newCmd.getAmount() != null && newCmd.getAmount() > 0 ? newCmd.getAmount() : pending.getAmount())
                .currency(newCmd.getCurrency() != null ? newCmd.getCurrency() : pending.getCurrency())
                .accountName(newCmd.getAccountName() != null ? newCmd.getAccountName() : pending.getAccountName())
                .fundName(newCmd.getFundName() != null ? newCmd.getFundName() : pending.getFundName())
                .comment(newCmd.getComment() != null ? newCmd.getComment() : pending.getComment())
                .secondAccount(newCmd.getSecondAccount() != null ? newCmd.getSecondAccount() : pending.getSecondAccount())
                .secondPerson(newCmd.getSecondPerson() != null ? newCmd.getSecondPerson() : pending.getSecondPerson())
                .secondCurrency(newCmd.getSecondCurrency() != null ? newCmd.getSecondCurrency() : pending.getSecondCurrency())
                .understood(newCmd.isUnderstood())
                .clarification(newCmd.getClarification())
                .errorMessage(newCmd.getErrorMessage())
                .build();
    }
    
    /**
     * Загружает контексты linked users и добавляет их в основной контекст.
     * Это нужно для того, чтобы AI видел счета/фонды/defaults linked users.
     */
    private void loadLinkedUserContexts(UserContext userContext) {
        List<String> linkedUsers = userContext.getLinkedUsers();
        if (linkedUsers == null || linkedUsers.isEmpty()) {
            return;
        }
        
        for (String linkedUserEntry : linkedUsers) {
            // linkedUserEntry формат: "NAME (userId)" или просто "userId"
            String linkedUserId = extractUserId(linkedUserEntry);
            if (linkedUserId != null && !linkedUserId.equals(userContext.getUserId())) {
                try {
                    UserContext linkedContext = userContextService.getContext(linkedUserId);
                    if (linkedContext != null && linkedContext.getUserId() != null) {
                        userContext.addLinkedUserContext(linkedUserId, linkedContext);
                        log.info("Loaded linked user context: {} for user {}", 
                                linkedContext.getUserName(), userContext.getUserId());
                    }
                } catch (Exception e) {
                    log.warn("Failed to load linked user context for {}: {}", linkedUserId, e.getMessage());
                }
            }
        }
    }
    
    /**
     * Извлекает userId из строки формата "NAME (userId)" или просто "userId"
     */
    private String extractUserId(String linkedUserEntry) {
        if (linkedUserEntry == null || linkedUserEntry.isBlank()) {
            return null;
        }
        // Если формат "NAME (userId)" — извлекаем userId из скобок
        int start = linkedUserEntry.lastIndexOf('(');
        int end = linkedUserEntry.lastIndexOf(')');
        if (start != -1 && end != -1 && end > start) {
            return linkedUserEntry.substring(start + 1, end).trim();
        }
        // Иначе считаем что это просто userId
        return linkedUserEntry.trim();
    }
}
