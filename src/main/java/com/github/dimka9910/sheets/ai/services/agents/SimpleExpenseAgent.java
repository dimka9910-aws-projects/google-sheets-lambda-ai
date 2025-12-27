package com.github.dimka9910.sheets.ai.services.agents;

import com.github.dimka9910.sheets.ai.dto.actions.FinancialAction;
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
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Lightweight handler for SIMPLE_EXPENSE category.
 * 
 * Uses gpt-4o-mini with minimal context for fast processing.
 * Handles messages like: "coffee 200", "taxi 500", "groceries 3000"
 */
@Slf4j
@Component
public class SimpleExpenseAgent {
    
    private static final String MODEL = "gpt-4o-mini";
    private static final int MAX_TOKENS = 500;
    
    private final ChatModel chatModel;
    private final UserContextToPromptMapper contextMapper;
    
    // Cache converter to avoid reflection overhead on each call
    private final BeanOutputConverter<MainAgentResponse> outputConverter;
    
    public SimpleExpenseAgent(ChatModel chatModel, UserContextToPromptMapper contextMapper) {
        this.chatModel = chatModel;
        this.contextMapper = contextMapper;
        // Initialize converter once (expensive reflection operation)
        this.outputConverter = new BeanOutputConverter<>(MainAgentResponse.class);
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
        log.info("🔷 SimpleExpenseAgent processing: \"{}\"", message);
        
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
                            .maxCompletionTokens(MAX_TOKENS)
                            .temperature(0.0)  // Deterministic for consistent parsing
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
            
            log.info("✅ SimpleExpenseAgent result: {} actions, pending={}", 
                    result.getActions().size(), result.hasPendingClarifications());
            
            return result;
            
        } catch (Exception e) {
            log.error("❌ SimpleExpenseAgent error: {}", e.getMessage(), e);
            return MainAgentResponse.builder()
                    .actions(List.of())
                    .response("Error processing expense: " + e.getMessage())
                    .build();
        }
    }
    
    /**
     * Validate that model followed instructions.
     * FINANCIAL actions MUST have all required fields filled (model should use defaults from context).
     * If fields are null, model failed to follow prompt instructions.
     */
    private void validateResult(MainAgentResponse response) {
        if (response.getActions() == null) return;
        
        for (var action : response.getActions()) {
            if (action instanceof FinancialAction financial) {
                // Check that model filled all required fields
                if (financial.getAmount() == null || 
                    financial.getCurrency() == null || 
                    financial.getAccount() == null || 
                    financial.getFund() == null) {
                    
                    String errorMsg = String.format(
                        "❌ Model returned FINANCIAL action with null field(s). " +
                        "Model MUST fill all fields or return PENDING_CLARIFICATION. " +
                        "Fields: amount=%s, currency=%s, account=%s, fund=%s",
                        financial.getAmount(),
                        financial.getCurrency(),
                        financial.getAccount(),
                        financial.getFund()
                    );
                    
                    log.error(errorMsg);
                    throw new IllegalStateException(errorMsg);
                }
                
                log.debug("✅ FINANCIAL action validated: amount={}, currency={}, account={}, fund={}", 
                    financial.getAmount(), financial.getCurrency(), financial.getAccount(), financial.getFund());
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // BUILD PROMPT
    // ═══════════════════════════════════════════════════════════════════════════
    
    private static final String PROMPT_TEMPLATE = """
         You are a high-precision financial parser for a personal finance assistant.
         Your task is to extract a single EXPENSE operation from the user message.
         
        **ALWAYS respond in the SAME language as the user's input message unless other instructions provided.**
        
        ## CRITICAL: Complete Data Rule
         **If you return a FINANCIAL action, ALL fields (amount, currency, account, fund) MUST be filled.**
         - Use defaults from User Context if not explicitly specified
         - If you cannot determine a value AND there is no default → return PENDING_CLARIFICATION instead
         - NEVER return a FINANCIAL action with null/empty fields
         
         ## CRITICAL: ID Field Rule
         **ALWAYS set "id": null for new operations.**
         - The "id" field is ONLY used for editing/deleting existing operations
         - For new expenses, "id" MUST be null (system will generate UUID on backend)
         - Do NOT generate or invent UUID values
         
         ## Core Extraction Rules
         
         ### Amount (MANDATORY):
         - Must be a number. If missing or unclear → PENDING_CLARIFICATION.
         - Examples: "200", "15.50", "3000"
         
       ### Currency (MANDATORY):
       - Extraction priority:
         1. Explicitly mentioned in message (e.g., "200 RSD", "50 EUR")
         2. Infer from colloquial/slang terms (e.g., "bucks" for USD, local slang for currencies)
         3. **Use default currency from User Context**
       - If ambiguous AND no default → PENDING_CLARIFICATION
         
         ### Account (MANDATORY):
         - Selection priority:
           1. **Inference**: Detect hints ("paid with cash", "from card", "visa")
           2. **Match**: Compare against available accounts (names/aliases)
           3. **Use default account from User Context**
         - If multiple matches AND no default → PENDING_CLARIFICATION
         
         ### Fund/Category (MANDATORY):
         - Selection priority:
           1. **Inference**: Infer from item/context ("coffee" → FOOD, "taxi" → TRANSPORT)
           2. **Use default fund from User Context**
         - If cannot infer AND no default → PENDING_CLARIFICATION
         
         ## Clarification Logic
         When you CANNOT fill all required fields, return PENDING_CLARIFICATION:
         - **Context Field**: Detailed note including:
           1. Information captured (e.g., "User bought coffee")
           2. Specific missing data (e.g., "Amount not specified")
           3. Why ambiguous (e.g., "User has multiple Visa cards, unclear which one")
         
         ## Custom Instructions
         {customInstructions}
         
         ## User Context (USE THESE DEFAULTS!)
         - Default Currency: {currency} ← USE THIS if not specified in message
         - Default Account: {defaultAccount} ← USE THIS if cannot infer from message
         - Default Fund: {defaultFund} ← USE THIS if cannot infer from message
         
         ### Available Accounts:
         {accounts}
         
         ### Available Funds:
         {funds}
         
         ## Examples
         
         ### Valid FINANCIAL (all fields filled):
         - "coffee 200" → {{"amount": 200, "currency": "RSD", "account": "CARD_MAIN", "fund": "FOOD", "comment": "coffee"}}
         - "3000 cash" → {{"amount": 3000, "currency": "RSD", "account": "CASH", "fund": "PERSONAL", "comment": null}}
         
         ### PENDING_CLARIFICATION (missing required data):
         - "coffee" → {{"context": "User wants to record coffee expense. Missing: amount."}}
         - "200 visa" → {{"context": "User spent 200 RSD. Unclear: which Visa card (VISA_A or VISA_B)?"}}
     """;
    
    private String buildSystemPrompt(UserEntity context) {
        Map<String, Object> params = new HashMap<>();
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
        
        PromptTemplate template = new PromptTemplate(PROMPT_TEMPLATE);
        return Objects.requireNonNull(template.render(params), "Prompt template render returned null");
    }
}

