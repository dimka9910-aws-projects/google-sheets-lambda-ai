package com.github.dimka9910.sheets.ai.services.agents;

import com.github.dimka9910.sheets.ai.dto.actions.FinancialAction;
import com.github.dimka9910.sheets.ai.dto.actions.FinancialAction.OperationType;
import com.github.dimka9910.sheets.ai.dto.actions.MainAgentResponse;
import com.github.dimka9910.sheets.ai.dto.user.LinkedUserEntry;
import com.github.dimka9910.sheets.ai.dto.user.UserEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Lightweight handler for THIRD_PARTY_ACTION category.
 * 
 * Uses gpt-4o-mini with linked users context for fast processing.
 * Handles messages like: "to Sarah 200", "for girlfriend 1500", "from partner 500"
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ThirdPartyActionAgent {
    
    private static final String MODEL = "gpt-4o-mini";
    private static final int MAX_TOKENS = 400;
    
    private final ChatModel chatModel;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // REQUEST / RESPONSE
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * DTO for structured output parsing.
     */
    public record ThirdPartyResult(
            String operationType,  // EXPENSE or TRANSFER
            Double amount,
            String currency,
            String targetPerson,  // Linked user name
            String comment
    ) {}
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PROCESS
    // ═══════════════════════════════════════════════════════════════════════════
    
    public MainAgentResponse process(String message, UserEntity userContext) {
        
        log.info("🔷 ThirdPartyHandler processing: \"{}\"", message);
        
        try {
            // Build prompt
            String systemPrompt = buildSystemPrompt(userContext);
            String userPrompt = "User message: " + message;
            
            // Create converter for structured output
            BeanOutputConverter<ThirdPartyResult> outputConverter = new BeanOutputConverter<>(ThirdPartyResult.class);
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
            ThirdPartyResult result = outputConverter.convert(content);
            
            log.info("✅ Parsed third-party action: {} {} {} to {}", 
                    result.operationType(), result.amount(), result.currency(), result.targetPerson());
            
            // Convert to FinancialAction
            OperationType opType = "TRANSFER".equalsIgnoreCase(result.operationType()) 
                    ? OperationType.TRANSFER 
                    : OperationType.EXPENSE;
            
            String account = userContext.getDefaultAccount() != null ? 
                    userContext.getDefaultAccount().getAccountId() : null;
            
            FinancialAction action = FinancialAction.builder()
                    .operationType(opType)
                    .amount(result.amount())
                    .currency(result.currency() != null ? result.currency() : userContext.getDefaultCurrency())
                    .account(account)
                    .targetPerson(result.targetPerson())
                    .userName(userContext.getUserName())  // Current user is sender
                    .comment(result.comment())
                    .build();
            
            // Return MainAgentResponse with action
            return MainAgentResponse.builder()
                    .actions(List.of(action))
                    .response("Recorded " + result.operationType().toLowerCase() + ": " + 
                            result.amount() + " " + result.currency() + " to " + result.targetPerson())
                    .build();
            
        } catch (Exception e) {
            log.error("❌ ThirdPartyActionAgent error: {}", e.getMessage(), e);
            return MainAgentResponse.builder()
                    .actions(List.of())
                    .response("Error processing third-party action: " + e.getMessage())
                    .build();
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // BUILD PROMPT
    // ═══════════════════════════════════════════════════════════════════════════
    
    private String buildSystemPrompt(UserEntity context) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("""
                You are a lightweight parser for third-party financial actions.
                Parse messages involving other people (linked users).
                
                ## Task
                Extract: operationType, amount, currency, targetPerson, comment
                
                ## Operation Types
                - EXPENSE: Paying FOR someone, gift, shared expense
                - TRANSFER: Sending money TO someone or receiving FROM someone
                
                ## Rules
                - If currency not specified → use default
                - Match person name/alias to linked users
                - If person not found → return the name as-is (will be handled later)
                
                ## User Context
                """);
        
        // Current user
        sb.append("Current user: ").append(context.getUserName()).append("\n");
        
        // Default currency
        sb.append("Default currency: ").append(context.getDefaultCurrency()).append("\n");
        
        // Linked users with aliases
        sb.append("\nLinked users:\n");
        if (context.getLinkedUsers() != null && !context.getLinkedUsers().isEmpty()) {
            for (LinkedUserEntry linkedUser : context.getLinkedUsers()) {
                sb.append("- ").append(linkedUser.getUserName());
                if (linkedUser.getDisplayName() != null) {
                    sb.append(" (").append(linkedUser.getDisplayName()).append(")");
                }
                if (linkedUser.getAliases() != null && !linkedUser.getAliases().isEmpty()) {
                    sb.append(" [aliases: ").append(String.join(", ", linkedUser.getAliases())).append("]");
                }
                sb.append("\n");
            }
        }
        
        sb.append("""
                
                ## Examples
                "to Sarah 200" → operationType: TRANSFER, amount: 200, targetPerson: Sarah
                "for girlfriend 1500" → operationType: EXPENSE, amount: 1500, targetPerson: girlfriend
                "from partner 500 RSD" → operationType: TRANSFER, amount: 500, currency: RSD, targetPerson: partner
                "paid for wife's lunch 800" → operationType: EXPENSE, amount: 800, targetPerson: wife, comment: "lunch"
                """);
        
        return sb.toString();
    }
}

