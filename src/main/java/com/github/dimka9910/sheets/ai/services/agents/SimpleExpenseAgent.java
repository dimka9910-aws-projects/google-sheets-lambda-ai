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
            // Build prompt with JSON schema for structured output
            String systemPrompt = buildSystemPrompt(userContext);
            String userPrompt = "User message: " + message;
            
            // Get JSON schema from cached converter (no reflection overhead)
            String jsonSchema = outputConverter.getFormat();
            
            // Append JSON schema to system prompt
            systemPrompt += "\n\n## Response Format\nReturn JSON following this schema:\n" + jsonSchema;
            
            // Create Spring AI Prompt
            // Note: responseFormat (JSON_OBJECT mode) not available in Spring AI 1.1.2
            // But BeanOutputConverter + explicit schema in prompt is reliable enough
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
            You are a lightweight expense parser for a personal finance bot.
            Parse simple expense messages (amount + optional item/category/account).
            
            ## Rules
            - If amount is CLEAR → create FINANCIAL action (type: "FINANCIAL", operationType: "EXPENSE")
            - If amount is MISSING/UNCLEAR → create PENDING_CLARIFICATION action (type: "PENDING_CLARIFICATION")
            - Use null for fields that should use defaults (currency, account, fund)
            - Try to infer fund (category) from comment (FOOD, TRANSPORT, etc.)
            - Generate friendly response message in user's language
            
            ## User Context
            Default currency: {currency}
            Default account: {defaultAccount}
            Default fund: {defaultFund}
            
            Available accounts:
            {accounts}
            
            Available funds:
            {funds}
            
            {customInstructions}
            
            ## Examples
            
            "coffee 200" → FINANCIAL: amount=200, fund="FOOD", comment="coffee"
            "taxi 500 RSD" → FINANCIAL: amount=500, currency="RSD", fund="TRANSPORT"
            "3000 cash" → FINANCIAL: amount=3000, account="CASH"
            "200 from card A" → FINANCIAL: amount=200, account="CARD_A"
            
            "coffee" → PENDING_CLARIFICATION: context="Need amount for coffee"
            "купил" → PENDING_CLARIFICATION: context="Need amount and item details"
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

