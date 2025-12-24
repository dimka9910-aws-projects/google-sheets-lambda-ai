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
            String comment,
            Boolean needsClarification,
            String clarificationQuestion
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
            
            // Parse structured output
            ExpenseResult result = outputConverter.convert(content);
            
            // Check if clarification is needed
            if (Boolean.TRUE.equals(result.needsClarification())) {
                String question = result.clarificationQuestion() != null ? 
                        result.clarificationQuestion() : 
                        "Please provide more details about this expense";
                
                log.info("⚠️ Clarification needed: {}", question);
                
                // Create pending clarification action
                PendingClarificationAction clarification = PendingClarificationAction.builder()
                        .context("simple_expense: " + message)
                        .build();
                
                return MainAgentResponse.builder()
                        .actions(List.of(clarification))
                        .response(question)
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
            - If currency not specified → use default
            - If account not specified → use default
            - If fund not specified → try to infer from comment OR use default
            - comment = item name or description
            
            ## Clarifications
            - If AMOUNT is missing or unclear → set needsClarification=true and ask for amount
            - If message is too vague to parse → set needsClarification=true and ask for details
            - DO NOT ask for clarification if you can use defaults (currency, account, fund)
            
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
            
            ### Normal parsing:
            "coffee 200" → amount: 200, currency: (default), account: (default), fund: FOOD, comment: "coffee", needsClarification: false
            "taxi 500 RSD" → amount: 500, currency: RSD, account: (default), fund: TRANSPORT, comment: "taxi", needsClarification: false
            "3000 cash" → amount: 3000, currency: (default), account: CASH, fund: (default), comment: null, needsClarification: false
            "200 from card A" → amount: 200, currency: (default), account: CARD_A, fund: (default), comment: null, needsClarification: false
            
            ### Clarification needed:
            "coffee" → needsClarification: true, clarificationQuestion: "How much did the coffee cost?"
            "купил" → needsClarification: true, clarificationQuestion: "What did you buy and how much did it cost?"
            "something" → needsClarification: true, clarificationQuestion: "Please specify the amount for this expense"
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

