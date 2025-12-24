package com.github.dimka9910.sheets.ai.services.agents;

import com.github.dimka9910.sheets.ai.dto.actions.FinancialAction;
import com.github.dimka9910.sheets.ai.dto.actions.FinancialAction.OperationType;
import com.github.dimka9910.sheets.ai.dto.telegram.TelegramChatRequest;
import com.github.dimka9910.sheets.ai.dto.telegram.TelegramChatResponse;
import com.github.dimka9910.sheets.ai.dto.user.AccountEntry;
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
 * Lightweight handler for INTERNAL_TRANSFER category.
 * 
 * Uses gpt-4o-mini with minimal context for fast processing.
 * Handles messages like: "transfer 1000 from card A to cash", "withdrew 500 from card"
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InternalTransferAgent {
    
    private static final String MODEL = "gpt-4o-mini";
    private static final int MAX_TOKENS = 300;
    
    private final ChatModel chatModel;
    private final MainAgentResultHandler resultHandler;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // REQUEST / RESPONSE
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * DTO for structured output parsing.
     */
    public record TransferResult(
            Double amount,
            String currency,
            String fromAccount,
            String toAccount,
            String comment
    ) {}
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PROCESS
    // ═══════════════════════════════════════════════════════════════════════════
    
    public TelegramChatResponse process(TelegramChatRequest chatRequest, UserEntity userContext) {
        String message = chatRequest.getMessage();
        
        log.info("🔷 InternalTransferHandler processing: \"{}\"", message);
        
        try {
            // Build prompt
            String systemPrompt = buildSystemPrompt(userContext);
            String userPrompt = "User message: " + message;
            
            // Create converter for structured output
            BeanOutputConverter<TransferResult> outputConverter = new BeanOutputConverter<>(TransferResult.class);
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
            TransferResult result = outputConverter.convert(content);
            
            log.info("✅ Parsed transfer: {} {} from {} to {}", 
                    result.amount(), result.currency(), result.fromAccount(), result.toAccount());
            
            // Convert to FinancialAction
            FinancialAction action = FinancialAction.builder()
                    .operationType(OperationType.TRANSFER)
                    .amount(result.amount())
                    .currency(result.currency() != null ? result.currency() : userContext.getDefaultCurrency())
                    .account(result.fromAccount())  // Source account
                    .targetAccount(result.toAccount())  // Target account
                    .comment(result.comment())
                    .build();
            
            // Use MainAgentResultHandler to save and build response
            com.github.dimka9910.sheets.ai.dto.actions.MainAgentResponse agentResponse = 
                    com.github.dimka9910.sheets.ai.dto.actions.MainAgentResponse.builder()
                            .actions(List.of(action))
                            .response("Recorded transfer: " + result.amount() + " " + result.currency() + 
                                    " from " + result.fromAccount() + " to " + result.toAccount())
                            .build();
            
            return resultHandler.handle(chatRequest, agentResponse, userContext);
            
        } catch (Exception e) {
            log.error("❌ InternalTransferHandler error: {}", e.getMessage(), e);
            return TelegramChatResponse.builder()
                    .chatId(chatRequest.getResponseChatId())
                    .success(false)
                    .message("Error processing transfer: " + e.getMessage())
                    .build();
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // BUILD PROMPT
    // ═══════════════════════════════════════════════════════════════════════════
    
    private String buildSystemPrompt(UserEntity context) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("""
                You are a lightweight transfer parser for a personal finance bot.
                Parse internal transfer messages (moving money between user's OWN accounts).
                
                ## Task
                Extract: amount, currency, fromAccount, toAccount, comment
                
                ## Rules
                - If currency not specified → use default
                - Match account names/aliases to user's accounts
                - If account not found → return the name as-is (will be handled later)
                
                ## User Context
                """);
        
        // Default currency
        sb.append("Default currency: ").append(context.getDefaultCurrency()).append("\n");
        
        // Available accounts with aliases
        sb.append("\nAvailable accounts:\n");
        if (context.getAccounts() != null && !context.getAccounts().isEmpty()) {
            for (AccountEntry account : context.getAccounts()) {
                sb.append("- ").append(account.getAccountId());
                if (account.getDisplayName() != null) {
                    sb.append(" (").append(account.getDisplayName()).append(")");
                }
                if (account.getAliases() != null && !account.getAliases().isEmpty()) {
                    sb.append(" [aliases: ").append(String.join(", ", account.getAliases())).append("]");
                }
                sb.append("\n");
            }
        }
        
        sb.append("""
                
                ## Examples
                "transfer 1000 from card A to cash" → amount: 1000, currency: (default), fromAccount: CARD_A, toAccount: CASH
                "withdrew 500 from card" → amount: 500, currency: (default), fromAccount: CARD, toAccount: CASH
                "move 200 USD to savings" → amount: 200, currency: USD, fromAccount: (default), toAccount: SAVINGS
                """);
        
        return sb.toString();
    }
}

