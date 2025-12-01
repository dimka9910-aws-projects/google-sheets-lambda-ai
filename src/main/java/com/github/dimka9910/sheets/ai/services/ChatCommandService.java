package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.dto.*;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Основной сервис обработки команд из чата.
 * Координирует AI парсинг, управление контекстом и отправку в очереди.
 */
@Slf4j
public class ChatCommandService {

    private final AICommandParser aiCommandParser;
    private final SQSPublisher sqsPublisher;
    private final UserContextService userContextService;

    // Паттерны для мета-команд
    private static final Pattern SET_CURRENCY_PATTERN = Pattern.compile(
            "(?i)(установи|поставь|смени)\\s+(валюту|currency)\\s+(?:по умолчанию\\s+)?(?:на\\s+)?(RUB|USD|EUR|рубли|доллары|евро)",
            Pattern.UNICODE_CASE
    );
    private static final Pattern SET_ACCOUNT_PATTERN = Pattern.compile(
            "(?i)(установи|поставь|смени)\\s+(счёт|счет|account)\\s+(?:по умолчанию\\s+)?(?:на\\s+)?(.+)",
            Pattern.UNICODE_CASE
    );
    private static final Pattern ADD_INSTRUCTION_PATTERN = Pattern.compile(
            "(?i)(запомни|помни|учти|remember)[:\\s]+(.+)",
            Pattern.UNICODE_CASE
    );
    private static final Pattern CLEAR_INSTRUCTIONS_PATTERN = Pattern.compile(
            "(?i)(забудь всё|очисти указания|clear instructions|сбрось настройки)",
            Pattern.UNICODE_CASE
    );
    private static final Pattern SHOW_SETTINGS_PATTERN = Pattern.compile(
            "(?i)(покажи настройки|мои настройки|show settings|настройки)",
            Pattern.UNICODE_CASE
    );

    public ChatCommandService() {
        this.aiCommandParser = new AICommandParser();
        this.sqsPublisher = new SQSPublisher();
        this.userContextService = new UserContextService();
    }

    public ChatCommandService(AICommandParser aiCommandParser, SQSPublisher sqsPublisher, 
                              UserContextService userContextService) {
        this.aiCommandParser = aiCommandParser;
        this.sqsPublisher = sqsPublisher;
        this.userContextService = userContextService;
    }

    /**
     * Обрабатывает запрос из чата
     */
    public ChatResponse processCommand(ChatRequest request) {
        log.info("Processing command from user {}: {}", request.getUserName(), request.getMessage());

        String userId = request.getUserId();
        String message = request.getMessage().trim();

        // Сначала проверяем мета-команды (настройки)
        ChatResponse metaResponse = handleMetaCommand(request, message);
        if (metaResponse != null) {
            sqsPublisher.sendResponse(metaResponse);
            return metaResponse;
        }

        // Получаем контекст пользователя
        UserContext userContext = userContextService.getContext(userId);

        // Парсим финансовую команду с учётом контекста
        ParsedCommand parsedCommand = aiCommandParser.parse(message, userContext);
        log.info("Parsed command: {}", parsedCommand);

        ChatResponse response = buildResponse(request, parsedCommand);

        if (response.isSuccess()) {
            sendToSheetsLambda(request, parsedCommand);
        }

        sqsPublisher.sendResponse(response);
        return response;
    }

    /**
     * Обрабатывает мета-команды (настройки пользователя)
     */
    private ChatResponse handleMetaCommand(ChatRequest request, String message) {
        String userId = request.getUserId();
        String chatId = request.getChatId();

        // Показать настройки
        Matcher showMatcher = SHOW_SETTINGS_PATTERN.matcher(message);
        if (showMatcher.find()) {
            String summary = userContextService.getContextSummary(userId);
            return ChatResponse.builder()
                    .chatId(chatId)
                    .success(true)
                    .message("⚙️ Твои настройки:\n\n" + summary)
                    .build();
        }

        // Установить валюту
        Matcher currencyMatcher = SET_CURRENCY_PATTERN.matcher(message);
        if (currencyMatcher.find()) {
            String currency = normalizeCurrency(currencyMatcher.group(3));
            userContextService.setDefaultCurrency(userId, currency);
            return ChatResponse.builder()
                    .chatId(chatId)
                    .success(true)
                    .message("✅ Валюта по умолчанию: " + currency)
                    .build();
        }

        // Установить счёт
        Matcher accountMatcher = SET_ACCOUNT_PATTERN.matcher(message);
        if (accountMatcher.find()) {
            String account = accountMatcher.group(3).trim();
            userContextService.setDefaultAccount(userId, account);
            return ChatResponse.builder()
                    .chatId(chatId)
                    .success(true)
                    .message("✅ Счёт по умолчанию: " + account)
                    .build();
        }

        // Добавить инструкцию
        Matcher instructionMatcher = ADD_INSTRUCTION_PATTERN.matcher(message);
        if (instructionMatcher.find()) {
            String instruction = instructionMatcher.group(2).trim();
            userContextService.addInstruction(userId, instruction);
            return ChatResponse.builder()
                    .chatId(chatId)
                    .success(true)
                    .message("✅ Запомнил: " + instruction)
                    .build();
        }

        // Очистить инструкции
        Matcher clearMatcher = CLEAR_INSTRUCTIONS_PATTERN.matcher(message);
        if (clearMatcher.find()) {
            userContextService.clearInstructions(userId);
            return ChatResponse.builder()
                    .chatId(chatId)
                    .success(true)
                    .message("✅ Все особые указания удалены")
                    .build();
        }

        return null; // Не мета-команда
    }

    private String normalizeCurrency(String input) {
        return switch (input.toLowerCase()) {
            case "рубли", "rub" -> "RUB";
            case "доллары", "usd" -> "USD";
            case "евро", "eur" -> "EUR";
            default -> input.toUpperCase();
        };
    }

    private ChatResponse buildResponse(ChatRequest request, ParsedCommand parsedCommand) {
        if (parsedCommand.isUnderstood() && parsedCommand.getOperationType() != OperationTypeEnum.UNKNOWN) {
            return ChatResponse.builder()
                    .chatId(request.getChatId())
                    .success(true)
                    .message(formatSuccessMessage(parsedCommand))
                    .parsedCommand(parsedCommand)
                    .build();
        }

        return ChatResponse.builder()
                .chatId(request.getChatId())
                .success(false)
                .message(parsedCommand.getClarification() != null
                        ? parsedCommand.getClarification()
                        : "Не удалось понять команду. Попробуйте ещё раз.")
                .parsedCommand(parsedCommand)
                .build();
    }

    private void sendToSheetsLambda(ChatRequest request, ParsedCommand parsedCommand) {
        SheetsRecordDTO sheetsRecord = SheetsRecordDTO.fromParsedCommand(
                parsedCommand,
                request.getUserName()
        );
        sqsPublisher.sendToSheetsLambda(sheetsRecord);
    }

    private String formatSuccessMessage(ParsedCommand cmd) {
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
}
