package com.github.dimka9910.sheets.ai.services.agents;

import com.github.dimka9910.sheets.ai.dto.response.FinancialAgentResponse;
import com.github.dimka9910.sheets.ai.dto.actions.FinancialAction;
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
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight agent for editing and deleting existing financial operations.
 * 
 * Handles:
 * - MODIFY operations (change amount, account, fund, etc.)
 * - DELETE operations (remove existing records)
 * 
 * Uses gpt-4o-mini for fast, focused corrections processing.
 */
@Slf4j
@Component
public class ExpenseEditAndDeletionAgent {

    private static final String MODEL = "gpt-4o-mini";
    private static final int MAX_COMPLETION_TOKENS = 1000;

    private final ChatModel chatModel;
    private final UserContextToPromptMapper contextMapper;
    
    // Cache converter for performance
    private final BeanOutputConverter<FinancialAgentResponse> outputConverter;

    public ExpenseEditAndDeletionAgent(ChatModel chatModel, UserContextToPromptMapper contextMapper) {
        this.chatModel = chatModel;
        this.contextMapper = contextMapper;
        this.outputConverter = new BeanOutputConverter<>(FinancialAgentResponse.class);
    }

    /**
     * Process edit/deletion request.
     * Returns FinancialAgentResponse with FINANCIAL action (correction=true) or PENDING_CLARIFICATION.
     */
    public FinancialAgentResponse process(String message, UserEntity userContext) {
        log.info("✏️ ExpenseEditAndDeletionAgent processing: \"{}\"", truncate(message, 60));

        try {
            String systemPrompt = buildSystemPrompt(userContext);
            String userPrompt = "### User Message ###\n" + message;

            Prompt prompt = new Prompt(
                    List.of(
                            new SystemMessage(systemPrompt),
                            new UserMessage(userPrompt)
                    ),
                    OpenAiChatOptions.builder()
                            .model(MODEL)
                            .maxCompletionTokens(MAX_COMPLETION_TOKENS)
                            .temperature(0.3) // Lower temp for precise corrections
                            .build()
            );

            ChatResponse chatResponse = chatModel.call(prompt);
            String content = chatResponse.getResult().getOutput().getText();

            FinancialAgentResponse result = outputConverter.convert(content);

            // Validate if FINANCIAL action is present
            if (result != null && result.getFinancialActions() != null) {
                result.getFinancialActions().forEach(this::validateFinancialAction);
            }

            log.info("✅ ExpenseEditAndDeletionAgent parsed: {} financial actions", 
                    result.getFinancialActions() != null ? result.getFinancialActions().size() : 0);

            return result;

        } catch (Exception e) {
            log.error("❌ ExpenseEditAndDeletionAgent error: {}", e.getMessage(), e);
            return FinancialAgentResponse.builder()
                    .financialActions(List.of())
                    .message("Sorry, could not process the correction. Please try again.")
                    .build();
        }
    }

    private String buildSystemPrompt(UserEntity userContext) {
        StringBuilder sb = new StringBuilder();
        
        sb.append(PROMPT_TEMPLATE_HEADER);
        
        // Available lists (reference only, enriched message is primary)
        sb.append("\n## Available Accounts (Reference):\n");
        sb.append(contextMapper.formatAccountsList(userContext.getAccounts())).append("\n");
        
        sb.append("\n## Available Funds (Reference):\n");
        sb.append(contextMapper.formatFundsList(userContext.getFunds())).append("\n");
        
        // Defaults
        sb.append("\n## Defaults:\n");
        sb.append("- Currency: ").append(userContext.getDefaultCurrency() != null 
                ? userContext.getDefaultCurrency() : "not set").append("\n");
        if (userContext.getDefaultAccount() != null) {
            sb.append("- Account: ").append(userContext.getDefaultAccount().getAccountId()).append("\n");
        }
        if (userContext.getDefaultFund() != null) {
            sb.append("- Fund: ").append(userContext.getDefaultFund().getFundId()).append("\n");
        }
        
        // Custom instructions
        String customInstructions = contextMapper.formatCustomInstructionsSection(
                userContext.getCustomInstructions());
        if (customInstructions != null && !customInstructions.isBlank()) {
            sb.append("\n").append(customInstructions).append("\n");
        }
        
        // JSON Schema
        sb.append("\n# OUTPUT FORMAT (JSON Schema):\n");
        sb.append(outputConverter.getFormat()).append("\n");

        return sb.toString();
    }

    private void validateFinancialAction(FinancialAction action) {
        // 1. БАЗОВАЯ ПРОВЕРКА (Общая для всех коррекций)
        if (!action.isCorrection()) {
            throw new IllegalStateException("Correction flag must be TRUE for this agent.");
        }
        
        if (action.getId() == null) {
            throw new IllegalStateException("Operation ID (UUID) is mandatory for corrections.");
        }

        FinancialAction.OperationType opType = action.getOperationType();

        // 2. ПРОВЕРКА ОБЯЗАТЕЛЬНЫХ ПОЛЕЙ ДАННЫХ
        // Для DELETE и MODIFY нужны базовые поля для идентификации записи
        if (action.getAmount() == null) {
            throw new IllegalStateException("Amount is missing for correction ID=" + action.getId());
        }
        if (action.getCurrency() == null) {
            throw new IllegalStateException("Currency is missing for correction ID=" + action.getId());
        }
        if (action.getAccount() == null) {
            throw new IllegalStateException("Source Account is missing for correction ID=" + action.getId());
        }

        // 3. СПЕЦИФИЧЕСКАЯ ЛОГИКА ПО ТИПАМ ОПЕРАЦИЙ
        switch (opType) {
            case MODIFY -> validateModifyDetails(action);
            case DELETE -> log.debug("✅ Validated DELETE for ID: {}", action.getId());
            default -> throw new IllegalStateException("Only MODIFY or DELETE allowed. Got: " + opType);
        }
    }

    private void validateModifyDetails(FinancialAction action) {
        // Если это расход — фонд обязателен
        if (action.getOperationType() == FinancialAction.OperationType.EXPENSE && action.getFund() == null) {
            throw new IllegalStateException("MODIFY Expense requires a Fund ID. Got ID=" + action.getId());
        }

        // Если это перевод — нужен целевой аккаунт
        if (action.getOperationType() == FinancialAction.OperationType.TRANSFER && action.getTargetAccount() == null) {
            throw new IllegalStateException("MODIFY Transfer requires a Target Account ID. Got ID=" + action.getId());
        }
        
        log.debug("✅ Validated MODIFY for ID: {}", action.getId());
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private static final String PROMPT_TEMPLATE_HEADER = """
            # CORRECTION SPECIALIST PROTOCOL
            
            You handle corrections to existing financial operations: MODIFY and DELETE.
            
            ## HIERARCHY OF TRUTH (CRITICAL!)
            
            When processing corrections, trust this order:
            
            1. **ENRICHED MESSAGE FROM MAINAGENT** (Primary Source)
               - This is your #1 source of data
               - Contains the UUID and all operation details
               - MainAgent already did the detective work
               
            2. **USER MESSAGE** (Specific Change)
               - Use this to understand what to change
               - Example: "300 instead of 200" means amount=300
               
            3. **AVAILABLE LISTS** (Name Resolution Only)
               - Use accounts/funds lists ONLY if enriched message has ambiguous names
               - Do NOT use these to override enriched message data
            
            ## ENRICHED MESSAGE FORMAT
            
            MainAgent sends you messages like:
            
            "User wants to modify operation ID=550e8400-e29b-41d4-a716-446655440000.
             Original operation details:
               - Type: EXPENSE
               - Amount: 200
               - Currency: RSD
               - Account: CARD_VISA
               - Fund: FOOD
               - Comment: coffee
             User's correction: Change amount from 200 to 300.
             All other fields remain unchanged."
            
            Your job:
            1. Extract UUID and original values from enriched message
            2. Apply the specific change mentioned
            3. Return FINANCIAL action with correction=true
            
            ## RULES FOR MODIFY
            
            **Mandatory fields:**
            - `id`: UUID from enriched message (CRITICAL!)
            - `operationType`: "MODIFY"
            - `correction`: true
            - `amount`, `currency`, `account`: Always required
            - `fund`: Required for EXPENSE, optional for INCOME
            - `targetAccount`: Required for TRANSFER
            
            **Process:**
            1. Extract ALL original values from enriched message
            2. Apply the specific change mentioned by user
            3. Keep all other fields unchanged
            
            **Special rules by operation type:**
            - **EXPENSE**: Must have `fund` field
            - **INCOME**: `fund` is optional
            - **TRANSFER**: Must have both `account` (source) and `targetAccount` (destination)
            
            ## RULES FOR DELETE
            
            **Mandatory fields:**
            - `id`: UUID from enriched message
            - `operationType`: "DELETE"
            - `correction`: true
            - `amount`, `currency`, `account`: For identification/verification
            
            **Process:**
            Extract all identifying fields from enriched message.
            
            ## PENDING_CLARIFICATION
            
            Use ONLY if:
            - Enriched message doesn't contain operation details
            - Enriched message is ambiguous or corrupted
            - Cannot parse UUID or required fields
            
            **Context field should explain:**
            - What data is missing from enriched message
            - What question to ask user
            
            ## CRITICAL RULES
            
            1. **Trust enriched message first** - it contains the authoritative UUID and data
            2. **correction=true is MANDATORY** for all FINANCIAL actions from this agent
            3. **Return PURE JSON** - no markdown, no comments
            4. **Validate operation type logic:**
               - EXPENSE → needs fund
               - TRANSFER → needs targetAccount
               - INCOME → fund optional
            """;
}

