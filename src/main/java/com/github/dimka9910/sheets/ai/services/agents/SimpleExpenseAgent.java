package com.github.dimka9910.sheets.ai.services.agents;

import com.github.dimka9910.sheets.ai.dto.actions.FinancialAction;
import com.github.dimka9910.sheets.ai.dto.actions.MainAgentResponse;
import com.github.dimka9910.sheets.ai.dto.user.UserEntity;
import com.github.dimka9910.sheets.ai.services.UserContextToPromptMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
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
@RequiredArgsConstructor
public class SimpleExpenseAgent {
    
    private static final String MODEL = "gpt-4o-mini";
    private static final int MAX_TOKENS = 500;
    
    private final ChatModel chatModel;
    private final UserContextToPromptMapper contextMapper;
    private final ObjectMapper objectMapper;
    
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
            // Build prompt
            String systemPrompt = buildSystemPrompt(userContext);
            String userPrompt = "User message: " + message;
            
            // Create Spring AI Prompt
            @SuppressWarnings("null")
            Prompt prompt = new Prompt(
                    List.of(
                            new SystemMessage(systemPrompt),
                            new UserMessage(userPrompt)
                    ),
                    OpenAiChatOptions.builder()
                            .model(MODEL)
                            .maxTokens(MAX_TOKENS)
                            .temperature(0.0)  // Deterministic
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
            
            // Parse MainAgentResponse directly using ObjectMapper
            // ObjectMapper already configured with @JsonSubTypes for polymorphic deserialization
            MainAgentResponse result = objectMapper.readValue(content, MainAgentResponse.class);
            
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
            
            ## Response Format
            Return JSON in this EXACT format:
            
            ```json
            {
              "actions": [/* array of actions, see below */],
              "response": "Human-readable message to show user"
            }
            ```
            
            ## Action Types
            
            ### 1. FINANCIAL (when amount is clear):
            ```json
            {
              "type": "FINANCIAL",
              "operationType": "EXPENSE",
              "amount": 200.0,
              "currency": "RSD",  // or null to use default
              "account": "CARD_DIMA_VISA_RAIF",  // or null to use default
              "fund": "FOOD",  // category, or null to use default
              "comment": "coffee"  // optional description
            }
            ```
            
            ### 2. PENDING_CLARIFICATION (when amount is missing/unclear):
            ```json
            {
              "type": "PENDING_CLARIFICATION",
              "context": "User wants to record expense. Need: amount. Original message: coffee"
            }
            ```
            
            ## Rules
            - If amount is CLEAR → return FINANCIAL action
            - If amount is MISSING/UNCLEAR → return PENDING_CLARIFICATION action
            - Fields can be null → defaults will be applied (currency, account, fund)
            - Try to infer fund (category) from comment
            - response = friendly message to user in their language
            
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
            
            ### Example 1: Clear expense
            Input: "coffee 200"
            Output:
            ```json
            {
              "actions": [
                {
                  "type": "FINANCIAL",
                  "operationType": "EXPENSE",
                  "amount": 200.0,
                  "currency": null,
                  "account": null,
                  "fund": "FOOD",
                  "comment": "coffee"
                }
              ],
              "response": "Recorded: coffee 200 RSD (FOOD)"
            }
            ```
            
            ### Example 2: Missing amount
            Input: "coffee"
            Output:
            ```json
            {
              "actions": [
                {
                  "type": "PENDING_CLARIFICATION",
                  "context": "User wants to record expense. Need: amount. Original message: coffee"
                }
              ],
              "response": "How much did the coffee cost?"
            }
            ```
            
            ### Example 3: With account
            Input: "200 from card A"
            Output:
            ```json
            {
              "actions": [
                {
                  "type": "FINANCIAL",
                  "operationType": "EXPENSE",
                  "amount": 200.0,
                  "currency": null,
                  "account": "CARD_A",
                  "fund": null,
                  "comment": null
                }
              ],
              "response": "Recorded: 200 RSD from CARD_A"
            }
            ```
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

