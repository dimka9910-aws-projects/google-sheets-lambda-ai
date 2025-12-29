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
 * - INTERNAL_TRANSFER (between own accounts)
 * - TRANSFER to/from linked users
 * - EXPENSE for linked users
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
        log.info("🔷 FinancialAgent processing: \"{}\"", message);
        
        if (message == null || message.isBlank()) {
            return FinancialAgentResponse.builder()
                    .financialActions(List.of())
                    .message("Error: Empty message")
                    .build();
        }
        
        try {
            // Build prompt and append JSON schema
            String systemPrompt = buildSystemPrompt(userContext);
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
                        // INTERNAL_TRANSFER: account and targetAccount mandatory
                        if (financial.getAccount() == null || financial.getTargetAccount() == null) {
                            throwValidationError("TRANSFER (internal) requires: amount, currency, account, targetAccount", financial);
                        }
                        
                        log.debug("✅ TRANSFER (internal) validated: {} → {} (amount={})", 
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
        You are a high-precision financial parser for a personal finance assistant.
        Your task is to extract ONE financial operation from the user message.
        
        **ALWAYS respond in the SAME language as the user's input message unless other instructions provided.**
        
        ## RESPONSE FORMAT
        Your response must be a JSON object with:
        - `financialActions`: Array of FINANCIAL actions (max 1, empty if need clarification)
        - `pendingClarifications`: Array of PENDING_CLARIFICATION actions (empty if all data available)
        - `message`: Your response text to the user (in their language) - confirmation or question
        
        ## CRITICAL: Complete Data Rule
        **If you return a FINANCIAL action, ALL required fields for that operation type MUST be filled.**
        - Use defaults from User Context if not explicitly specified
        - If you cannot determine a value AND there is no default → return PENDING_CLARIFICATION instead
        - NEVER return a FINANCIAL action with null/empty required fields
        
        ## CRITICAL: ID Field Rule
        **ALWAYS set "id": null for new operations.**
        - The "id" field is ONLY used for editing/deleting existing operations
        - For new operations, "id" MUST be null (backend will generate UUID)
        - Do NOT generate or invent UUID values
        
        ## Operation Types
        
        ### 1. EXPENSE - Simple spending (most common)
        **Required fields:** amount, currency, account, fund
        **Optional fields:** targetPerson (if spending FOR someone), comment
        
        **Examples:**
        - "coffee 200" → EXPENSE from default account to FOOD fund
        - "taxi 500 cash" → EXPENSE from CASH account
        - "bought lunch for Sarah 1500" → EXPENSE with targetPerson (if Sarah is linked user)
        
        ### 2. TRANSFER (Internal) - Moving money between OWN accounts
        **Required fields:** amount, currency, account (from), targetAccount (to)
        **userName and targetPerson:** MUST BE NULL for internal transfers
        
        **Examples:**
        - "transfer 1000 from card to cash" → TRANSFER between own accounts
        - "withdrew 500 from card" → TRANSFER from card to cash
        - "put 200 on card" → TRANSFER from cash to card
        
        ### 3. TRANSFER (Linked User) - Money to/from another person
        **Required fields:** amount, currency, account, targetAccount, userName, targetPerson
        **CRITICAL:** userName and targetPerson must be EXACT userNames from lists below!
        
        **When RECEIVING money FROM linked user:**
        - userName: linked user's userName (who SENDS)
        - targetPerson: current user's userName (who RECEIVES)
        - account: their account (source)
        - targetAccount: my account (destination)
        - Examples: "Sarah gave me 500", "got 1000 from Bob"
        
        **When SENDING money TO linked user:**
        - userName: current user's userName (who SENDS)
        - targetPerson: linked user's userName (who RECEIVES)
        - account: my account (source)
        - targetAccount: their account (destination)
        - Examples: "sent 500 to Sarah", "gave Bob 200"
        
        ### 4. INCOME - Receiving money (rare)
        **Required fields:** amount, currency, account
        **Examples:** "salary 50000", "got paid 3000"
        
        ## Field Extraction Rules
        
        ### Amount (MANDATORY for all):
        - Must be a number
        - If missing or unclear → PENDING_CLARIFICATION
        
        ### Currency (MANDATORY for all):
        - Priority: explicit → infer from slang (e.g. "bucks" = USD) → **default currency** → clarify
        
        ### Account (MANDATORY for EXPENSE/INCOME/TRANSFER):
        - Priority: inference from hints ("cash", "card", "visa") → match against available accounts → **default account** → clarify
        
        ### Fund (MANDATORY for EXPENSE only):
        - Priority: infer from item/context ("coffee" → FOOD, "taxi" → TRANSPORT) → **default fund** → clarify
        
        ### targetAccount (MANDATORY for TRANSFER only):
        - For INTERNAL_TRANSFER: match against own accounts
        - For linked user TRANSFER: use their account from "Linked users" list
        
        ### userName and targetPerson (MANDATORY for linked user TRANSFER):
        - **CRITICAL: Must be EXACT userName from lists below!**
        - Match user's words to names/aliases, then use the EXACT userName field
        - ❌ WRONG: aliases, nicknames ("Ksyusha", "girlfriend")
        - ✅ CORRECT: userName field (e.g., "KIKI", "DIMA")
        
        ### targetPerson (OPTIONAL for EXPENSE):
        - If user spent money FOR a linked user, set this to their EXACT userName
        - Examples: "bought coffee for Sarah" → if Sarah is linked user, use her userName
        
        ## Clarification Logic
        When you CANNOT fill all required fields, return PENDING_CLARIFICATION:
        - **Context Field**: Note what's captured, what's missing, and why it's ambiguous
        
        ## Custom Instructions
        {customInstructions}
        
        ## User Context (USE THESE DEFAULTS!)
        - Current User: {currentUser} ← Use for userName/targetPerson when referring to "me"/"I"
        - Default Currency: {currency} ← USE THIS if not specified
        - Default Account: {defaultAccount} ← USE THIS if cannot infer
        - Default Fund: {defaultFund} ← USE THIS for EXPENSE if cannot infer
        
        ### Available Accounts:
        {accounts}
        
        ### Available Funds:
        {funds}
        
        ### Linked Users:
        {linkedUsers}
        
        ## Examples
        
        ### EXPENSE:
        - "coffee 200" → {{"operationType": "EXPENSE", "amount": 200, "currency": "RSD", "account": "CARD_MAIN", "fund": "FOOD"}}
        - "taxi 500 cash" → {{"operationType": "EXPENSE", "amount": 500, "currency": "RSD", "account": "CASH", "fund": "TRANSPORT"}}
        
        ### TRANSFER (internal):
        - "transfer 1000 from card to cash" → {{"operationType": "TRANSFER", "amount": 1000, "currency": "RSD", "account": "CARD_MAIN", "targetAccount": "CASH"}}
        - "withdrew 500" → {{"operationType": "TRANSFER", "amount": 500, "currency": "RSD", "account": "CARD_MAIN", "targetAccount": "CASH"}}
        
        ### TRANSFER (linked user):
        - "sent 500 to Sarah" (Sarah's userName is KIKI) → 
          {{"operationType": "TRANSFER", "amount": 500, "currency": "RSD", "userName": "DIMA", "targetPerson": "KIKI", "account": "CARD_DIMA", "targetAccount": "CARD_KIKI"}}
        
        - "Bob gave me 200" (Bob's userName is BOB) → 
          {{"operationType": "TRANSFER", "amount": 200, "currency": "RSD", "userName": "BOB", "targetPerson": "DIMA", "account": "CARD_BOB", "targetAccount": "CARD_DIMA"}}
        
        ### EXPENSE (for linked user):
        - "bought coffee for Sarah 200" (Sarah's userName is KIKI) → 
          {{"operationType": "EXPENSE", "amount": 200, "currency": "RSD", "account": "CARD_MAIN", "fund": "FOOD", "targetPerson": "KIKI"}}
        
        ### PENDING_CLARIFICATION:
        - "coffee" → {{"context": "User wants to record coffee expense. Missing: amount."}}
        - "transfer 1000" → {{"context": "User wants to transfer 1000 RSD. Missing: source account (from where?) and target account (to where?)."}}
        - "500 to friend" → {{"context": "User wants to send 500 RSD to friend. Unclear: friend is not in linked users list. Is this a linked user or regular expense?"}}
    """;
    
    private String buildSystemPrompt(UserEntity context) {
        Map<String, Object> params = new HashMap<>();
        params.put("currentUser", context.getUserName() != null ? context.getUserName() : "USER");
        params.put("currency", context.getDefaultCurrency() != null ? context.getDefaultCurrency() : "RSD");
        params.put("defaultAccount", context.getDefaultAccount() != null ? 
                context.getDefaultAccount().getAccountId() : "not set");
        params.put("defaultFund", context.getDefaultFund() != null ? 
                context.getDefaultFund().getFundId() : "not set");
        
        String accountsList = contextMapper.formatAccountsList(context.getAccounts());
        String fundsList = contextMapper.formatFundsList(context.getFunds());
        String linkedUsersList = contextMapper.formatLinkedUsersList(context.getLinkedUsers());
        String customInstructions = contextMapper.formatCustomInstructionsSection(context.getCustomInstructions());
        
        params.put("accounts", accountsList != null ? accountsList : "(No accounts)");
        params.put("funds", fundsList != null ? fundsList : "(No funds)");
        params.put("linkedUsers", linkedUsersList != null ? linkedUsersList : "(No linked users)");
        params.put("customInstructions", customInstructions != null ? customInstructions : "");
        
        PromptTemplate template = new PromptTemplate(PROMPT_TEMPLATE);
        return Objects.requireNonNull(template.render(params), "Prompt template render returned null");
    }
}

