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
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import org.apache.commons.collections4.CollectionUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
    
    private static final String MODEL = "gpt-4o-mini";
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
                    .message("Error: Empty message")
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
                            .temperature(0.0)  // Deterministic for consistent parsing
                            .build()
            );
            
            // Call LLM
            ChatResponse chatResponse = chatModel.call(prompt);
            String content = chatResponse.getResult().getOutput().getText();
            
            if (content == null || content.isBlank()) {
                log.error("❌ Empty response from LLM");
                return FinancialAgentResponse.builder()
                        .financialActions(List.of())
                        .message("Error: Empty response from AI model")
                        .build();
            }
            
            // Parse FinancialAgentResponse using BeanOutputConverter
            FinancialAgentResponse result = outputConverter.convert(content);
            
            // Validate that model followed instructions
            validateResult(result, userContext);
            
            log.info("✅ FinancialAgent result: {} financial actions, pending={}", 
                    !CollectionUtils.isEmpty(result.getFinancialActions()) ? result.getFinancialActions().size() : 0, 
                    !CollectionUtils.isEmpty(result.getPendingClarifications()));
            
            return result;
            
        } catch (Exception e) {
            log.error("❌ FinancialAgent error: {}", e.getMessage(), e);
            return FinancialAgentResponse.builder()
                    .financialActions(List.of())
                    .message("Error processing financial operation: " + e.getMessage())
                    .build();
        }
    }
    
    /**
     * Universal validation for all financial operation types.
     * Checks required fields based on operationType.
     */
    private void validateResult(FinancialAgentResponse response, UserEntity userContext) {
        if (response.getFinancialActions() == null) return;
        
        for (FinancialAction financial : response.getFinancialActions()) {
            OperationType type = financial.getOperationType();
            
            // Common fields for all operations
            if (financial.getAmount() == null || financial.getCurrency() == null) {
                throwValidationError("amount and currency are MANDATORY for all operations", financial);
            }
            
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
                    // TRANSFER: check if it's internal or with linked user
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
        throw new IllegalStateException(errorMsg);
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
            throw new IllegalStateException(errorMsg);
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
           - **Amount**: - should be provided Explicitly as number or text or slang.
           - **Currency**: Match explicit word -> Infer from slang -> Use {currency} default.
           - **Account**: Match user keyword to exact ID from "Available" lists, or default, or ask

           - **Fund**: Match user keyword to exact ID from "Available" lists.
             - *Inference*: "amex" -> AMERICAN_EXPRESS, "taxi" -> TRANSPORT, "card" -> CARD_MAIN.
             - *Fallback*: Use {defaultAccount} / {defaultFund} ONLY if inference fails.
             
          **NOTE - (GENERAL RULES FOR MATCHING FIELD VALUES)**:
          - *Inference*: try to match provided name to listed name or alias, it can be slang, short form of the name or exact match. Examples:
            - "amex" -> AMERICAN_EXPRESS
            - "family" -> FAMILY_MONTHLY_BUDGET
            - "payed with 100 dollar bill" -> clearly refers cash operations so some CASH account should be selected
          - if user clearly didn't try to refer any value - pick default
          - if no default available - PENDING_CLARIFICATION
          - If it seems like user tried to refer some value, but you are NOT 99% sure about what value to pick - better leave a PENDING_CLARIFICATION with your guess if you have any, or just ask.
          - if user refers some name, which doesn't exist in account list or fund list - leave PENDING_CLARIFICATION explaining that you can't pick one. 
          - also user's Custom Instructions might have special rules for value matching, so pay attention to them as well
          
                  
        3. **Handle Linked Users (If applicable)**:
           - if list of Linked Users is not empty - then user's request might be an operation with linked user.
           - first of all try to identify if user's really refers it's linked user, as user clearly should refer one.
             - targetPerson should ALWAYS be one of linked users, never invent unlisted one
             - you should match if it's a linked user by it's name of aliases
             - if user have a linked user "spouse" and says - he transfer money to wife - you can clearly match a linked user
             - if user have linked user "Robert" and says he bought something for bob - you can clearly match a linked user
             - if user have a linked user "Linda" and says he bought a present for mom - it most likely just a simple EXPENSE operation with comment "present for mom" and not a linked user operation.
             - so don't try to invent anything here, if it clearly doesn't match - it's a simple expense operation with a comment.
             - Use EXACT `userName` from the list (DIMA, KIKI, etc.).
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
        - **message**: Write a detailed, natural confirmation in user's preferred language: {preferredLanguage}
          **For successful operations, include ALL key details in ONE sentence:**
          - Amount + Currency
          - Operation type (spent/transferred/received)
          - Account (where from)
          - Fund/Category (for expenses) OR Target account (for transfers)
          - Target person (if applicable)
          - Comment (if provided)
          
          **Examples of good confirmations (adapt to {preferredLanguage}):**
          - EXPENSE: "Recorded expense 200 RSD from CARD_MAIN to FOOD category (coffee)."
          - EXPENSE (with targetPerson): "Recorded expense 500 RSD from CARD_MAIN to TRANSPORT category for KIKI (taxi)."
          - TRANSFER (between own accounts): "Transferred 1000 RSD from CARD_MAIN to CASH."
          - TRANSFER (to linked user): "Transferred 500 RSD from CARD_DIMA to CARD_KIKI for KIKI."
          - TRANSFER (from linked user): "Received 200 RSD from BOB from CARD_BOB to CARD_DIMA."
          - INCOME: "Recorded income 50000 RSD to CARD_MAIN (salary)."
          - Cross-user expense: "Recorded expense 2000 RSD from CARD_DIMA to TRANSPORT_KIKI category for KIKI (fuel on her budget)."
          
          **For clarifications, ask specific question:**
          - "How much did you spend on coffee?"
          - "Which account to transfer 1000 RSD from?"
        
        # USER CONTEXT (SITUATION AWARENESS)
        - Current User: {currentUser}
        - Preferred Language: {preferredLanguage} ← ALWAYS respond in this language
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
        
        **Examples:**
        - "coffee 200" → EXPENSE from default account to FOOD fund
        - "taxi 500 cash" → EXPENSE from CASH account
        - "bought lunch for Sarah 1500" → EXPENSE with targetPerson (if Sarah is linked user)
        
        ### 2. TRANSFER - Moving money between accounts
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
        
      
        ## Examples
        
        ### EXPENSE:
        - "coffee 200" → {{"operationType": "EXPENSE", "amount": 200, "currency": "RSD", "account": "CARD_MAIN", "fund": "FOOD"}}
        - "taxi 500 cash" → {{"operationType": "EXPENSE", "amount": 500, "currency": "RSD", "account": "CASH", "fund": "TRANSPORT"}}
        
        ### TRANSFER (internal):
        - "transfer 1000 from card to cash" → {{"operationType": "TRANSFER", "amount": 1000, "currency": "RSD", "account": "CARD_MAIN", "targetAccount": "CASH"}}
        - "withdrew 500" → {{"operationType": "TRANSFER", "amount": 500, "currency": "RSD", "account": "CARD_MAIN", "targetAccount": "CASH"}}
        
        ### PENDING_CLARIFICATION:
        - "coffee" → {{"context": "User wants to record coffee expense. Missing: amount."}}
        - "transfer 1000" → {{"context": "User wants to transfer 1000 RSD. Missing: source account (from where?) and target account (to where?)."}}
        - "500 to friend" → {{"context": "User wants to send 500 RSD to friend. Unclear: friend is not in linked users list. Is this a linked user or regular expense?"}}
    """;
    
    // Additional section for TRANSFER operations with linked users (conditionally appended)
    private static final String THIRD_PARTY_OPERATIONS_SECTION = """
        
        ## TRANSFER Operations - Detailed Rules
        
        ### When RECEIVING money FROM linked user:
        - **userName**: linked user's userName (who SENDS)
        - **targetPerson**: current user's userName (who RECEIVES)
        - **account**: their account (source)
        - **targetAccount**: my account (destination)
        - **Examples:** "Sarah gave me 500", "got 1000 from Bob", "Bob sent me money"
        
        ### When SENDING money TO linked user:
        - **userName**: current user's userName (who SENDS)
        - **targetPerson**: linked user's userName (who RECEIVES)
        - **account**: my account (source)
        - **targetAccount**: their account (destination)
        - **Examples:** "sent 500 to Sarah", "gave Bob 200", "transfer to girlfriend"
        
        ### userName and targetPerson Rules:
        - **CRITICAL: Must be EXACT userName from Linked Users list!**
        - Match user's words (names/aliases) to find linked user, then use their EXACT userName
        - ❌ WRONG: using aliases or nicknames in userName/targetPerson fields
        - ✅ CORRECT: using userName field value (e.g., "KIKI", "BOB", "DIMA")
        
        ### EXPENSE: Paying FOR someone on THEIR fund (Cross-user expense tracking)
        **This is NOT a TRANSFER! It's an EXPENSE where one person pays but tracks it on another person's budget.**
        
        **Scenario 1: I paid for linked user's expense → Track on THEIR fund**
        - **account**: MY account (I paid from my wallet/card)
        - **fund**: THEIR fund from their funds list (e.g., KIKI's PERSONAL, KIKI's FOOD)
        - **targetPerson**: THEIR userName (who benefits)
        - **Use case**: Tracking expenses per person in shared finances
        
        **Scenario 2: Linked user paid for MY expense → Track on MY fund**
        - **account**: THEIR account (they paid from their wallet/card)
        - **fund**: MY fund from my funds list (e.g., DIMA's PERSONAL, DIMA's FOOD)
        - **targetPerson**: MY userName (who benefits)
        - **Use case**: Partner paid for my groceries, but it's my personal budget
        
        **Examples:**
        - "Paid for Alice's present 200 on her personal budget" (Alice=KIKI) →
          {{"operationType": "EXPENSE", "amount": 200, "account": "CARD_DIMA", "fund": "PERSONAL_KIKI", "targetPerson": "KIKI", "comment": "present"}}
          ↑ I (DIMA) paid, but tracked on KIKI's PERSONAL fund
        
        - "Paid for girlfriend's fuel 2000" (girlfriend=KIKI, she has default fund) →
          {{"operationType": "EXPENSE", "amount": 2000, "account": "CARD_DIMA", "fund": "TRANSPORT_KIKI", "targetPerson": "KIKI"}}
          ↑ I paid, but tracked on KIKI's TRANSPORT fund
        
        - "Bob paid for my groceries 500" (Bob=BOB, I=DIMA) →
          {{"operationType": "EXPENSE", "amount": 500, "account": "CARD_BOB", "fund": "FOOD_DIMA", "targetPerson": "DIMA", "comment": "groceries"}}
          ↑ BOB paid, but tracked on DIMA's FOOD fund
        
        **CRITICAL: Fund matching logic:**
        - Look for fund in the BENEFICIARY's (targetPerson) fund list, NOT the payer's
        - If user says "her personal budget" → search in KIKI's funds for PERSONAL
        - If user says "my food budget" → search in DIMA's funds for FOOD
        - Use beneficiary's default fund if fund not specified
        
        ### EXPENSE: Simple "for someone" (Generic third-party expense)
        - If user just says "bought coffee for Sarah" without specifying fund → generic expense
        - Use payer's account and payer's fund (infer from item: coffee→FOOD), set targetPerson
        - Example: "bought coffee for Sarah" → {{"account": "CARD_DIMA", "fund": "FOOD", "targetPerson": "KIKI"}}
        
        ### TRANSFER Examples:
        - "sent 500 to Sarah" (Sarah's userName is KIKI) → 
          {{"operationType": "TRANSFER", "amount": 500, "currency": "RSD", "userName": "DIMA", "targetPerson": "KIKI", "account": "CARD_DIMA", "targetAccount": "CARD_KIKI"}}
        
        - "Bob gave me 200" (Bob's userName is BOB) → 
          {{"operationType": "TRANSFER", "amount": 200, "currency": "RSD", "userName": "BOB", "targetPerson": "DIMA", "account": "CARD_BOB", "targetAccount": "CARD_DIMA"}}
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
        params.put("preferredLanguage", context.getPreferredLanguage() != null ? context.getPreferredLanguage() : "English");
        params.put("currency", context.getDefaultCurrency() != null ? context.getDefaultCurrency() : "RSD");
        params.put("defaultAccount", context.getDefaultAccount() != null ? 
                context.getDefaultAccount().getAccountId() : "not set");
        params.put("defaultFund", context.getDefaultFund() != null ? 
                context.getDefaultFund().getFundId() : "not set");
        
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
        
        // Step 3: Render final prompt with data parameters
        PromptTemplate template = new PromptTemplate(promptBuilder.toString());
        return Objects.requireNonNull(template.render(params), "Prompt template render returned null");
    }
}

