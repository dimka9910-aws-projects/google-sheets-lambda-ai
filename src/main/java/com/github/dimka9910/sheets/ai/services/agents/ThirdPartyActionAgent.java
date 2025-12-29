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
 * Lightweight handler for THIRD_PARTY_ACTION category.
 * 
 * Uses gpt-4o-mini with linked users context for fast processing.
 * Handles messages involving other people (linked users):
 * - TRANSFER to/from linked user: "to Sarah 200", "from BOB 500"
 * - EXPENSE for linked user: "for girlfriend 1500", "bought coffee for BOB 200"
 */
@Slf4j
@Component
public class ThirdPartyActionAgent {
    
    private static final String MODEL = "gpt-4o-mini";
    private static final int MAX_TOKENS = 600;
    
    private final ChatModel chatModel;
    private final UserContextToPromptMapper contextMapper;
    
    // Cache converter to avoid reflection overhead on each call
    private final BeanOutputConverter<FinancialAgentResponse> outputConverter;
    
    public ThirdPartyActionAgent(ChatModel chatModel, UserContextToPromptMapper contextMapper) {
        this.chatModel = chatModel;
        this.contextMapper = contextMapper;
        // Initialize converter once (expensive reflection operation)
        this.outputConverter = new BeanOutputConverter<>(FinancialAgentResponse.class);
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // REQUEST / RESPONSE
    // ═══════════════════════════════════════════════════════════════════════════
    
    public record Request(
            String message,
            UserEntity userContext
    ) {}
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PROCESS
    // ═══════════════════════════════════════════════════════════════════════════
    
    public FinancialAgentResponse process(String message, UserEntity userContext) {
        log.info("🔷 ThirdPartyActionAgent processing: \"{}\"", message);
        
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
            // JSON_OBJECT mode doesn't pass schema via API, so we include it in prompt
            String jsonSchema = outputConverter.getFormat();
            systemPrompt += "\n\n" + jsonSchema;
            
            // Create Spring AI Prompt with JSON_OBJECT response format
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
            // This handles @JsonSubTypes polymorphic deserialization automatically
            FinancialAgentResponse result = outputConverter.convert(content);
            
            // Validate that model followed instructions (all fields must be filled)
            validateResult(result, userContext);
            
            log.info("✅ ThirdPartyActionAgent result: {} actions, pending={}", 
                    result.getFinancialActions().size(), !CollectionUtils.isEmpty(result.getPendingClarifications()));
            
            return result;
            
        } catch (Exception e) {
            log.error("❌ ThirdPartyActionAgent error: {}", e.getMessage(), e);
            return FinancialAgentResponse.builder()
                    .financialActions(List.of())
                    .message("Error processing third-party action: " + e.getMessage())
                    .build();
        }
    }
    
    /**
     * Validate that model followed instructions for third-party operations.
     * 
     * TRANSFER to/from linked user MUST have:
     * - amount, currency, account (source)
     * - targetAccount (destination - mandatory!)
     * - userName (who SENDS money - must be EXACT userName from linked users or current user)
     * - targetPerson (who RECEIVES money - must be EXACT userName from linked users or current user)
     * 
     * EXPENSE for linked user MUST have:
     * - amount, currency, account, fund
     * - targetPerson (who the expense is FOR - must be EXACT userName)
     */
    private void validateResult(FinancialAgentResponse response, UserEntity userContext) {
        if (response.getFinancialActions() == null) return;
        
        for (FinancialAction financial : response.getFinancialActions()) {
                
                // Validate TRANSFER to/from linked user
                if (financial.getOperationType() == OperationType.TRANSFER) {
                    // Check basic fields
                    if (financial.getAmount() == null || 
                        financial.getCurrency() == null || 
                        financial.getAccount() == null ||
                        financial.getTargetAccount() == null ||
                        financial.getUserName() == null ||
                        financial.getTargetPerson() == null) {
                        
                        String errorMsg = String.format(
                            "❌ Model returned TRANSFER (third-party) action with null field(s). " +
                            "Model MUST fill all fields or return PENDING_CLARIFICATION. " +
                            "Fields: amount=%s, currency=%s, account=%s, targetAccount=%s, userName=%s, targetPerson=%s",
                            financial.getAmount(),
                            financial.getCurrency(),
                            financial.getAccount(),
                            financial.getTargetAccount(),
                            financial.getUserName(),
                            financial.getTargetPerson()
                        );
                        
                        log.error(errorMsg);
                        throw new IllegalStateException(errorMsg);
                    }
                    
                    // Validate userName and targetPerson are EXACT userNames (not aliases)
                    String currentUserName = userContext.getUserName();
                    boolean userNameValid = financial.getUserName().equals(currentUserName) ||
                            userContext.getLinkedUsers().stream()
                                    .anyMatch(lu -> lu.getUserName().equals(financial.getUserName()));
                    
                    boolean targetPersonValid = financial.getTargetPerson().equals(currentUserName) ||
                            userContext.getLinkedUsers().stream()
                                    .anyMatch(lu -> lu.getUserName().equals(financial.getTargetPerson()));
                    
                    if (!userNameValid || !targetPersonValid) {
                        String errorMsg = String.format(
                            "❌ Model returned TRANSFER with invalid userName/targetPerson. " +
                            "Must be EXACT userName from linked users or current user. " +
                            "userName=%s (valid=%s), targetPerson=%s (valid=%s), currentUser=%s, linkedUsers=%s",
                            financial.getUserName(), userNameValid,
                            financial.getTargetPerson(), targetPersonValid,
                            currentUserName,
                            userContext.getLinkedUsers().stream().map(lu -> lu.getUserName()).toList()
                        );
                        
                        log.error(errorMsg);
                        throw new IllegalStateException(errorMsg);
                    }
                    
                    log.debug("✅ TRANSFER (third-party) action validated: {} → {} (amount={}, currency={})", 
                        financial.getUserName(), financial.getTargetPerson(), 
                        financial.getAmount(), financial.getCurrency());
                }
                
                // Validate EXPENSE for linked user
                if (financial.getOperationType() == OperationType.EXPENSE) {
                    if (financial.getAmount() == null || 
                        financial.getCurrency() == null || 
                        financial.getAccount() == null ||
                        financial.getFund() == null ||
                        financial.getTargetPerson() == null) {
                        
                        String errorMsg = String.format(
                            "❌ Model returned EXPENSE (third-party) action with null field(s). " +
                            "Model MUST fill all fields or return PENDING_CLARIFICATION. " +
                            "Fields: amount=%s, currency=%s, account=%s, fund=%s, targetPerson=%s",
                            financial.getAmount(),
                            financial.getCurrency(),
                            financial.getAccount(),
                            financial.getFund(),
                            financial.getTargetPerson()
                        );
                        
                        log.error(errorMsg);
                        throw new IllegalStateException(errorMsg);
                    }
                    
                    // Validate targetPerson is EXACT userName (not alias)
                    String currentUserName = userContext.getUserName();
                    boolean targetPersonValid = financial.getTargetPerson().equals(currentUserName) ||
                            userContext.getLinkedUsers().stream()
                                    .anyMatch(lu -> lu.getUserName().equals(financial.getTargetPerson()));
                    
                    if (!targetPersonValid) {
                        String errorMsg = String.format(
                            "❌ Model returned EXPENSE with invalid targetPerson. " +
                            "Must be EXACT userName from linked users or current user. " +
                            "targetPerson=%s, currentUser=%s, linkedUsers=%s",
                            financial.getTargetPerson(),
                            currentUserName,
                            userContext.getLinkedUsers().stream().map(lu -> lu.getUserName()).toList()
                        );
                        
                        log.error(errorMsg);
                        throw new IllegalStateException(errorMsg);
                    }
                    
                    log.debug("✅ EXPENSE (third-party) action validated: for {} (amount={}, currency={}, fund={})", 
                        financial.getTargetPerson(), financial.getAmount(), 
                        financial.getCurrency(), financial.getFund());
                }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // BUILD PROMPT
    // ═══════════════════════════════════════════════════════════════════════════
    
    private static final String PROMPT_TEMPLATE = """
        You are a high-precision parser for THIRD-PARTY financial actions.
        Your task is to extract operations involving OTHER PEOPLE (linked users).
        
        **ALWAYS respond in the SAME language as the user's input message unless other instructions provided.**
        
        ## RESPONSE FORMAT
        Your response must be a JSON object with:
        - `financialActions`: Array of FINANCIAL actions (max 1 for third-party, empty if need clarification)
        - `pendingClarifications`: Array of PENDING_CLARIFICATION actions (empty if all data available)
        - `message`: Your response text to the user (in their language) - confirmation or question
        
        ## CRITICAL: Complete Data Rule
        **If you return a FINANCIAL action, ALL required fields MUST be filled.**
        - Use defaults from User Context if not explicitly specified
        - If you cannot determine a value AND there is no default → return PENDING_CLARIFICATION instead
        - NEVER return a FINANCIAL action with null/empty fields
        
        ## CRITICAL: ID Field Rule
        **ALWAYS set "id": null for new operations.**
        - The "id" field is ONLY used for editing/deleting existing operations
        - For new operations (EXPENSE/INCOME/TRANSFER), "id" MUST be null (system will generate UUID on backend)
        - Do NOT generate or invent UUID values
        
        ## Operation Types
        
        ### 1. TRANSFER to/from Linked User (Money Exchange Between People)
        **CRITICAL: Money TO/FROM linked user = TRANSFER, NOT INCOME/EXPENSE!**
        
        **When I RECEIVE money FROM linked user:**
        - operationType: TRANSFER
        - userName: linked user's EXACT userName (who SENDS - mandatory!)
        - targetPerson: current user's EXACT userName (who RECEIVES - mandatory!)
        - account: their account (source - where money comes FROM)
        - targetAccount: my account (destination - where money goes TO)
        - Examples: "BOB gave me 500", "got 500 from BOB", "received from partner 1000"
        
        **When I SEND money TO linked user:**
        - operationType: TRANSFER
        - userName: current user's EXACT userName (who SENDS - mandatory!)
        - targetPerson: linked user's EXACT userName (who RECEIVES - mandatory!)
        - account: my account (source - where money comes FROM)
        - targetAccount: their account (destination - where money goes TO)
        - Examples: "sent 1000 to BOB", "gave BOB 500", "to Sarah 200"
        
        ### 2. EXPENSE FOR Linked User (I Paid For Them)
        - operationType: EXPENSE
        - targetPerson: linked user's EXACT userName (who the expense is FOR)
        - account: my account (where I paid FROM)
        - fund: category (use their fund if specified in custom instructions)
        - Examples: "bought coffee for BOB 200", "paid for girlfriend's lunch 1500"
        
        ### 3. Person NOT in Linked Users List
        **If person mentioned is NOT in "Linked users" list:**
        - Option A: PENDING_CLARIFICATION asking which linked user they mean
        - Option B: If it's spending FOR someone (not linked user) → regular EXPENSE with comment
        - Examples: "gift for mom", "coffee with friend" → if NOT in linked users → EXPENSE with comment
        
        ## Core Extraction Rules
        
        ### Amount (MANDATORY):
        - Must be a number. If missing or unclear → PENDING_CLARIFICATION.
        
        ### Currency (MANDATORY):
        - Priority: explicit → infer from colloquial/slang terms → **default currency** → clarify
        
        ### userName and targetPerson (MANDATORY for TRANSFER):
        - **CRITICAL: Must be EXACT userName from "Linked users" list OR current user userName!**
        - ❌ WRONG: nicknames, aliases, relationship words ("girlfriend", "mom", "friend")
        - ✅ CORRECT: exact userName from "Linked users" or "Current user"
        - userName = person who SENDS money (always fill!)
        - targetPerson = person who RECEIVES money (always fill!)
        - Match user's words to names/aliases in "Linked users" list, then use the EXACT userName
        
        ### Account and targetAccount (MANDATORY for TRANSFER):
        - account = source account (where money comes FROM)
        - targetAccount = destination account (where money goes TO)
        - For TRANSFER to/from linked user: use their accounts from "Linked users" list
        - If cannot determine for sure → PENDING_CLARIFICATION (don't guess!)
        
        ### targetPerson (MANDATORY for EXPENSE):
        - The linked user who the expense is FOR
        - Must be EXACT userName from "Linked users" list
        
        ### Fund (MANDATORY for EXPENSE):
        - Category of expense
        - Priority: explicit → custom instructions → **default fund** → clarify
        
        ### Comment (OPTIONAL):
        - Description of the operation
        - Can be null
        
        ## Clarification Logic
        When you CANNOT fill all required fields, return PENDING_CLARIFICATION:
        - **Context Field**: Detailed note including:
          1. Information captured (e.g., "User sent 1000 to BOB")
          2. Specific missing data (e.g., "Target account not specified")
          3. Why ambiguous (e.g., "BOB has multiple accounts: CARD_BOB_RAIF, CASH_BOB")
        
        ## How to Identify Linked User
        - User explicitly names a linked user (by **name** or **alias** from "Linked users" list)
        - User uses relationship words: girlfriend, boyfriend, wife, husband, partner
        - User says "her", "him", "she", "he" and context implies linked user
        - Match user's words to names/aliases in "Linked users" list
        - **Then use the EXACT userName field from matched linked user!**
        
        ## Custom Instructions
        {customInstructions}
        
        ## User Context (USE THESE!)
        - Current User: {currentUser} ← Use this for userName/targetPerson when referring to "me"/"I"
        - Default Currency: {currency} ← USE THIS if not specified
        - Default Account: {defaultAccount} ← USE THIS for my account if not specified
        - Default Fund: {defaultFund} ← USE THIS for fund if not specified
        
        ### Available Accounts (for current user):
        {accounts}
        
        ### Available Funds (for current user):
        {funds}
        
        ### Linked Users:
        {linkedUsers}
        
        ## Examples
        
        ### Valid TRANSFER (linked user → me):
        - "BOB gave me 500" → 
          {{"operationType": "TRANSFER", "amount": 500, "currency": "RSD", 
            "userName": "BOB", "targetPerson": "DIMA", 
            "account": "CARD_BOB_RAIF", "targetAccount": "CARD_DIMA_VISA"}}
        
        - "from partner 1000 RSD" → 
          {{"operationType": "TRANSFER", "amount": 1000, "currency": "RSD", 
            "userName": "KIKI", "targetPerson": "DIMA", 
            "account": "CARD_KIKI_RAIF", "targetAccount": "CARD_DIMA_VISA"}}
        
        ### Valid TRANSFER (me → linked user):
        - "sent 1000 to BOB" → 
          {{"operationType": "TRANSFER", "amount": 1000, "currency": "RSD", 
            "userName": "DIMA", "targetPerson": "BOB", 
            "account": "CARD_DIMA_VISA", "targetAccount": "CARD_BOB_RAIF"}}
        
        - "to Sarah 200" (Sarah is alias for KIKI) → 
          {{"operationType": "TRANSFER", "amount": 200, "currency": "RSD", 
            "userName": "DIMA", "targetPerson": "KIKI", 
            "account": "CARD_DIMA_VISA", "targetAccount": "CARD_KIKI_RAIF"}}
        
        ### Valid EXPENSE (for linked user):
        - "bought coffee for BOB 200" → 
          {{"operationType": "EXPENSE", "amount": 200, "currency": "RSD", 
            "account": "CARD_DIMA_VISA", "fund": "FOOD", "targetPerson": "BOB"}}
        
        - "for girlfriend 1500" (girlfriend is alias for KIKI) → 
          {{"operationType": "EXPENSE", "amount": 1500, "currency": "RSD", 
            "account": "CARD_DIMA_VISA", "fund": "PERSONAL", "targetPerson": "KIKI"}}
        
        ### PENDING_CLARIFICATION (missing data):
        - "gave BOB 500" (BOB has multiple accounts) → 
          {{"context": "User sent 500 RSD to BOB. Missing: which account should I use for BOB? Available: CARD_BOB_RAIF, CASH_BOB."}}
        
        - "to Sarah 200" (Sarah is not in linked users list) → 
          {{"context": "User wants to send 200 RSD to Sarah. Unclear: Sarah is not in linked users list. Which linked user is Sarah?"}}
        
        - "from friend 1000" (friend is not in linked users list) → 
          {{"context": "User received 1000 RSD from friend. Unclear: friend is not in linked users list. Is this a linked user or regular income?"}}
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
