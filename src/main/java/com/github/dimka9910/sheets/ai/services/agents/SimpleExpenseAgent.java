package com.github.dimka9910.sheets.ai.services.agents;

import com.github.dimka9910.sheets.ai.dto.actions.FinancialAction;
import com.github.dimka9910.sheets.ai.dto.actions.FinancialAction.OperationType;
import com.github.dimka9910.sheets.ai.dto.actions.MainAgentResponse;
import com.github.dimka9910.sheets.ai.dto.telegram.TelegramChatRequest;
import com.github.dimka9910.sheets.ai.dto.telegram.TelegramChatResponse;
import com.github.dimka9910.sheets.ai.dto.user.FundEntry;
import com.github.dimka9910.sheets.ai.dto.user.UserEntity;
import com.github.dimka9910.sheets.ai.services.MainAgentResultHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.List;

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
    private final MainAgentResultHandler resultHandler;
    
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
            String fund,
            String comment
    ) {}
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PROCESS
    // ═══════════════════════════════════════════════════════════════════════════
    
    public TelegramChatResponse process(TelegramChatRequest chatRequest, UserEntity userContext) {
        String message = chatRequest.getMessage();
        
        log.info("🔷 SimpleExpenseHandler processing: \"{}\"", message);
        
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
            org.springframework.ai.chat.model.ChatResponse chatResponse = chatModel.call(prompt);
            String content = chatResponse.getResult().getOutput().getText();
            
            // Parse structured output
            ExpenseResult result = outputConverter.convert(content);
            
            log.info("✅ Parsed expense: {} {} {}", result.amount(), result.currency(), result.fund());
            
            // Convert to FinancialAction
            FinancialAction action = FinancialAction.builder()
                    .operationType(OperationType.EXPENSE)
                    .amount(result.amount())
                    .currency(result.currency() != null ? result.currency() : userContext.getDefaultCurrency())
                    .account(userContext.getDefaultAccount())  // Always use default for simple expense
                    .fund(result.fund() != null ? result.fund() : userContext.getDefaultFund())
                    .comment(result.comment())
                    .build();
            
            // Use MainAgentResultHandler to save and build response
            MainAgentResponse agentResponse = MainAgentResponse.builder()
                            .actions(List.of(action))
                            .response("Recorded expense: " + result.amount() + " " + result.currency() + 
                                    " (" + (result.fund() != null ? result.fund() : userContext.getDefaultFund()) + ")")
                            .build();
            
            return resultHandler.handle(chatRequest, agentResponse, userContext);
            
        } catch (Exception e) {
            log.error("❌ SimpleExpenseHandler error: {}", e.getMessage(), e);
            return TelegramChatResponse.builder()
                    .chatId(chatRequest.getResponseChatId())
                    .success(false)
                    .message("Error processing expense: " + e.getMessage())
                    .build();
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // BUILD PROMPT
    // ═══════════════════════════════════════════════════════════════════════════
    
    private String buildSystemPrompt(UserEntity context) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("""
                You are a lightweight expense parser for a personal finance bot.
                Parse simple expense messages (amount + optional item/category).
                
                ## Task
                Extract: amount, currency, fund (category), comment
                
                ## Rules
                - If currency not specified → use default
                - If fund not specified → try to infer from comment OR use default
                - comment = item name or description
                
                ## User Context
                """);
        
        // Default currency
        sb.append("Default currency: ").append(context.getDefaultCurrency()).append("\n");
        
        // Default fund
        sb.append("Default fund: ").append(context.getDefaultFund()).append("\n");
        
        // Available funds with aliases
        sb.append("\nAvailable funds:\n");
        if (context.getFunds() != null && !context.getFunds().isEmpty()) {
            for (FundEntry fund : context.getFunds()) {
                sb.append("- ").append(fund.getFundId());
                if (fund.getDisplayName() != null) {
                    sb.append(" (").append(fund.getDisplayName()).append(")");
                }
                if (fund.getAliases() != null && !fund.getAliases().isEmpty()) {
                    sb.append(" [aliases: ").append(String.join(", ", fund.getAliases())).append("]");
                }
                sb.append("\n");
            }
        }
        
        sb.append("""
                
                ## Examples
                "coffee 200" → amount: 200, currency: (default), fund: FOOD, comment: "coffee"
                "taxi 500 RSD" → amount: 500, currency: RSD, fund: TRANSPORT, comment: "taxi"
                "3000" → amount: 3000, currency: (default), fund: (default), comment: null
                """);
        
        return sb.toString();
    }
}

