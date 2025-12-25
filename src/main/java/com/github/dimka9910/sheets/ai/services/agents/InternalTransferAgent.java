package com.github.dimka9910.sheets.ai.services.agents;

import com.github.dimka9910.sheets.ai.dto.actions.FinancialAction;
import com.github.dimka9910.sheets.ai.dto.actions.FinancialAction.OperationType;
import com.github.dimka9910.sheets.ai.dto.actions.MainAgentResponse;
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
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Lightweight handler for INTERNAL_TRANSFER category.
 * 
 * Uses gpt-4o-mini with minimal context for fast processing.
 * Handles messages like: "transfer 1000 from card A to cash", "withdrew 500 from card"
 */
@Slf4j
@Component
public class InternalTransferAgent {
    
    private static final String MODEL = "gpt-4o-mini";
    private static final int MAX_TOKENS = 500;
    
    private final ChatModel chatModel;
    private final UserContextToPromptMapper contextMapper;
    
    // Cache converter to avoid reflection overhead on each call
    private final BeanOutputConverter<MainAgentResponse> outputConverter;
    
    // Native OpenAI JSON response format (guarantees valid JSON)
    private final ResponseFormat responseFormat;
    
    public InternalTransferAgent(ChatModel chatModel, UserContextToPromptMapper contextMapper) {
        this.chatModel = chatModel;
        this.contextMapper = contextMapper;
        // Initialize converter once (expensive reflection operation)
        this.outputConverter = new BeanOutputConverter<>(MainAgentResponse.class);
        // Use JSON_OBJECT mode for reliable JSON without fragile schema parsing
        // OpenAI guarantees valid JSON, BeanOutputConverter validates structure
        this.responseFormat = ResponseFormat.builder()
                .type(ResponseFormat.Type.JSON_OBJECT)
                .build();
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
    
    public MainAgentResponse process(String message, UserEntity userContext) {
        log.info("🔷 InternalTransferAgent processing: \"{}\"", message);
        
        if (message == null || message.isBlank()) {
            return MainAgentResponse.builder()
                    .actions(List.of())
                    .response("Error: Empty message")
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
                            .maxTokens(MAX_TOKENS)
                            .temperature(0.0)  // Deterministic for consistent parsing
                            .responseFormat(responseFormat)  // Guarantees valid JSON
                            .build()
            );
            
            // Call LLM
            ChatResponse chatResponse = chatModel.call(prompt);
            String content = chatResponse.getResult().getOutput().getText();
            
            if (content == null || content.isBlank()) {
                log.error("❌ Empty response from LLM");
                return MainAgentResponse.builder()
                        .actions(List.of())
                        .response("Error: Empty response from AI model")
                        .build();
            }
            
            // Parse MainAgentResponse using BeanOutputConverter
            // This handles @JsonSubTypes polymorphic deserialization automatically
            MainAgentResponse result = outputConverter.convert(content);
            
            // Validate that model followed instructions (all fields must be filled)
            validateResult(result);
            
            log.info("✅ InternalTransferAgent result: {} actions, pending={}", 
                    result.getActions().size(), result.hasPendingClarifications());
            
            return result;
            
        } catch (Exception e) {
            log.error("❌ InternalTransferAgent error: {}", e.getMessage(), e);
            return MainAgentResponse.builder()
                    .actions(List.of())
                    .response("Error processing transfer: " + e.getMessage())
                    .build();
        }
    }
    
    /**
     * Validate that model followed instructions.
     * FINANCIAL TRANSFER actions MUST have all required fields filled.
     * If fields are null, model failed to follow prompt instructions.
     */
    private void validateResult(MainAgentResponse response) {
        if (response.getActions() == null) return;
        
        for (var action : response.getActions()) {
            if (action instanceof FinancialAction financial && 
                financial.getOperationType() == OperationType.TRANSFER) {
                
                // Check that model filled all required fields for TRANSFER
                if (financial.getAmount() == null || 
                    financial.getCurrency() == null || 
                    financial.getAccount() == null ||       // fromAccount
                    financial.getTargetAccount() == null) { // toAccount
                    
                    String errorMsg = String.format(
                        "❌ Model returned TRANSFER action with null field(s). " +
                        "Model MUST fill all fields or return PENDING_CLARIFICATION. " +
                        "Fields: amount=%s, currency=%s, fromAccount=%s, toAccount=%s",
                        financial.getAmount(),
                        financial.getCurrency(),
                        financial.getAccount(),
                        financial.getTargetAccount()
                    );
                    
                    log.error(errorMsg);
                    throw new IllegalStateException(errorMsg);
                }
                
                log.debug("✅ TRANSFER action validated: amount={}, currency={}, fromAccount={}, toAccount={}", 
                    financial.getAmount(), financial.getCurrency(), financial.getAccount(), financial.getTargetAccount());
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // BUILD PROMPT
    // ═══════════════════════════════════════════════════════════════════════════
    
    private static final String PROMPT_TEMPLATE = """
         You are a high-precision transfer parser for a personal finance assistant.
         Your task is to extract an INTERNAL TRANSFER operation (moving money between user's OWN accounts).
         
         ## CRITICAL: Complete Data Rule
         **If you return a FINANCIAL action, ALL fields (amount, currency, fromAccount, toAccount) MUST be filled.**
         - Use defaults from User Context if not explicitly specified
         - If you cannot determine a value AND there is no default → return PENDING_CLARIFICATION instead
         - NEVER return a FINANCIAL action with null/empty fields
         
         ## Core Extraction Rules
         
         ### Amount (MANDATORY):
         - Must be a number. If missing or unclear → PENDING_CLARIFICATION.
         - Examples: "200", "1500.50", "3000"
         
         ### Currency (MANDATORY):
         - Extraction priority:
           1. Explicitly mentioned in message (e.g., "1000 RSD", "50 EUR")
           2. Inferred from context
           3. **Use default currency from User Context**
         - If ambiguous AND no default → PENDING_CLARIFICATION
         
         ### From Account (MANDATORY):
         - The SOURCE account where money is taken from
         - Selection priority:
           1. **Explicitly mentioned**: "from card A", "withdrew from cash"
           2. **Match**: Compare against available accounts (names/aliases)
           3. **Use default account from User Context** (if specified)
         - Keywords: "from", "withdrew", "take from", "debit from"
         - If multiple matches AND no clear choice → PENDING_CLARIFICATION
         
         ### To Account (MANDATORY):
         - The TARGET account where money is moved to
         - Selection priority:
           1. **Explicitly mentioned**: "to cash", "deposit to savings"
           2. **Match**: Compare against available accounts (names/aliases)
           3. **Infer from context**: "withdrew" usually means TO cash
         - Keywords: "to", "deposit", "move to", "credit to"
         - If cannot infer AND not specified → PENDING_CLARIFICATION
         
         ### Comment (OPTIONAL):
         - Description of the transfer
         - Can be null
         
         ## Clarification Logic
         When you CANNOT fill all required fields, return PENDING_CLARIFICATION:
         - **Context Field**: Detailed note including:
           1. Information captured (e.g., "User wants to transfer 1000 RSD")
           2. Specific missing data (e.g., "Source account not specified")
           3. Why ambiguous (e.g., "User has multiple cards, unclear which one")
         
         ## Custom Instructions
         {customInstructions}
         
         ## User Context (USE THESE DEFAULTS!)
         - Default Currency: {currency} ← USE THIS if not specified in message
         - Default Account: {defaultAccount} ← USE THIS for fromAccount if not specified
         
         ### Available Accounts:
         {accounts}
         
         ## Examples
         
         ### Valid FINANCIAL TRANSFER (all fields filled):
         - "transfer 1000 from card A to cash" → 
           {{"amount": 1000, "currency": "RSD", "account": "CARD_A", "targetAccount": "CASH", "operationType": "TRANSFER"}}
         
         - "withdrew 500 from card" → 
           {{"amount": 500, "currency": "RSD", "account": "CARD_MAIN", "targetAccount": "CASH", "operationType": "TRANSFER"}}
         
         - "put 200 bucks on card" → 
           {{"amount": 200, "currency": "USD", "account": "CASH", "targetAccount": "CARD", "operationType": "TRANSFER"}}
         
         ### PENDING_CLARIFICATION (missing required data):
         - "transfer 1000" → 
           {{"context": "User wants to transfer 1000 RSD. Missing: source account (from where?) and target account (to where?)."}}
         
         - "from card to savings" → 
           {{"context": "User wants to transfer from card to savings. Missing: amount (how much?)."}}
         
         - "withdrew 500" → 
           {{"context": "User withdrew 500 RSD. Unclear: which account to withdraw from (CARD_A or CARD_B)?  Target is CASH."}}
     """;
    
    private String buildSystemPrompt(UserEntity context) {
        Map<String, Object> params = new HashMap<>();
        params.put("currency", context.getDefaultCurrency() != null ? context.getDefaultCurrency() : "RSD");
        params.put("defaultAccount", context.getDefaultAccount() != null ? 
                context.getDefaultAccount().getAccountId() : "not set");
        
        String accountsList = contextMapper.formatAccountsList(context.getAccounts());
        String customInstructions = contextMapper.formatCustomInstructionsSection(context.getCustomInstructions());
        
        params.put("accounts", accountsList != null ? accountsList : "(No accounts)");
        params.put("customInstructions", customInstructions != null ? customInstructions : "");
        
        PromptTemplate template = new PromptTemplate(PROMPT_TEMPLATE);
        return Objects.requireNonNull(template.render(params), "Prompt template render returned null");
    }
}
