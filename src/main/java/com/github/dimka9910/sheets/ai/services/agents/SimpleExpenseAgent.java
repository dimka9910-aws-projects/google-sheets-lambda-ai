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
import org.springframework.ai.openai.api.ResponseFormat;
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
    
    // Native OpenAI JSON response format (guarantees valid JSON)
    private final ResponseFormat responseFormat;
    
    public SimpleExpenseAgent(ChatModel chatModel, UserContextToPromptMapper contextMapper) {
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
            
            // Apply defaults if needed (currency, account, fund)
            applyDefaults(result, userContext);
            
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
     * Apply user defaults to FinancialActions if fields are null.
     */
    private void applyDefaults(MainAgentResponse response, UserEntity userContext) {
        if (response.getActions() == null) return;
        
        for (var action : response.getActions()) {
            if (action instanceof FinancialAction financial) {
                // Apply default currency
                if (financial.getCurrency() == null) {
                    financial.setCurrency(userContext.getDefaultCurrency());
                }
                
                // Apply default account
                if (financial.getAccount() == null && userContext.getDefaultAccount() != null) {
                    financial.setAccount(userContext.getDefaultAccount().getAccountId());
                }
                
                // Apply default fund
                if (financial.getFund() == null && userContext.getDefaultFund() != null) {
                    financial.setFund(userContext.getDefaultFund().getFundId());
                }
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // BUILD PROMPT
    // ═══════════════════════════════════════════════════════════════════════════
    
    private static final String PROMPT_TEMPLATE = """
        You are a high-precision financial parser for a personal finance assistant.
        Your task is to extract a single EXPENSE operation from the user message.
        
        ## Core Extraction Rules
        - **Amount**: Mandatory. Must be a number. If missing or unclear -> PENDING_CLARIFICATION.
        - **Currency**: Extraction order: 1. Explicitly mentioned -> 2. Slang/Context -> 3. User default. If ambiguous -> PENDING_CLARIFICATION.
        - **Entity Selection (Account & Fund)**:
            1. **Inference**: Detect logical hints. Phrases like "paid with papers" imply a CASH account. "Treat for myself" implies a PERSONAL fund. Match against provided display names and aliases.
            2. **Default**: If no hints or explicit names are found, use the provided defaults.
            3. **Clarify**: If multiple entities match and you cannot decide -> PENDING_CLARIFICATION.
        
        ## Clarification Logic
        When creating a PENDING_CLARIFICATION action:
        - **Context Field**: Write a detailed note for yourself. Include:
          1. Information already captured (e.g., "User bought pizza").
          2. Specific missing data (e.g., "Missing amount").
          3. Reason for ambiguity (e.g., "User has multiple Visa cards, no default set").
        This note will be used in the next turn to complete the action.
        
        ## Custom Instructions
        {customInstructions}
        
        ## User Context
        - Default Currency: {currency}
        - Default Account: {defaultAccount}
        - Default Fund: {defaultFund}
        
        ### Available Accounts:
        {accounts}
        
        ### Available Funds:
        {funds}
        
        ## Examples
        - "coffee 200" -> FINANCIAL: {{"amount": 200, "fund": "FOOD", "comment": "coffee"}}
        - "3000 cash" -> FINANCIAL: {{"amount": 3000, "account": "CASH_MAIN"}}
        - "coffee" -> PENDING_CLARIFICATION: {{"context": "Recording coffee expense, but amount is missing."}}
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

