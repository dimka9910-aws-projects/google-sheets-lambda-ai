package com.github.dimka9910.sheets.ai.services.agents;

import com.github.dimka9910.sheets.ai.dto.response.FinancialAction;
import com.github.dimka9910.sheets.ai.dto.response.FinancialAction.OperationType;
import com.github.dimka9910.sheets.ai.dto.response.FinancialAgentResponse;
import com.github.dimka9910.sheets.ai.dto.user.UserEntity;
import com.github.dimka9910.sheets.ai.services.UserContextToPromptMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import org.apache.commons.collections4.CollectionUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Universal financial agent handling ALL simple financial operations:
 * - EXPENSE (simple spending)
 * - TRANSFER (between own accounts OR with linked users)
 * - INCOME (receiving money)
 * - EXPENSE for linked users (spending on their funds)
 * 
 * Uses gpt-4o-mini for fast, cost-effective processing.
 * Replaces: SimpleExpenseAgent, InternalTransferAgent, ThirdPartyActionAgent
 */
@Slf4j
@Component
public class FinancialAgent {
    
    private static final String MODEL = "gpt-5.2";
    private static final int MAX_TOKENS = 600;
    
    private final ChatModel chatModel;
    private final UserContextToPromptMapper contextMapper;
    
    // Cache converter to avoid reflection overhead on each call
    private final BeanOutputConverter<FinancialAgentResponse> outputConverter;
    
    public FinancialAgent(ChatModel chatModel, UserContextToPromptMapper contextMapper) {
        this.chatModel = chatModel;
        this.contextMapper = contextMapper;
        // Initialize converter once (expensive reflection operation)
        this.outputConverter = new BeanOutputConverter<>(FinancialAgentResponse.class);
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PROCESS
    // ═══════════════════════════════════════════════════════════════════════════
    
    public FinancialAgentResponse process(String message, UserEntity userContext) {
        return process(message, userContext, true);
    }
    
    /**
     * Process financial operation with optional linked users context.
     * 
     * @param includeLinkedUsersContext If true, includes linked users in prompt (for third-party operations).
     *                                   If false, omits linked users to save tokens (for simple operations).
     */
    public FinancialAgentResponse process(String message, UserEntity userContext, boolean includeLinkedUsersContext) {
        log.info("🔷 FinancialAgent processing: \"{}\" (includeLinkedUsers={})", message, includeLinkedUsersContext);
        
        if (message == null || message.isBlank()) {
            return FinancialAgentResponse.builder()
                    .financialActions(List.of())
                    .message("Please send a non-empty message.")
                    .build();
        }
        
        try {
            // Build prompt and append JSON schema
            String systemPrompt = buildSystemPrompt(userContext, includeLinkedUsersContext);
            String userPrompt = "User message: " + message;
            
            // Add JSON schema to prompt (cached, no reflection overhead)
            String jsonSchema = outputConverter.getFormat();
            systemPrompt += "\n\n" + jsonSchema;
            
            // Create Spring AI Prompt
            @SuppressWarnings("null")
            Prompt prompt = new Prompt(
                    List.of(
                            new SystemMessage(systemPrompt),
                            new UserMessage(userPrompt)
                    ),
                    OpenAiChatOptions.builder()
                            .model(MODEL)
                            .maxCompletionTokens(MAX_TOKENS)
                            // GPT-5 reasoning models typically only support default temperature (1.0)
                            .temperature(1.0)
                            // Spring AI OpenAiChatOptions supports reasoningEffort for reasoning models.
                            // Supported values (per Spring AI 1.1.1 source): low | medium | high.
                            .reasoningEffort("low")
                            .build()
            );
            
            // Call LLM
            ChatResponse chatResponse = chatModel.call(prompt);
            String content = chatResponse.getResult().getOutput().getText();
            
            if (content == null || content.isBlank()) {
                log.error("❌ Empty response from LLM");
                return FinancialAgentResponse.builder()
                        .financialActions(List.of())
                        .message("I didn't get a response from the AI model. Please try again.")
                        .build();
            }
            
            // Parse FinancialAgentResponse using BeanOutputConverter
            FinancialAgentResponse result = outputConverter.convert(content);

            // Contract enforcement (guardrail-only):
            // If the model asks for clarification, it MUST NOT return any financial actions.
            // In practice models sometimes return both; we treat that as "pending only" and skip validation/saving.
            if (result != null && !CollectionUtils.isEmpty(result.getPendingClarifications())) {
                result.setFinancialActions(List.of());
                return result;
            }

            // Validate that model followed instructions.
            // If validation fails, prefer returning the model's own message (likely in user's language),
            // and only fall back to a generic message if none was provided.
            try {
                validateResult(result, userContext);
            } catch (ModelValidationException ve) {
                log.warn("⚠️ FinancialAgent model validation failed: {}", ve.getMessage());
                String msg = (result != null && result.getMessage() != null && !result.getMessage().isBlank())
                        ? result.getMessage()
                        : "Please clarify the missing/ambiguous details (e.g., account and/or fund).";
                return FinancialAgentResponse.builder()
                        .financialActions(List.of())
                        .pendingClarifications(List.of(
                                com.github.dimka9910.sheets.ai.dto.response.PendingClarificationAction.builder()
                                        .context(buildPendingContext(ve))
                                        .build()
                        ))
                        .message(msg)
                        .build();
            }
            
            log.info("✅ FinancialAgent result: {} financial actions, pending={}", 
                    !CollectionUtils.isEmpty(result.getFinancialActions()) ? result.getFinancialActions().size() : 0, 
                    !CollectionUtils.isEmpty(result.getPendingClarifications()));
            
            return result;
            
        } catch (Exception e) {
            log.error("❌ FinancialAgent error: {}", e.getMessage(), e);
            return FinancialAgentResponse.builder()
                    .financialActions(List.of())
                    .message("Something went wrong. Please try again.")
                    .build();
        }
    }
    
    // NOTE: guardrail-only mode:
    // - We do NOT modify model output (no server-side defaults, no heuristic fund/account inference).
    // - Only validations/ID guardrails and safe fallbacks remain.

    private String buildPendingContext(ModelValidationException e) {
        // Keep this short so it doesn't bloat context; detailed logs already contain full info.
        String msg = e != null && e.getMessage() != null ? e.getMessage() : "Validation failed";
        if (msg.length() > 240) return msg.substring(0, 240) + "...";
        return msg;
    }

    
    /**
     * Universal validation for all financial operation types.
     * Checks required fields based on operationType.
     */
    private void validateResult(FinancialAgentResponse response, UserEntity userContext) {
        if (response.getFinancialActions() == null) return;

        Set<String> allowedAccounts = buildAllowedAccountIds(userContext);
        Set<String> allowedFunds = buildAllowedFundIds(userContext);
        
        for (FinancialAction financial : response.getFinancialActions()) {
            OperationType type = financial.getOperationType();
            
            // Common fields for all operations
            if (financial.getAmount() == null || financial.getCurrency() == null) {
                throwValidationError("amount and currency are MANDATORY for all operations", financial);
            }

            // Guardrail: prevent invented IDs (accounts/funds)
            requireAllowed("account", financial.getAccount(), allowedAccounts);
            requireAllowed("targetAccount", financial.getTargetAccount(), allowedAccounts);
            requireAllowed("fund", financial.getFund(), allowedFunds);
            
            switch (type) {
                case EXPENSE -> {
                    // EXPENSE: account and fund are mandatory
                    // targetPerson is optional (for third-party expenses)
                    if (financial.getAccount() == null || financial.getFund() == null) {
                        throwValidationError("EXPENSE requires: amount, currency, account, fund", financial);
                    }
                    
                    // If targetPerson is set, validate it's a valid userName
                    if (financial.getTargetPerson() != null) {
                        validateUserName(financial.getTargetPerson(), userContext, "targetPerson");
                    }
                    
                    log.debug("✅ EXPENSE validated: amount={}, fund={}, targetPerson={}", 
                        financial.getAmount(), financial.getFund(), financial.getTargetPerson());
                }
                
                case TRANSFER -> {
                    // For now, TRANSFER never uses funds. Ignore any provided fund to avoid fund-related clarifications.
                    financial.setFund(null);

                    // TRANSFER: check if it's internal or with linked user
                    // Normalize userName based on context:
                    if (financial.getTargetPerson() != null && financial.getUserName() == null) {
                        // If targetPerson is set but userName is missing, assume sender is currentUser
                        financial.setUserName(userContext.getUserName());
                    } else if (financial.getUserName() != null && financial.getUserName().equals(userContext.getUserName()) && financial.getTargetPerson() == null) {
                        // If userName=currentUser but targetPerson=null, this is internal transfer (model error)
                        financial.setUserName(null);
                    }
                    
                    boolean hasUserNames = financial.getUserName() != null || financial.getTargetPerson() != null;
                    
                    if (hasUserNames) {
                        // TRANSFER with linked user: userName, targetPerson, account, targetAccount mandatory
                        if (financial.getUserName() == null || financial.getTargetPerson() == null ||
                            financial.getAccount() == null || financial.getTargetAccount() == null) {
                            throwValidationError("TRANSFER (linked user) requires: amount, currency, userName, targetPerson, account, targetAccount", financial);
                        }
                        
                        // Validate userName and targetPerson are exact userNames
                        validateUserName(financial.getUserName(), userContext, "userName");
                        validateUserName(financial.getTargetPerson(), userContext, "targetPerson");
                        
                        log.debug("✅ TRANSFER (linked user) validated: {} → {} (amount={})", 
                            financial.getUserName(), financial.getTargetPerson(), financial.getAmount());
                    } else {
                        // TRANSFER between own accounts: account and targetAccount mandatory, no userName/targetPerson
                        if (financial.getAccount() == null || financial.getTargetAccount() == null) {
                            throwValidationError("TRANSFER (between own accounts) requires: amount, currency, account, targetAccount. userName/targetPerson must be NULL.", financial);
                        }
                        
                        log.debug("✅ TRANSFER (between own accounts) validated: {} → {} (amount={})", 
                            financial.getAccount(), financial.getTargetAccount(), financial.getAmount());
                    }
                }
                
                case INCOME -> {
                    // INCOME: account is mandatory
                    if (financial.getAccount() == null) {
                        throwValidationError("INCOME requires: amount, currency, account", financial);
                    }
                    
                    log.debug("✅ INCOME validated: amount={}, account={}", 
                        financial.getAmount(), financial.getAccount());
                }
                
                default -> {
                    log.warn("⚠️ Unknown operationType: {}", type);
                }
            }
        }
    }

    private Set<String> buildAllowedAccountIds(UserEntity userContext) {
        Set<String> ids = new HashSet<>();
        if (userContext.getAccounts() != null) {
            for (var a : userContext.getAccounts()) {
                if (a != null && a.getAccountId() != null) ids.add(a.getAccountId());
            }
        }
        if (userContext.getLinkedUserEntitys() != null) {
            for (UserEntity linked : userContext.getLinkedUserEntitys().values()) {
                if (linked != null && linked.getAccounts() != null) {
                    for (var a : linked.getAccounts()) {
                        if (a != null && a.getAccountId() != null) ids.add(a.getAccountId());
                    }
                }
            }
        }
        return ids;
    }

    private Set<String> buildAllowedFundIds(UserEntity userContext) {
        Set<String> ids = new HashSet<>();
        if (userContext.getFunds() != null) {
            for (var f : userContext.getFunds()) {
                if (f != null && f.getFundId() != null) ids.add(f.getFundId());
            }
        }
        if (userContext.getLinkedUserEntitys() != null) {
            for (UserEntity linked : userContext.getLinkedUserEntitys().values()) {
                if (linked != null && linked.getFunds() != null) {
                    for (var f : linked.getFunds()) {
                        if (f != null && f.getFundId() != null) ids.add(f.getFundId());
                    }
                }
            }
        }
        return ids;
    }

    private void requireAllowed(String field, String value, Set<String> allowed) {
        if (value == null) return;
        if (!allowed.contains(value)) {
            throw new ModelValidationException(
                    "AI returned " + field + "='" + value + "' which is not present in available IDs. " +
                    "Please ask the user to choose a valid " + field + " from their list."
            );
        }
    }

    private static class ModelValidationException extends RuntimeException {
        ModelValidationException(String message) { super(message); }
    }
    
    private void throwValidationError(String requirement, FinancialAction action) {
        String errorMsg = String.format(
            "❌ Model returned invalid action. %s. " +
            "Model MUST fill all fields or return PENDING_CLARIFICATION. " +
            "Received: operationType=%s, amount=%s, currency=%s, account=%s, fund=%s, " +
            "targetAccount=%s, userName=%s, targetPerson=%s",
            requirement,
            action.getOperationType(),
            action.getAmount(),
            action.getCurrency(),
            action.getAccount(),
            action.getFund(),
            action.getTargetAccount(),
            action.getUserName(),
            action.getTargetPerson()
        );
        
        log.error(errorMsg);
        // Treat validation failures as "need clarification" instead of hard errors.
        throw new ModelValidationException(errorMsg);
    }
    
    private void validateUserName(String userName, UserEntity userContext, String fieldName) {
        String currentUserName = userContext.getUserName();
        boolean isValid = userName.equals(currentUserName) ||
                (userContext.getLinkedUsers() != null && userContext.getLinkedUsers().stream()
                        .anyMatch(lu -> lu.getUserName().equals(userName)));
        
        if (!isValid) {
            String errorMsg = String.format(
                "❌ Model returned invalid %s='%s'. " +
                "Must be EXACT userName from linked users or current user. " +
                "currentUser=%s, linkedUsers=%s",
                fieldName, userName,
                currentUserName,
                userContext.getLinkedUsers() != null ? 
                    userContext.getLinkedUsers().stream().map(lu -> lu.getUserName()).toList() : 
                    List.of()
            );
            
            log.error(errorMsg);
            throw new ModelValidationException(errorMsg);
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // BUILD PROMPT
    // ═══════════════════════════════════════════════════════════════════════════
    
    private static final String PROMPT_TEMPLATE = """
        # ROLE: High-Precision Financial Data Extractor
        Your goal: Map human language to a structured JSON financial record.
        
        ## PARSING PROTOCOL (Follow strictly):
        1. **Identify OperationType**:\s
           - TRANSFER: Movement of money between accounts (own accounts OR with linked users).
             * Between own accounts: "transfer 1000 from card to cash" → targetAccount required, targetPerson=null
             * With linked users: "sent 500 to Kiki" → targetAccount+targetPerson required (or just targetPerson if their default account)
           - INCOME: External money coming in.
           - EXPENSE: Everything else (default).
        
        2. **Map Entities (Priority: Explicit > Inferred > Default)**:
           - **Amount**:
             - If the user message contains exactly ONE clear numeric value (e.g., "200", "200.50"), it MUST be used as `amount`.
             - Do NOT ask for amount if a clear numeric value is present.
             - If there are multiple numbers and it's ambiguous which is the amount → PENDING_CLARIFICATION.
           - **Currency**:
             - If user explicitly mentions a currency name/symbol (in any language), map it to an ISO currency code if unambiguous.
             - Example (format only): "dinar" → RSD.
             - If no currency is mentioned or mapping is ambiguous → use {currency} default.
           - **Account**: Match user keyword to EXACT ID from "Available Accounts" list below.
           - **Fund**: Match user keyword to EXACT ID from "Available Funds" list below.
             
          **⚠️ CRITICAL MATCHING RULES (MUST FOLLOW):**
          
          1. **ONLY USE VALUES FROM AVAILABLE LISTS!**
             - Account MUST be one of the IDs from "Available Accounts" list below
             - Fund MUST be one of the IDs from "Available Funds" list below
             - **NEVER invent or guess values that are not in the lists!**
             - If you can't find a match → PENDING_CLARIFICATION
          
          2. **PHONETIC/TRANSLITERATION MATCHING:**
             - User may write in different script (cyrillic vs latin) - match by sound!
             - Always consider phonetic similarity across scripts
             - Example: if user writes in cyrillic but account name is in latin, match by pronunciation
          
          3. **SEMANTIC MATCHING:**
             - Match by meaning when exact word is not in the list
             - Look for semantically related fund/account in Available lists
             - If the user uses a different language, mentally translate key terms to English before matching
             - **DO NOT infer Fund from the purchase item itself.**
               - If user only describes an expense (e.g., "200 for taxi", "200 for tickets") without explicitly referencing a fund/category/budget:
                 use defaultFund if available, otherwise ask for clarification.
               - Only attempt to match a Fund when the user explicitly references a fund/category/budget (e.g., "to fund X", "category X", "budget X").
               - If user tried to reference a fund but you can't match confidently → PENDING_CLARIFICATION.
          
          4. **FALLBACK PRIORITY:**
             - First: Try to match user's word to Available list (phonetic + semantic)
             - Second: Use default ({defaultAccount} / {defaultFund}) if user didn't specify anything
             - Third: If no default AND can't match → PENDING_CLARIFICATION
          
          5. **WHEN TO ASK (PENDING_CLARIFICATION):**
             - User tried to refer something but NO match found in Available lists
             - You're not 99% confident about the match
             - No default available and user didn't specify
             - **NEVER guess or invent values!**
          
          - Custom Instructions might have special rules for matching, pay attention to them
          
                  
        3. **Handle Linked Users (If applicable)**:
           - if list of Linked Users is not empty - then user's request might be an operation with linked user.
           - first of all try to identify if user's really refers it's linked user, as user clearly should refer one.
             - targetPerson should ALWAYS be one of linked users, never invent unlisted one
             - you should match if it's a linked user by it's name of aliases
             - if user have a linked user "spouse" and says - he transfer money to wife - you can clearly match a linked user
             - if user have linked user "Robert" and says he bought something for bob - you can clearly match a linked user
             - if user have a linked user "Linda" and says he bought a present for mom - it most likely just a simple EXPENSE operation with comment "present for mom" and not a linked user operation.
             - so don't try to invent anything here, if it clearly doesn't match - it's a simple expense operation with a comment.
            - Use EXACT `userName` from the list (do not invent).
             - Never use nicknames or aliases in the `userName` or `targetPerson` fields.
             - also user's Custom Instructions might have special rules for linked user matching, so pay attention to them as well
           - So if you clearly identified that operation involves a LINKED_USER there might be two possible scenarios 
             - Transfer to a linked user, like:
               - I venmo to Alice for lunch 200 bucks
               - gave 100 to Tim from my wallet
               - transfer from my card to Wife 200
               - my wife gave me 200 euro she had
             - Payment with your own money for someone else's fund, in that case you have to make a simple EXPENSE, but pick a fund from Linked User's list.
               - Payed for Alice for her present for mom 200, on her personal budget (here you clearly have to match to some of here personal budgets like MY_DAILY_SPEND)
               - and also payed for her fuel 2000 (here you have to probably pick one of linked user's defaults)
        
        ### Linked Users:
        {linkedUsers}
        
        
        ## FIELD SPECIFIC RULES:
        - **message**: Write a detailed, natural confirmation in the user's language. If a preferred language is provided, use it: {preferredLanguage}
          **For successful operations, include ALL key details in ONE sentence:**
          - Amount + Currency
          - Operation type (spent/transferred/received)
          - Account (where from)
          - Fund/Category (for expenses) OR Target account (for transfers)
          - Target person (if applicable)
          - Comment (if provided)
          
          **Examples of good confirmations (use ACTUAL account/fund names from Available lists!):**
          - EXPENSE: "Recorded expense 200 {currency} from <ACCOUNT_FROM_LIST> to <FUND_FROM_LIST> (coffee)."
          - TRANSFER: "Recorded transfer 1000 {currency} from <ACCOUNT_FROM_LIST> to <TARGET_ACCOUNT_FROM_LIST>."
          - INCOME: "Recorded income 50000 {currency} to <ACCOUNT_FROM_LIST> (salary)."
          
          **IMPORTANT: Use ACTUAL values from Available Accounts/Funds lists, not placeholder names.**
          
          **For clarifications, ask specific question:**
          - "How much did you spend on coffee?"
          - "Which account to transfer 1000 RSD from?"
        
        # USER CONTEXT (SITUATION AWARENESS)
        - Current User: {currentUser}
        - Preferred Language: {preferredLanguage} ← If set, respond in it; otherwise respond in the same language as the user message
        - Defaults: Currency: {currency} | Account: {defaultAccount} | Fund: {defaultFund}
        
        ### Available Data:
        - Accounts: {accounts}
        - Funds: {funds}
        
        # USER'S CUSTOM INSTRUCTIONS - (Please pay attention to them, user might apply it's own rules to expense processing, anything from special rules for fund or account or currency selection, default amount, or even math operations to perform,
        or request to make one more financial operation in pair, literally anything, use rules which are relevant to this request):
        {customInstructions}
        
        ## Operation Types
        
        ### 1. EXPENSE - Simple spending (most common)
        **Required fields:** amount, currency, account, fund
        **Optional fields:** targetPerson (if spending FOR someone), comment
        
        **Important: Fund selection**
        - If user does NOT explicitly reference a fund/category/budget: you MUST use {defaultFund} (if set) or return PENDING_CLARIFICATION.
        - Do NOT infer fund from the purchase item text.
        
        ### 2. TRANSFER - Moving money between accounts
        **IMPORTANT:** TRANSFER has NO fund. Always set `fund` to null and NEVER ask about fund for TRANSFER.
        **Case A: Between OWN accounts (no people involved)**
        - **Required:** amount, currency, account (from), targetAccount (to)
        - **userName and targetPerson:** MUST BE NULL
        - **Examples:**
          - "transfer 1000 from card to cash" → TRANSFER between own accounts
          - "withdrew 500 from card" → TRANSFER from card to cash
          - "put 200 on card" → TRANSFER from cash to card
        
        ### 3. TRANSFER - Money to/from linked users
        **Required fields:** amount, currency, account, targetAccount, userName, targetPerson
        **CRITICAL:** userName and targetPerson must be EXACT userNames from Linked Users list
        **Note:** Detailed rules and examples for TRANSFER operations provided below (if linked users context is available)
        
        ### 4. INCOME - Receiving money (rare)
        **Required fields:** amount, currency, account
        **Examples:** "salary 50000", "got paid 3000"
        
      
        ## Examples (use ACTUAL values from Available lists, these are just format examples)
        
        ### EXPENSE:
        - "coffee 200" → {{"operationType": "EXPENSE", "amount": 200, "currency": "{currency}", "account": "{defaultAccount}", "fund": "<FUND_FROM_AVAILABLE_LIST>"}}
        
        ### TRANSFER (internal):
        - "transfer 1000 from card to cash" → {{"operationType": "TRANSFER", "amount": 1000, "currency": "{currency}", "account": "<ACCOUNT_FROM_LIST>", "targetAccount": "<ACCOUNT_FROM_LIST>"}}
        
        ### PENDING_CLARIFICATION (when you CAN'T find match in Available lists):
        - "coffee" → {{"context": "User wants to record coffee expense. Missing: amount."}}
        - "transfer 1000" → {{"context": "User wants to transfer 1000. Missing: source account and target account."}}
        - If user mentions something NOT in Available lists → Ask for clarification, NEVER invent values!
        
        **CRITICAL CONTRACT:**
        - If you output ANY `pendingClarifications`, then `financialActions` MUST be an empty array.
    """;
    
    // Additional section for TRANSFER operations with linked users (conditionally appended)
    private static final String THIRD_PARTY_OPERATIONS_SECTION = """
        
        ## TRANSFER Operations - Detailed Rules
        
        ### When RECEIVING money FROM linked user:
        - **userName**: linked user's userName (who SENDS)
        - **targetPerson**: current user's userName (who RECEIVES)
        - **account**: sender's account (source) - MUST be from Available Accounts
        - **targetAccount**: receiver's account (destination) - MUST be from Available Accounts
        
        ### When SENDING money TO linked user:
        - **userName**: current user's userName (who SENDS)
        - **targetPerson**: linked user's userName (who RECEIVES)
        - **account**: sender's account (source) - MUST be from Available Accounts
        - **targetAccount**: receiver's account (destination) - MUST be from Available Accounts
        
        ### userName and targetPerson Rules:
        - **CRITICAL: Must be EXACT userName from Linked Users list!**
        - Match user's words (names/aliases) to find linked user, then use their EXACT userName
        - ❌ WRONG: using aliases or nicknames in userName/targetPerson fields
        - ✅ CORRECT: using userName field value from the linked users list
        
        ### EXPENSE: Paying FOR someone on THEIR fund (Cross-user expense tracking)
        **This is NOT a TRANSFER! It's an EXPENSE where one person pays but tracks it on another person's budget.**
        
        **Scenario 1: I paid for linked user's expense → Track on THEIR fund**
        - **account**: MY account (I paid from my wallet/card)
        - **fund**: THEIR fund from their funds list (must exist in linked user's funds)
        - **targetPerson**: THEIR userName (who benefits)
        - **Use case**: Tracking expenses per person in shared finances
        
        **Scenario 2: Linked user paid for MY expense → Track on MY fund**
        - **account**: THEIR account (they paid from their wallet/card)
        - **fund**: MY fund from my funds list (must exist in current user's funds)
        - **targetPerson**: MY userName (who benefits)
        - **Use case**: Partner paid for my groceries, but it's my personal budget
        
        **CRITICAL: Fund matching logic:**
        - Look for fund in the BENEFICIARY's (targetPerson) fund list, NOT the payer's
        - Use beneficiary's default fund if fund not specified
        
        ### EXPENSE: Simple "for someone" (Generic third-party expense)
        - If user just says "bought coffee for Sarah" without specifying fund → generic expense
        - Use payer's account and payer's default fund (do NOT infer fund from the item), set targetPerson
        - Example (format only): "bought coffee for <linked user>" → {"account":"<ACCOUNT_FROM_LIST>","fund":"<FUND_FROM_LIST>","targetPerson":"<LINKED_USER_USERNAME>"}
        """;

    
    private String buildSystemPrompt(UserEntity context, boolean includeLinkedUsersContext) {
        // Step 1: Build complete prompt by concatenating sections
        StringBuilder promptBuilder = new StringBuilder(PROMPT_TEMPLATE);
        
        // Conditionally append third-party operations section (token optimization)
        if (includeLinkedUsersContext) {
            promptBuilder.append(THIRD_PARTY_OPERATIONS_SECTION);
        }
        
        // Step 2: Prepare data parameters
        Map<String, Object> params = new HashMap<>();
        params.put("currentUser", context.getUserName() != null ? context.getUserName() : "USER");
        // If user has no stored preference, we must follow the language of the user's message.
        params.put("preferredLanguage", context.getPreferredLanguage() != null ? context.getPreferredLanguage() : "same as the user's message");
        params.put("currency", context.getDefaultCurrency() != null ? context.getDefaultCurrency() : "RSD");
        // IMPORTANT (guardrail-only): do NOT use placeholder strings like "not set" that the model may echo back
        // and then fail ID validation. Empty means "no default".
        params.put("defaultAccount", context.getDefaultAccount() != null ?
                context.getDefaultAccount().getAccountId() : "");
        params.put("defaultFund", context.getDefaultFund() != null ?
                context.getDefaultFund().getFundId() : "");
        
        String accountsList = contextMapper.formatAccountsList(context.getAccounts());
        String fundsList = contextMapper.formatFundsList(context.getFunds());
        String customInstructions = contextMapper.formatCustomInstructionsSection(context.getCustomInstructions());
        
        params.put("accounts", accountsList != null ? accountsList : "(No accounts)");
        params.put("funds", fundsList != null ? fundsList : "(No funds)");
        params.put("customInstructions", customInstructions != null ? customInstructions : "");
        
        // Include linked users data if requested (token optimization)
        if (includeLinkedUsersContext) {
            // Include linked users WITH their accounts (critical for TRANSFER operations)
            String linkedUsersList = contextMapper.formatLinkedUsersListWithAccounts(
                    context.getLinkedUserEntitys(), 
                    context.getLinkedUsers());
            params.put("linkedUsers", linkedUsersList != null ? linkedUsersList : "(No linked users)");
        } else {
            params.put("linkedUsers", "");
        }
        
        // Step 3: Render final prompt WITHOUT PromptTemplate/StringTemplate (prevents syntax errors with quotes/pipes)
        String rendered = promptBuilder.toString();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            String key = "{" + e.getKey() + "}";
            String value = e.getValue() != null ? String.valueOf(e.getValue()) : "";
            rendered = rendered.replace(key, value);
        }
        return rendered;
    }
}

