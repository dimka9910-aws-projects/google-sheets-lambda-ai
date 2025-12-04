package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.dto.*;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Основной сервис обработки команд из чата.
 * Координирует AI парсинг, управление контекстом и отправку в очереди.
 * 
 * ВАЖНО: НЕ используем regex для понимания команд пользователя!
 * Все команды (включая мета-команды) понимает AI через промпт.
 * Это позволяет работать на ЛЮБОМ языке.
 */
@Slf4j
public class ChatCommandService {

    private final AICommandParser aiCommandParser;
    private final SQSPublisher sqsPublisher;
    private final UserContextService userContextService;
    private final ConversationService conversationService;
    private final OnboardingService onboardingService;

    public ChatCommandService() {
        this.aiCommandParser = new AICommandParser();
        this.sqsPublisher = new SQSPublisher();
        this.userContextService = new UserContextService();
        this.conversationService = new ConversationService();
        this.onboardingService = new OnboardingService(this.userContextService);
    }

    public ChatCommandService(UserContextService userContextService) {
        this.aiCommandParser = new AICommandParser();
        this.sqsPublisher = new SQSPublisher();
        this.userContextService = userContextService;
        this.conversationService = new ConversationService();
        this.onboardingService = new OnboardingService(userContextService);
    }

    public ChatCommandService(AICommandParser aiCommandParser, SQSPublisher sqsPublisher, 
                              UserContextService userContextService) {
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
        if (message.startsWith("/") || message.toLowerCase().startsWith("ps:")) {
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

        // Проверяем: это новая команда или продолжение диалога?
        boolean isNewCommand = conversationService.isNewCommand(message, userContext);
        
        if (isNewCommand) {
            log.info("New command detected, clearing conversation history and pending commands");
            conversationService.clearHistory(userContext);
            userContext.getPendingCommands().clear();  // Очищаем pending при новой команде
        } else {
            log.info("Continuing conversation, history size: {}", 
                    userContext.getConversationHistory() != null ? userContext.getConversationHistory().size() : 0);
        }

        // Проверяем: ответ на предложение сохранить инструкцию (Learning)
        // НЕ сохраняем userContext здесь — addInstruction уже сохранил с новой инструкцией
        ChatResponse learningResponse = handleLearningSuggestionResponse(request, message, userContext);
        if (learningResponse != null) {
            sqsPublisher.sendResponse(learningResponse);
            return learningResponse;
        }

        // Добавляем сообщение пользователя в историю
        conversationService.addToHistory(userContext, ConversationMessage.userMessage(message));

        // Парсим команду через AI (финансовая или мета-команда — AI сам определит)
        ParsedCommandList parsedList = aiCommandParser.parseMultiple(message, userContext);
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
            
            // Learning: если AI предложил инструкцию — добавляем в ответ и сохраняем pending
            if (parsedList.getSuggestedInstruction() != null && !parsedList.getSuggestedInstruction().isBlank()) {
                String suggestion = parsedList.getSuggestedInstruction();
                userContext.setPendingSuggestion(suggestion);
                response.setMessage(response.getMessage() + 
                    "\n\n💡 Запомнить: \"" + suggestion + "\"? (да/нет)");
            }
            
            // SetAsDefault: если пользователь попросил установить дефолты
            if (parsedList.getSetAsDefault() != null && parsedList.getSetAsDefault().hasAny()) {
                ParsedCommandList.SetAsDefault defaults = parsedList.getSetAsDefault();
                StringBuilder defaultsMsg = new StringBuilder();
                
                if (defaults.getAccount() != null) {
                    userContext.setDefaultAccount(defaults.getAccount());
                    defaultsMsg.append("📌 Счёт по умолчанию: ").append(defaults.getAccount()).append("\n");
                }
                if (defaults.getCurrency() != null) {
                    userContext.setDefaultCurrency(defaults.getCurrency());
                    defaultsMsg.append("📌 Валюта по умолчанию: ").append(defaults.getCurrency()).append("\n");
                }
                if (defaults.getFund() != null) {
                    userContext.setDefaultFund(defaults.getFund());
                    defaultsMsg.append("📌 Фонд по умолчанию: ").append(defaults.getFund()).append("\n");
                }
                
                if (defaultsMsg.length() > 0) {
                    response.setMessage(response.getMessage() + "\n\n" + defaultsMsg.toString().trim());
                    log.info("Updated defaults for user: {}", defaults);
                }
            }
            
            // После успешной операции — очищаем историю (но НЕ pendingSuggestion!)
            conversationService.clearHistory(userContext);
        }

        // Сохраняем контекст (с историей)
        userContextService.saveContext(userContext);

        // Debug mode — добавляем подробную информацию
        if (Boolean.TRUE.equals(userContext.getDebugMode())) {
            String debugInfo = buildDebugInfo(parsedList, userContext);
            response.setMessage(response.getMessage() + "\n\n" + debugInfo);
        }

        sqsPublisher.sendResponse(response);
        return response;
    }
    
    /**
     * Формирует debug информацию для ответа
     */
    private String buildDebugInfo(ParsedCommandList parsedList, UserContext userContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("🔧 DEBUG:\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━\n");
        
        // Token usage (если есть)
        if (parsedList.getTokenUsage() != null) {
            sb.append(parsedList.getTokenUsage()).append("\n");
        }
        
        // AI Response summary
        sb.append("understood: ").append(parsedList.isUnderstood()).append("\n");
        sb.append("commands: ").append(parsedList.size()).append("\n");
        
        if (parsedList.getMetaCommand() != null && parsedList.getMetaCommand().isPresent()) {
            sb.append("metaCommand: ").append(parsedList.getMetaCommand().getType())
              .append(" = ").append(parsedList.getMetaCommand().getValue()).append("\n");
        }
        
        if (parsedList.getClarification() != null) {
            sb.append("clarification: ").append(parsedList.getClarification()).append("\n");
        }
        
        if (parsedList.isCorrection()) {
            sb.append("correction: true\n");
        }
        
        // Commands details
        if (parsedList.getCommands() != null && !parsedList.getCommands().isEmpty()) {
            sb.append("\nOperations:\n");
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
        sb.append("\nContext:\n");
        sb.append("  pendingCommands: ").append(userContext.getPendingCommands() != null ? userContext.getPendingCommands().size() : 0).append("\n");
        sb.append("  awaitingClarification: ").append(userContext.isAwaitingClarification()).append("\n");
        sb.append("  historySize: ").append(
                userContext.getConversationHistory() != null ? userContext.getConversationHistory().size() : 0
        ).append("\n");
        
        sb.append("━━━━━━━━━━━━━━━━━━━━");
        return sb.toString();
    }

    /**
     * Обрабатывает ответ на предложение сохранить инструкцию (Learning)
     */
    private ChatResponse handleLearningSuggestionResponse(ChatRequest request, String message, UserContext userContext) {
        String pending = userContext.getPendingSuggestion();
        if (pending == null || pending.isBlank()) {
            return null; // Нет ожидающего предложения
        }
        
        String lower = message.toLowerCase().trim();
        
        // Проверяем положительный ответ
        if (lower.matches("да|yes|ок|окей|ok|okay|конечно|запомни|сохрани|ага|угу|давай|го|1|\\+")) {
            // Сначала получаем свежий контекст, добавляем инструкцию
            UserContext freshContext = userContextService.getContext(userContext.getUserId());
            freshContext.addInstruction(pending);
            freshContext.setPendingSuggestion(null);
            freshContext.clearHistory();
            userContextService.saveContext(freshContext);
            
            log.info("Learning: saved instruction '{}' for user {}", pending, userContext.getUserId());
            
            return ChatResponse.builder()
                    .chatId(request.getChatId())
                    .success(true)
                    .message("✅ Запомнил: \"" + pending + "\"")
                    .operationsCount(0)
                    .build();
        }
        
        // Проверяем отрицательный ответ
        if (lower.matches("нет|no|не надо|не нужно|отмена|cancel|0|\\-|неа|не")) {
            userContext.setPendingSuggestion(null);
            conversationService.clearHistory(userContext);
            userContextService.saveContext(userContext);
            
            return ChatResponse.builder()
                    .chatId(request.getChatId())
                    .success(true)
                    .message("👌 Ок, не запоминаю")
                    .operationsCount(0)
                    .build();
        }
        
        // Не похоже на ответ да/нет — очищаем pending и обрабатываем как новую команду
        userContext.setPendingSuggestion(null);
        userContextService.saveContext(userContext);
        return null;
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
                
                ps: TEXT   — same as /note (save feedback to logs)
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
        if (msgLower.startsWith("/note") || msgLower.startsWith("ps:")) {
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
                    // Проверка на дубликаты
                    List<String> existing = userContext.getCustomInstructions();
                    if (existing != null && existing.contains(value)) {
                        log.info("Instruction already exists for user {}: {}", userId, value);
                        return ChatResponse.builder()
                                .chatId(chatId)
                                .success(true)
                                .message(aiMessage + " (уже было)")
                                .build();
                    }
                    userContext.addInstruction(value);
                    log.info("Added instruction for user {}: {}", userId, value);
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
        
        if (allValid) {
            String message;
            if (parsedList.isCorrection()) {
                // Форматируем сообщение о коррекции
                ParsedCommand lastOp = userContext.getLastOperation();
                message = formatCorrectionMessage(lastOp, commands.get(0));
            } else {
                message = formatSuccessMessage(commands);
            }
            
            return ChatResponse.builder()
                    .chatId(request.getChatId())
                    .success(true)
                    .message(message)
                    .parsedCommands(commands)
                    .parsedCommand(parsedList.getFirst()) // для обратной совместимости
                    .operationsCount(commands.size())
                    .build();
        }

        // Если AI не вернул clarification — используем errorMessage или пустой ответ
        // НЕ хардкодим сообщения на конкретном языке!
        String message = parsedList.getClarification();
        if (message == null || message.isBlank()) {
            message = parsedList.getErrorMessage();
        }
        if (message == null || message.isBlank()) {
            // Fallback — просим AI сгенерировать сообщение
            // Но если даже AI молчит — логируем и возвращаем минимальный ответ
            log.warn("No clarification or error message from AI for user {}", request.getUserId());
            message = "?"; // Минимальный индикатор что что-то не так
        }
        
        return ChatResponse.builder()
                .chatId(request.getChatId())
                .success(false)
                .message(message)
                .parsedCommands(commands)
                .parsedCommand(parsedList.getFirst())
                .operationsCount(0)
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
     * Форматирует сообщение об успехе для нескольких команд
     */
    private String formatSuccessMessage(List<ParsedCommand> commands) {
        if (commands.size() == 1) {
            return formatSingleCommand(commands.get(0));
        }
        
        // Несколько команд — формируем список
        StringBuilder sb = new StringBuilder();
        sb.append("✅ Записал ").append(commands.size()).append(" операции:\n");
        
        for (int i = 0; i < commands.size(); i++) {
            ParsedCommand cmd = commands.get(i);
            sb.append(i + 1).append(". ").append(formatSingleCommandShort(cmd)).append("\n");
        }
        
        return sb.toString().trim();
    }

    private String formatSingleCommand(ParsedCommand cmd) {
        return switch (cmd.getOperationType()) {
            case EXPENSES -> String.format("✅ Записал расход: %.2f %s на %s (%s)",
                    cmd.getAmount(), cmd.getCurrency(), cmd.getFundName(), cmd.getAccountName());
            case INCOME -> String.format("✅ Записал доход: %.2f %s на счёт %s",
                    cmd.getAmount(), cmd.getCurrency(), cmd.getAccountName());
            case TRANSFER -> String.format("✅ Записал перевод: %.2f %s с %s на %s",
                    cmd.getAmount(), cmd.getCurrency(), cmd.getAccountName(), cmd.getSecondAccount());
            case CREDIT -> String.format("✅ Записал кредитную операцию: %.2f %s",
                    cmd.getAmount(), cmd.getCurrency());
            default -> "✅ Операция записана";
        };
    }

    private String formatSingleCommandShort(ParsedCommand cmd) {
        String comment = cmd.getComment() != null ? cmd.getComment() : cmd.getFundName();
        return switch (cmd.getOperationType()) {
            case EXPENSES -> String.format("%.0f %s — %s", cmd.getAmount(), cmd.getCurrency(), comment);
            case INCOME -> String.format("+%.0f %s — доход", cmd.getAmount(), cmd.getCurrency());
            case TRANSFER -> String.format("%.0f %s — перевод", cmd.getAmount(), cmd.getCurrency());
            case CREDIT -> String.format("%.0f %s — кредит", cmd.getAmount(), cmd.getCurrency());
            default -> "операция";
        };
    }
    
    /**
     * Форматирует сообщение о коррекции операции
     */
    private String formatCorrectionMessage(ParsedCommand oldOp, ParsedCommand newOp) {
        StringBuilder sb = new StringBuilder("✏️ Исправил: ");
        
        // Сравниваем что изменилось
        boolean amountChanged = oldOp != null && !oldOp.getAmount().equals(newOp.getAmount());
        boolean accountChanged = oldOp != null && !safeEquals(oldOp.getAccountName(), newOp.getAccountName());
        boolean fundChanged = oldOp != null && !safeEquals(oldOp.getFundName(), newOp.getFundName());
        boolean commentChanged = oldOp != null && !safeEquals(oldOp.getComment(), newOp.getComment());
        
        if (amountChanged && oldOp != null) {
            sb.append(String.format("%.0f → %.0f %s", oldOp.getAmount(), newOp.getAmount(), newOp.getCurrency()));
        } else if (accountChanged && oldOp != null) {
            sb.append(String.format("%s → %s", oldOp.getAccountName(), newOp.getAccountName()));
        } else if (fundChanged && oldOp != null) {
            sb.append(String.format("%s → %s", oldOp.getFundName(), newOp.getFundName()));
        } else if (commentChanged && oldOp != null) {
            sb.append(String.format("'%s' → '%s'", oldOp.getComment(), newOp.getComment()));
        } else {
            // Общий формат если не смогли определить что изменилось
            sb.append(formatSingleCommandShort(newOp));
        }
        
        return sb.toString();
    }
    
    private boolean safeEquals(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
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
