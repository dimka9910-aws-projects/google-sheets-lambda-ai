package com.github.dimka9910.sheets.ai.services.agents;

import com.github.dimka9910.sheets.ai.dto.response.FinancialAgentResponse;
import com.github.dimka9910.sheets.ai.dto.response.FinancialAction;
import com.github.dimka9910.sheets.ai.dto.user.UserEntity;
import com.github.dimka9910.sheets.ai.services.UserContextToPromptMapper;
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

            @SuppressWarnings("null")
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
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("Empty response from correction model");
            }

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
        // 1. Basic validation (common for all corrections)
        if (!action.isCorrection()) {
            throw new IllegalStateException("Correction flag must be TRUE for this agent.");
        }
        
        if (action.getId() == null) {
            throw new IllegalStateException("Operation ID (UUID) is mandatory for corrections.");
        }

        FinancialAction.OperationType opType = action.getOperationType();

        // 2. Only MODIFY/DELETE are supported by this agent.
        // Backend applies corrections as a PATCH using UUID; other fields may be partially filled.
        switch (opType) {
            case MODIFY -> log.debug("✅ Validated MODIFY for ID: {}", action.getId());
            case DELETE -> log.debug("✅ Validated DELETE for ID: {}", action.getId());
            default -> throw new IllegalStateException("Only MODIFY or DELETE allowed. Got: " + opType);
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private static final String PROMPT_TEMPLATE_HEADER = """
            # CORRECTION SPECIALIST PROTOCOL
            
            You handle corrections to existing financial operations: MODIFY and DELETE.
            
            ## RESPONSE FORMAT
            Your response must be a JSON object with:
            - `financialActions`: Array of FINANCIAL actions (MODIFY or DELETE type, empty if need clarification)
            - `pendingClarifications`: Array of PENDING_CLARIFICATION actions (empty if all data available)
            - `message`: Your response text to the user (in their language) - **MUST BE DETAILED** confirmation or question
            
            ### MESSAGE FIELD REQUIREMENTS:
            For successful corrections, your message MUST include:
            1. **What was changed** - Be specific about which fields were modified
            2. **Original value** → **New value** (if applicable)
            3. **Full context** - amount, currency, account, fund/category, comment
            
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
            1. Extract UUID from enriched message
            2. Identify what the user wants to change
            3. Return a PATCH-like FINANCIAL action with correction=true
            
            ## RULES FOR MODIFY
            
            **Mandatory fields:**
            - `id`: UUID from enriched message (CRITICAL!)
            - `operationType`: "MODIFY"
            - `correction`: true
            
            **Patch rule (CRITICAL):**
            - Include ONLY changed fields (amount/currency/account/fund/targetAccount/comment/date).
            - Do NOT invent missing original fields. Backend will load them by UUID.
            
            **Process:**
            1. Determine which fields user wants to change
            2. Output those fields only
            3. If user is vague → ask a clarification (PENDING_CLARIFICATION)
            
            ## RULES FOR DELETE
            
            **Mandatory fields:**
            - `id`: UUID from enriched message
            - `operationType`: "DELETE"
            - `correction`: true
            
            **Patch rule:**
            - Only `id`, `operationType`, `correction` are required. Do not invent extra fields.
            
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
               - MODIFY/DELETE only
            """;
}

