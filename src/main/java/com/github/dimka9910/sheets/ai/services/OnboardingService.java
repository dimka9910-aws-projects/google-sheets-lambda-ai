package com.github.dimka9910.sheets.ai.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimka9910.sheets.ai.dto.ChatRequest;
import com.github.dimka9910.sheets.ai.dto.ChatResponse;
import com.github.dimka9910.sheets.ai.dto.OnboardingState;
import com.github.dimka9910.sheets.ai.dto.UserContext;
import com.github.dimka9910.sheets.ai.config.AppConfig;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

/**
 * Сервис онбординга новых пользователей.
 * Использует AI для генерации сообщений на языке пользователя.
 * НИКАКИХ ХАРДКОДОВ — AI сам определяет язык и адаптирует ответы.
 */
@Slf4j
public class OnboardingService {

    private final UserContextService userContextService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";

    public OnboardingService(UserContextService userContextService) {
        this.userContextService = userContextService;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Проверяет, нужен ли онбординг для пользователя
     */
    public boolean needsOnboarding(UserContext context) {
        if (context == null) return true;
        
        String state = context.getOnboardingState();
        
        // Если онбординг завершён — не нужен
        if (OnboardingState.COMPLETED.name().equals(state)) {
            return false;
        }
        
        // Если онбординг в процессе — нужен
        if (state != null && !OnboardingState.NOT_STARTED.name().equals(state)) {
            return true;
        }
        
        // Минимальные настройки = хотя бы 1 счёт И 1 фонд
        // (валюта, дефолты, имя — можно настроить позже через lazy setup)
        boolean hasMinimumSetup = 
                context.getAccounts() != null && !context.getAccounts().isEmpty()
                && context.getFunds() != null && !context.getFunds().isEmpty();
        
        return !hasMinimumSetup;
    }

    /**
     * Обрабатывает сообщение в контексте онбординга через AI
     */
    public ChatResponse handleOnboarding(ChatRequest request, String message, UserContext context) {
        String currentState = context.getOnboardingState();
        
        // Проверяем "skip all" / "пропустить всё" — сразу завершаем с минимальными дефолтами
        String msgLower = message.toLowerCase().trim();
        if (msgLower.contains("skip all") || msgLower.contains("skip everything") ||
            msgLower.contains("пропустить всё") || msgLower.contains("пропустить все") ||
            msgLower.contains("skip setup") || msgLower.contains("пропустить настройку")) {
            
            // Создаём минимальные дефолты если их нет
            if (context.getAccounts() == null || context.getAccounts().isEmpty()) {
                context.setAccounts(List.of("CARD", "CASH"));
            }
            if (context.getFunds() == null || context.getFunds().isEmpty()) {
                context.setFunds(List.of("GENERAL"));
            }
            context.setOnboardingState(OnboardingState.COMPLETED.name());
            userContextService.saveContext(context);
            
            log.info("User {} skipped onboarding completely", context.getUserId());
            return ChatResponse.builder()
                    .chatId(request.getChatId())
                    .success(true)
                    .message("OK! Setup skipped. Default accounts: CARD, CASH. Default category: GENERAL. " +
                            "You can now record expenses! Example: 'coffee 500 RUB'")
                    .operationsCount(0)
                    .build();
        }
        
        // Определяем текущий шаг
        // Начинаем со счетов (минимум для работы), имя/валюта — опционально
        OnboardingState state;
        if (currentState == null || currentState.isEmpty()) {
            state = OnboardingState.ASK_ACCOUNTS;
            context.setOnboardingState(state.name());
        } else {
            try {
                state = OnboardingState.valueOf(currentState);
            } catch (IllegalArgumentException e) {
                state = OnboardingState.ASK_ACCOUNTS;
                context.setOnboardingState(state.name());
            }
        }
        
        // Генерируем ответ через AI
        String aiResponse = generateOnboardingResponse(message, context, state);
        
        // Парсим ответ AI и извлекаем данные
        OnboardingResult result = parseOnboardingResponse(aiResponse, state);
        
        // Обновляем контекст на основе ответа
        updateContextFromResult(context, result, state);
        
        // Переходим к следующему шагу если данные для текущего шага получены
        boolean shouldAdvance = hasRequiredDataForState(context, state);
        
        if (shouldAdvance) {
            OnboardingState nextState = getNextState(state);
            context.setOnboardingState(nextState.name());
            log.info("Onboarding: {} -> {}", state, nextState);
        }
        
        userContextService.saveContext(context);
        
        return ChatResponse.builder()
                .chatId(request.getChatId())
                .success(true)
                .message(result.getResponseMessage())
                .operationsCount(0)
                .build();
    }

    /**
     * Генерирует ответ онбординга через AI
     */
    private String generateOnboardingResponse(String userMessage, UserContext context, OnboardingState state) {
        String systemPrompt = buildOnboardingPrompt(context, state);
        
        try {
            String requestBody = objectMapper.writeValueAsString(new java.util.HashMap<String, Object>() {{
                put("model", "gpt-4o-mini");
                put("messages", List.of(
                    new java.util.HashMap<String, String>() {{
                        put("role", "system");
                        put("content", systemPrompt);
                    }},
                    new java.util.HashMap<String, String>() {{
                        put("role", "user");
                        put("content", userMessage);
                    }}
                ));
                put("temperature", 0.7);
                put("max_tokens", 500);
            }});

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(OPENAI_API_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + AppConfig.getOpenAiApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            log.info("OpenAI response status: {}", response.statusCode());
            log.info("OpenAI raw response: {}", response.body());
            
            JsonNode root = objectMapper.readTree(response.body());
            
            // Check for API error
            if (root.has("error")) {
                String errorMsg = root.path("error").path("message").asText();
                log.error("OpenAI API error: {}", errorMsg);
                return "{\"responseMessage\": \"Error: " + errorMsg + "\", \"stepComplete\": false}";
            }
            
            JsonNode choices = root.path("choices");
            if (choices.isMissingNode() || !choices.isArray() || choices.size() == 0) {
                log.error("OpenAI response has no choices: {}", response.body());
                return "{\"responseMessage\": \"Error occurred. Please try again.\", \"stepComplete\": false}";
            }
            
            String content = choices.get(0).path("message").path("content").asText();
            log.info("OpenAI content: {}", content);
            return content;
            
        } catch (Exception e) {
            log.error("Error calling OpenAI for onboarding: {}", e.getMessage(), e);
            return "{\"responseMessage\": \"Error occurred. Please try again.\", \"stepComplete\": false}";
        }
    }

    /**
     * Строит промпт для онбординга — AI сам генерирует сообщения на языке пользователя
     */
    private String buildOnboardingPrompt(UserContext context, OnboardingState state) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("""
            You are a friendly financial assistant helping a new user set up their account.
            
            CRITICAL RULES:
            1. LANGUAGE DETECTION (MOST IMPORTANT!):
               - DETECT language from user's FIRST message IMMEDIATELY
               - "Привет", "Здравствуй", "По русски?" → Russian (ru) → respond IN RUSSIAN
               - "Hola", "Buenos días" → Spanish (es) → respond IN SPANISH  
               - "Hi", "Hello", "Hey" → English (en) → respond IN ENGLISH
               - ANY other language → detect and respond in THAT language
               - NEVER default to English if user wrote in another language!
               - Set "detectedLanguage" in EVERY response (ISO code: en, ru, sr, es, de, zh, etc.)
               - If preferredLanguage already set → use it, but still detect if user switches
            2. Be friendly, concise, use emojis sparingly
            3. NO hardcoded currencies/accounts - accept ANY valid input
            4. CURRENCIES - ALWAYS CLARIFY AMBIGUOUS, if same name currency is used in multiple countries, for example:
               - "dinar/динар" can be Serbian (RSD), Kuwaiti (KWD), Iraqi (IQD), Jordanian (JOD) and more
               - "dollar/доллар" can be US (USD), Canadian (CAD), Australian (AUD) and more
               - "peso" → ASK which: Mexican (MXN), Philippine (PHP), Argentine (ARS) and more
               - if currency is AMBIGUOUS clarify, by providing couple most popular options. for example if user says "dollar", ask something like - American dollar, right?
               - Only accept unambiguous: "euro/евро"=EUR, "yen/йена"=JPY
               - NEVER guess currency! If ambiguous → stepComplete=false, ask in clarification
            5. ACCOUNTS/FUNDS NAMING:
               - ALWAYS use ENGLISH/LATIN characters for IDs
               - Transliterate: "наличка" → "CASH", "карта" → "CARD"
               - Translate concepts: "семейные траты" → "FAMILY_EXPENSES", "личное" → "PERSONAL"
               - Keep brand names: "райфайзен" → "RAIFFEISEN", "йеттел" → "YETTEL"
            6. SKIP SUPPORT: If user says "later", "skip", "потом", "давай потом", "пропусти", "не сейчас" etc.
               → Set stepComplete=true, move to next step gracefully
            7. ALWAYS CONFIRM + NEXT STEP (VERY IMPORTANT!):
               - Step 1: CONFIRM what was saved: "✅ Saved your accounts: CARD, CASH"
               - Step 2: In SAME message, ASK next step: "Now, what are your expense categories?"
               - Step 3: Tell user progress: "Almost done!" / "Last step!"
               - NEVER leave user wondering "did it work?" or "what now?"
               - ALWAYS proactive - don't wait for user to ask "what's next?"
               - BAD: "Got it!" (user doesn't know what's next)
               - GOOD: "✅ Saved accounts: CARD, CASH. Now tell me your categories (food, transport, etc.)"
            8. IF USER TRIES TO RECORD EXPENSE DURING ONBOARDING:
               - Recognize it's an expense attempt (contains amount + description)
               - But POLITELY explain: need minimum setup first (at least 1 account + 1 fund)
               - Example: "I see you want to record '500 for coffee'! Let's quickly finish setup first - 
                 just need 1 account and 1 category. Then we can record that expense!"
               - DON'T ignore the user's intent - acknowledge it!
            9. MINIMUM SETUP REQUIRED:
               - At least 1 account (card/cash/etc)
               - At least 1 fund/category
               - Currency, defaults, name - can be set later (lazy setup)
               - Once minimum met → complete onboarding, user can record expenses
            
            CURRENT ONBOARDING STATE: %s
            
            """.formatted(state.name()));
        
        // Добавляем контекст того, что уже известно
        if (context.getPreferredLanguage() != null) {
            prompt.append("User's preferred language: ").append(context.getPreferredLanguage()).append(" (USE THIS LANGUAGE)\n");
        } else {
            prompt.append("User's preferred language: NOT SET (use English by default, switch if user asks)\n");
        }
        if (context.getDisplayName() != null) {
            prompt.append("User's name: ").append(context.getDisplayName()).append("\n");
        }
        if (context.getDefaultCurrency() != null) {
            prompt.append("User's currency: ").append(context.getDefaultCurrency()).append("\n");
        }
        if (context.getAccounts() != null && !context.getAccounts().isEmpty()) {
            prompt.append("User's accounts: ").append(String.join(", ", context.getAccounts())).append("\n");
        }
        if (context.getFunds() != null && !context.getFunds().isEmpty()) {
            prompt.append("User's funds: ").append(String.join(", ", context.getFunds())).append("\n");
        }
        
        prompt.append("\n");
        
        // Инструкции для каждого шага
        switch (state) {
            case ASK_NAME -> prompt.append("""
                TASK: Greet user warmly and ask for their name.
                If they already said their name in the message, extract it.
                
                RESPONSE FORMAT (JSON):
                {
                  "responseMessage": "Your greeting and question",
                  "extractedName": "Name if found, or null",
                  "stepComplete": true/false,
                  "detectedLanguage": "ISO code (en, ru, sr, es, etc.) if user indicated language preference, or null"
                }
                """);
                
            case ASK_CURRENCY -> prompt.append("""
                TASK: Ask user for their primary currency.
                Accept ANY currency (pesos, rubles, dinars, dollars, etc.)
                Convert to ISO 4217 code (3 letters).
                
                RESPONSE FORMAT (JSON):
                {
                  "responseMessage": "Your message in USER'S LANGUAGE",
                  "extractedCurrency": "ISO code like RUB, USD, EUR, MXN, RSD or null",
                  "stepComplete": true/false
                }
                """);
                
            case ASK_ACCOUNTS -> prompt.append("""
                TASK: This is the FIRST step! Greet user warmly, then ask for their accounts.
                If user is trying to record an expense → acknowledge it, explain need quick setup first.
                Ask user to list their accounts (cards, cash, credit cards, etc.)
                Accept any format, normalize to ENGLISH UPPER_SNAKE_CASE.
                Transliterate: "карта сбер" -> "CARD_SBER", "наличка" -> "CASH"
                
                RESPONSE FORMAT (JSON):
                {
                  "responseMessage": "Your message in USER'S LANGUAGE",
                  "extractedAccounts": ["ACCOUNT_1", "ACCOUNT_2"] or null,
                  "stepComplete": true/false
                }
                """);
                
            case ASK_FUNDS -> prompt.append("""
                TASK: Ask user to list their expense categories/funds.
                Accept any format, ALWAYS normalize to ENGLISH UPPER_SNAKE_CASE!
                
                ⚠️ CRITICAL - TRANSLITERATE TO ENGLISH:
                - "еда" → "FOOD" (NOT "ЕДА"!)
                - "транспорт" → "TRANSPORT" (NOT "ТРАНСПОРТ"!)
                - "развлечения" → "ENTERTAINMENT"
                - "семейные траты" → "FAMILY_EXPENSES"
                - "личное" → "PERSONAL"
                - "путешествия" → "TRAVEL"
                - "здоровье" → "HEALTH"
                - ANY Cyrillic → translate to English equivalent!
                
                NEVER return Cyrillic in extractedFunds! ALWAYS English!
                
                RESPONSE FORMAT (JSON):
                {
                  "responseMessage": "Your message in USER'S LANGUAGE",
                  "extractedFunds": ["FOOD", "TRANSPORT"] or null,
                  "stepComplete": true/false
                }
                """);
                
            case ASK_LINKED -> prompt.append("""
                TASK: Ask if user has a partner for shared finances (optional).
                If they say no/skip/alone, that's fine - complete the step.
                
                RESPONSE FORMAT (JSON):
                {
                  "responseMessage": "Your message in USER'S LANGUAGE",
                  "extractedPartner": "Partner name or null",
                  "stepComplete": true/false
                }
                """);
                
            case COMPLETED -> prompt.append("""
                TASK: Thank user and show summary of their setup.
                Tell them they can now start tracking expenses.
                Give example command in their language.
                
                RESPONSE FORMAT (JSON):
                {
                  "responseMessage": "Summary and example in USER'S LANGUAGE",
                  "stepComplete": true
                }
                """);
                
            default -> prompt.append("Continue onboarding conversation naturally.");
        }
        
        prompt.append("\nRESPOND WITH VALID JSON ONLY. No other text.");
        
        return prompt.toString();
    }

    /**
     * Парсит ответ AI и извлекает данные
     */
    private OnboardingResult parseOnboardingResponse(String aiResponse, OnboardingState state) {
        try {
            // Очищаем ответ от markdown если есть
            String cleaned = aiResponse
                    .replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();
            
            JsonNode root = objectMapper.readTree(cleaned);
            
            OnboardingResult result = new OnboardingResult();
            result.setResponseMessage(root.path("responseMessage").asText(""));
            result.setStepComplete(root.path("stepComplete").asBoolean(false));
            result.setExtractedName(getTextOrNull(root, "extractedName"));
            result.setExtractedCurrency(getTextOrNull(root, "extractedCurrency"));
            result.setExtractedPartner(getTextOrNull(root, "extractedPartner"));
            result.setDetectedLanguage(getTextOrNull(root, "detectedLanguage"));
            
            // Парсим массивы
            if (root.has("extractedAccounts") && root.get("extractedAccounts").isArray()) {
                List<String> accounts = new java.util.ArrayList<>();
                root.get("extractedAccounts").forEach(node -> accounts.add(node.asText()));
                result.setExtractedAccounts(accounts);
            }
            
            if (root.has("extractedFunds") && root.get("extractedFunds").isArray()) {
                List<String> funds = new java.util.ArrayList<>();
                root.get("extractedFunds").forEach(node -> funds.add(node.asText()));
                result.setExtractedFunds(funds);
            }
            
            return result;
            
        } catch (Exception e) {
            log.error("Error parsing onboarding response: {}", e.getMessage());
            OnboardingResult result = new OnboardingResult();
            result.setResponseMessage(aiResponse); // Возвращаем как есть
            result.setStepComplete(false);
            return result;
        }
    }
    
    private String getTextOrNull(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || node.asText().equals("null")) {
            return null;
        }
        return node.asText();
    }

    /**
     * Обновляет контекст пользователя на основе результата AI
     */
    private void updateContextFromResult(UserContext context, OnboardingResult result, OnboardingState state) {
        // Язык может быть определён на любом шаге
        if (result.getDetectedLanguage() != null) {
            context.setPreferredLanguage(result.getDetectedLanguage());
            log.info("Set preferred language to: {}", result.getDetectedLanguage());
        }
        
        switch (state) {
            case ASK_NAME -> {
                if (result.getExtractedName() != null) {
                    context.setDisplayName(result.getExtractedName());
                }
            }
            case ASK_CURRENCY -> {
                if (result.getExtractedCurrency() != null) {
                    context.setDefaultCurrency(result.getExtractedCurrency().toUpperCase());
                }
            }
            case ASK_ACCOUNTS -> {
                if (result.getExtractedAccounts() != null && !result.getExtractedAccounts().isEmpty()) {
                    // Нормализуем регистр: всё в UPPER_CASE
                    List<String> normalizedAccounts = result.getExtractedAccounts().stream()
                            .map(String::toUpperCase)
                            .toList();
                    context.setAccounts(normalizedAccounts);
                    // НЕ ставим дефолт автоматически — AI спросит при записи траты
                    log.info("Saved accounts (no default set): {}", normalizedAccounts);
                }
            }
            case ASK_FUNDS -> {
                if (result.getExtractedFunds() != null && !result.getExtractedFunds().isEmpty()) {
                    // Нормализуем регистр: всё в UPPER_CASE
                    List<String> normalizedFunds = result.getExtractedFunds().stream()
                            .map(String::toUpperCase)
                            .toList();
                    context.setFunds(normalizedFunds);
                    // НЕ ставим дефолты автоматически — AI спросит при записи траты
                    log.info("Saved funds (no defaults set): {}", normalizedFunds);
                }
            }
            case ASK_LINKED -> {
                if (result.getExtractedPartner() != null) {
                    context.setLinkedUsers(List.of(result.getExtractedPartner()));
                }
            }
            case COMPLETED, NOT_STARTED -> {
                // Nothing to update
            }
        }
    }

    /**
     * Проверяет, есть ли необходимые данные для перехода с текущего шага
     */
    private boolean hasRequiredDataForState(UserContext context, OnboardingState state) {
        return switch (state) {
            case ASK_ACCOUNTS -> context.getAccounts() != null && !context.getAccounts().isEmpty();
            case ASK_FUNDS -> context.getFunds() != null && !context.getFunds().isEmpty();
            case ASK_NAME -> context.getDisplayName() != null && !context.getDisplayName().isEmpty();
            case ASK_CURRENCY -> context.getDefaultCurrency() != null && !context.getDefaultCurrency().isEmpty();
            default -> true; // Для остальных шагов — всегда можно перейти
        };
    }

    /**
     * Определяет следующий шаг онбординга.
     * Порядок: счета → фонды → (MINIMUM DONE) → валюта → имя → партнёр
     * После счетов и фондов онбординг можно завершить (минимум есть)
     */
    private OnboardingState getNextState(OnboardingState current) {
        return switch (current) {
            case ASK_NAME -> OnboardingState.ASK_ACCOUNTS;  // Начинаем со счетов, не с имени
            case ASK_ACCOUNTS -> OnboardingState.ASK_FUNDS;
            case ASK_FUNDS -> OnboardingState.COMPLETED;    // После фондов — минимум готов!
            case ASK_CURRENCY -> OnboardingState.ASK_NAME;
            case ASK_LINKED, COMPLETED -> OnboardingState.COMPLETED;
            default -> OnboardingState.ASK_ACCOUNTS;        // По умолчанию спрашиваем счета
        };
    }

    /**
     * Результат парсинга ответа AI для онбординга
     */
    @lombok.Data
    private static class OnboardingResult {
        private String responseMessage;
        private boolean stepComplete;
        private String extractedName;
        private String extractedCurrency;
        private List<String> extractedAccounts;
        private List<String> extractedFunds;
        private String extractedPartner;
        private String detectedLanguage;  // ISO code: en, ru, sr, etc.
    }
}
