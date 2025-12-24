package com.github.dimka9910.sheets.ai.services.agents;

import com.github.dimka9910.sheets.ai.dto.actions.FinancialAction;
import com.github.dimka9910.sheets.ai.dto.actions.FinancialAction.OperationType;
import com.github.dimka9910.sheets.ai.dto.actions.MainAgentResponse;
import com.github.dimka9910.sheets.ai.dto.actions.PendingClarificationAction;
import com.github.dimka9910.sheets.ai.dto.user.UserEntity;
import com.github.dimka9910.sheets.ai.services.UserContextToPromptMapper;
import lombok.RequiredArgsConstructor;
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
    private static final int MAX_TOKENS = 300;
    
    private final ChatModel chatModel;
    private final UserContextToPromptMapper contextMapper;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // REQUEST / RESPONSE
    // ═══════════════════════════════════════════════════════════════════════════
    
    public record Request(
            String message,
            UserEntity userContext
    ) {}
    
    /**
     * DTO for structured output parsing.
     */
    public record ExpenseResult(
            Double amount,
            String currency,
            String account,
            String fund,
            String comment
    ) {}
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PROCESS
    // ═══════════════════════════════════════════════════════════════════════════
    
    public MainAgentResponse process(String message, UserEntity userContext) {
        log.info("🔷 SimpleExpenseAgent processing: \"{}\"", message);
        
        try {
            // Build prompt
            String systemPrompt = buildSystemPrompt(userContext);
            String userPrompt = "User message: " + message;
            
            // Create converter for structured output
            BeanOutputConverter<ExpenseResult> outputConverter = new BeanOutputConverter<>(ExpenseResult.class);
            String format = outputConverter.getFormat();
            systemPrompt += "\n\n## Response Format\nReturn JSON following this schema:\n" + format;
            
            // Create Spring AI Prompt
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
            
            // Parse structured output
            ExpenseResult result = outputConverter.convert(content);
            
            // Validate: SimpleExpenseAgent can only handle messages with clear amount
            if (result.amount() == null) {
                log.warn("⚠️ Message too vague for SimpleExpenseAgent (missing amount): \"{}\"", message);
                
                // Return PendingClarificationAction (like MainAgent does)
                PendingClarificationAction clarification = PendingClarificationAction.builder()
                        .context("User wants to record expense. Need: amount. Original message: " + message)
                        .build();
                
                return MainAgentResponse.builder()
                        .actions(List.of(clarification))
                        .response("How much did that cost?")
                        .build();
            }
            
            log.info("✅ Parsed expense: {} {} {} {}", result.amount(), result.currency(), result.account(), result.fund());
            
            // Convert to FinancialAction
            String currency = result.currency() != null ? result.currency() : userContext.getDefaultCurrency();
            String account = result.account() != null ? result.account() : 
                    (userContext.getDefaultAccount() != null ? userContext.getDefaultAccount().getAccountId() : null);
            String fund = result.fund() != null ? result.fund() : 
                    (userContext.getDefaultFund() != null ? userContext.getDefaultFund().getFundId() : null);
            
            FinancialAction action = FinancialAction.builder()
                    .operationType(OperationType.EXPENSE)
                    .amount(result.amount())
                    .currency(currency)
                    .account(account)
                    .fund(fund)
                    .comment(result.comment())
                    .build();
            
            // Return MainAgentResponse with action
            return MainAgentResponse.builder()
                    .actions(List.of(action))
                    .response("Recorded expense: " + result.amount() + " " + currency + " (" + fund + ")")
                    .build();
            
        } catch (Exception e) {
            log.error("❌ SimpleExpenseAgent error: {}", e.getMessage(), e);
            return MainAgentResponse.builder()
                    .actions(List.of())
                    .response("Error processing expense: " + e.getMessage())
                    .build();
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // BUILD PROMPT
    // ═══════════════════════════════════════════════════════════════════════════
    
    private static final String PROMPT_TEMPLATE = """
            You are a lightweight expense parser for a personal finance bot.
            Parse simple expense messages (amount + optional item/category/account).
            
            ## Task
            Extract: amount, currency, account, fund (category), comment
            
            ## Rules
            - amount is MANDATORY - if missing or unclear, return amount=null
            - If currency not specified → use default (set to null in response)
            - If account not specified → use default (set to null in response)
            - If fund not specified → try to infer from comment OR use default (set to null in response)
            - comment = item name or description
            
            ## User Context
            Default currency: {currency}
            Default account: {defaultAccount}
            
            Available accounts:
            {accounts}
            
            Default fund: {defaultFund}
            
            {customInstructions}
            
            Available funds:
            {funds}
            
            ## Examples
            
            ### Valid simple expenses (with clear amount):
            "coffee 200" → amount: 200, currency: null, account: null, fund: "FOOD", comment: "coffee"
            "taxi 500 RSD" → amount: 500, currency: "RSD", account: null, fund: "TRANSPORT", comment: "taxi"
            "3000 cash" → amount: 3000, currency: null, account: "CASH", fund: null, comment: null
            "200 from card A" → amount: 200, currency: null, account: "CARD_A", fund: null, comment: null
            
            ### Invalid (no clear amount - return amount=null):
            "coffee" → amount: null, currency: null, account: null, fund: null, comment: "coffee"
            "купил" → amount: null, currency: null, account: null, fund: null, comment: null
            "something" → amount: null, currency: null, account: null, fund: null, comment: "something"
            """;
    
    private String buildSystemPrompt(UserEntity context) {
        Map<String, Object> params = new HashMap<>();
        params.put("currency", context.getDefaultCurrency());
        params.put("defaultAccount", context.getDefaultAccount() != null ? 
                context.getDefaultAccount().getAccountId() : "not set");
        params.put("defaultFund", context.getDefaultFund() != null ? 
                context.getDefaultFund().getFundId() : "not set");
        params.put("accounts", contextMapper.formatAccountsList(context.getAccounts()));
        params.put("funds", contextMapper.formatFundsList(context.getFunds()));
        params.put("customInstructions", contextMapper.formatCustomInstructionsSection(context.getCustomInstructions()));
        
        PromptTemplate template = new PromptTemplate(PROMPT_TEMPLATE);
        return template.render(params);
    }
}

