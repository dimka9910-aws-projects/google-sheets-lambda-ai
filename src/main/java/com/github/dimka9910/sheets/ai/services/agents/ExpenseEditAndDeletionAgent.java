package com.github.dimka9910.sheets.ai.services.agents;

import com.github.dimka9910.sheets.ai.dto.actions.MainAgentResponse;
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
import org.springframework.ai.openai.api.ResponseFormat;
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
    
    // Cache converter and format for performance
    private final BeanOutputConverter<MainAgentResponse> outputConverter;
    private final ResponseFormat responseFormat;

    public ExpenseEditAndDeletionAgent(ChatModel chatModel, UserContextToPromptMapper contextMapper) {
        this.chatModel = chatModel;
        this.contextMapper = contextMapper;
        this.outputConverter = new BeanOutputConverter<>(MainAgentResponse.class);
        this.responseFormat = ResponseFormat.builder()
                .type(ResponseFormat.Type.JSON_OBJECT)
                .build();
    }

    /**
     * Process edit/deletion request.
     * Returns MainAgentResponse with FINANCIAL action (correction=true) or PENDING_CLARIFICATION.
     */
    public MainAgentResponse process(String message, UserEntity userContext) {
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
                            .responseFormat(responseFormat)
                            .build()
            );

            ChatResponse chatResponse = chatModel.call(prompt);
            String content = chatResponse.getResult().getOutput().getText();

            MainAgentResponse result = outputConverter.convert(content);

            // Validate if FINANCIAL action is present
            if (result != null && result.getActions() != null) {
                result.getActions().stream()
                        .filter(action -> action instanceof FinancialAction)
                        .map(action -> (FinancialAction) action)
                        .forEach(this::validateFinancialAction);
            }

            log.info("✅ ExpenseEditAndDeletionAgent parsed: {} actions", 
                    result.getActions() != null ? result.getActions().size() : 0);

            return result;

        } catch (Exception e) {
            log.error("❌ ExpenseEditAndDeletionAgent error: {}", e.getMessage(), e);
            return MainAgentResponse.builder()
                    .actions(List.of())
                    .response("Sorry, could not process the correction. Please try again.")
                    .build();
        }
    }

    private String buildSystemPrompt(UserEntity userContext) {
        Map<String, Object> params = new HashMap<>();

        // Format user context sections using mapper
        params.put("accounts", contextMapper.formatAccountsList(userContext.getAccounts()));
        params.put("funds", contextMapper.formatFundsList(userContext.getFunds()));
        params.put("customInstructions", contextMapper.formatCustomInstructionsSection(
                userContext.getCustomInstructions()
        ));
        
        // Default currency
        params.put("currency", userContext.getDefaultCurrency() != null 
                ? userContext.getDefaultCurrency() : "not set");
        
        // Default account and fund
        String defaultInfo = "";
        if (userContext.getDefaultAccount() != null) {
            defaultInfo += "Default Account: " + userContext.getDefaultAccount().getAccountId() + "\n";
        }
        if (userContext.getDefaultFund() != null) {
            defaultInfo += "Default Fund: " + userContext.getDefaultFund().getFundId() + "\n";
        }
        params.put("defaults", defaultInfo.isEmpty() ? "" : defaultInfo);

        // Recent conversation (critical for understanding what to edit/delete!)
        params.put("recentConversation", formatRecentConversation(userContext));

        PromptTemplate template = new PromptTemplate(PROMPT_TEMPLATE);
        return template.render(params);
    }

    private String formatRecentConversation(UserEntity userContext) {
        if (userContext.getConversationHistory() == null || userContext.getConversationHistory().isEmpty()) {
            return "No recent conversation.";
        }
        StringBuilder sb = new StringBuilder("## Recent Conversation (Last 10 messages):\n\n");
        userContext.getConversationHistory().stream()
                .skip(Math.max(0, userContext.getConversationHistory().size() - 10))
                .forEach(msg -> sb.append("**")
                        .append(msg.getRole().toUpperCase())
                        .append("**: ")
                        .append(msg.getContent())
                        .append("\n\n"));
        return sb.toString();
    }

    private void validateFinancialAction(FinancialAction action) {
        // CRITICAL: correction must be true for MODIFY/DELETE
        if (!action.isCorrection()) {
            throw new IllegalStateException(
                    "ExpenseEditAndDeletionAgent: correction flag must be TRUE. Got: " + action.isCorrection());
        }
        
        // CRITICAL: id must be present (identifies which operation to modify/delete)
        if (action.getId() == null) {
            throw new IllegalStateException(
                    "ExpenseEditAndDeletionAgent: operation ID is required for corrections. Got: null");
        }

        FinancialAction.OperationType opType = action.getOperationType();
        
        if (opType == FinancialAction.OperationType.DELETE) {
            // DELETE: validate identifying fields are present
            if (action.getAmount() == null || action.getCurrency() == null || 
                action.getAccount() == null) {
                throw new IllegalStateException(
                        "ExpenseEditAndDeletionAgent: DELETE requires identifying fields. Got: " +
                        "id=" + action.getId() +
                        ", amount=" + action.getAmount() +
                        ", currency=" + action.getCurrency() +
                        ", account=" + action.getAccount());
            }
            log.debug("Validated DELETE action with ID: {}", action.getId());
            return;
        }

        if (opType == FinancialAction.OperationType.MODIFY) {
            // MODIFY: all fields must be non-null (model must fill everything)
            if (action.getAmount() == null || action.getCurrency() == null || 
                action.getAccount() == null || action.getFund() == null) {
                throw new IllegalStateException(
                        "ExpenseEditAndDeletionAgent: MODIFY requires all fields. Got: " +
                        "id=" + action.getId() +
                        ", amount=" + action.getAmount() +
                        ", currency=" + action.getCurrency() +
                        ", account=" + action.getAccount() +
                        ", fund=" + action.getFund());
            }
            log.debug("Validated MODIFY action with ID: {}", action.getId());
            return;
        }

        throw new IllegalStateException(
                "ExpenseEditAndDeletionAgent: only MODIFY or DELETE allowed. Got: " + opType);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private static final String PROMPT_TEMPLATE = """
            # Role: Financial Operation Correction Specialist
            
            You handle corrections to existing financial operations: modifications (MODIFY) and deletions (DELETE).
            
            ## Your Task
            
            You receive enriched context from MainAgent with full operation details.
            MainAgent has already identified which operation to modify/delete and extracted all its fields.
            
            Your job:
            1. Parse the enriched message to extract operation details
            2. Apply user's correction
            3. Return FINANCIAL action with correction=true
            
            ## Enriched Message Format
            
            MainAgent sends you messages like:
            "User wants to modify operation ID=<uuid>.
             Original operation details:
               - Type: EXPENSE
               - Amount: 200
               - Currency: RSD
               - Account: CARD_VISA
               - Fund: FOOD
               - Comment: coffee
             User's correction: Change amount from 200 to 300.
             All other fields remain unchanged."
            
            ## User Context
            
            {recentConversation}
            
            {defaults}
            
            ### Available Accounts:
            {accounts}
            
            ### Available Funds:
            {funds}
            
            **Default Currency:** {currency}
            
            {customInstructions}
            
            ## Rules for MODIFY
            
            MainAgent has already extracted operation details for you:
            - operationType: "MODIFY"
            - correction: true (MANDATORY!)
            - id: UUID from enriched message (operation ID to modify)
            - Fill ALL fields with CORRECTED values
            
            **How to process:**
            1. Extract original values from enriched message
            2. Apply the specific change mentioned
            3. Keep all other fields unchanged
            
            **Example:**
            Enriched message: "Original: amount=200, currency=RSD, account=CARD_VISA, fund=FOOD. Change: amount to 300."
            
            Your MODIFY action:
            - id: <uuid from enriched message>
            - operationType: MODIFY
            - amount: 300 (changed)
            - currency: RSD (unchanged)
            - account: CARD_VISA (unchanged)
            - fund: FOOD (unchanged)
            - correction: true
            
            ## Rules for DELETE
            
            MainAgent has already identified which operation to delete:
            - operationType: "DELETE"
            - correction: true (MANDATORY!)
            - id: UUID from enriched message
            - Fill: amount, currency, account, fund (from enriched message)
            
            **Example:**
            Enriched message: "User wants to delete operation ID=<uuid>. Original: amount=200, currency=RSD..."
            
            Your DELETE action:
            - id: <uuid from enriched message>
            - operationType: DELETE
            - amount: 200 (from original)
            - currency: RSD (from original)
            - account: CARD_VISA (from original)
            - fund: FOOD (from original)
            - correction: true
            
            ## Rules for PENDING_CLARIFICATION
            
            If unclear what user wants:
            - No recent operation in conversation history → ask user to be more specific
            - Ambiguous which operation to modify → ask which one
            - Ambiguous which field to change → ask for clarification
            - Unknown account/fund mentioned → ask for clarification
            
            ## CRITICAL: Complete Data Rule
            
            For MODIFY:
            - You MUST fill ALL fields: id, amount, currency, account, fund
            - Extract original values from enriched message provided by MainAgent
            - Apply the specific correction mentioned
            - If enriched message is unclear → return PENDING_CLARIFICATION
            
            For DELETE:
            - You MUST fill: id, amount, currency, account, fund
            - Extract all values from enriched message provided by MainAgent
            - If enriched message doesn't have operation details → return PENDING_CLARIFICATION
            
            ## Entity Resolution (Accounts & Funds)
            
            1. **User mentions specific account/fund** → use it (validate against available list)
            2. **User mentions unclear account/fund** → PENDING_CLARIFICATION
            3. **User doesn't mention** → infer from recent conversation
            4. **Cannot infer** → use defaults OR PENDING_CLARIFICATION
            
            ## Strategy for Parsing Corrections
            
            1. Read recent conversation backward to find the last financial operation
            2. Look for assistant messages mentioning amounts, accounts, funds
            3. Apply user's correction instruction to that operation
            4. Fill ALL required fields
            
            ## Output Format
            
            You MUST return valid JSON with this structure:
            - "actions": array of actions (FINANCIAL with correction=true, or PENDING_CLARIFICATION)
            - "response": human-readable message to show user
            
            **Success example:**
            ```json
            {
              "actions": [
                {
                  "type": "FINANCIAL",
                  "operationType": "MODIFY",
                  "amount": 300,
                  "currency": "USD",
                  "account": "CARD_VISA",
                  "fund": "FOOD",
                  "comment": "coffee",
                  "correction": true
                }
              ],
              "response": "Changed amount to 300 USD."
            }
            ```
            
            **Clarification example:**
            ```json
            {
              "actions": [
                {
                  "type": "PENDING_CLARIFICATION",
                  "context": "User wants to modify an operation but recent conversation doesn't show any financial operations."
                }
              ],
              "response": "I don't see a recent operation to modify. Could you be more specific about which operation you want to change?"
            }
            ```
            
            Remember:
            - correction=true is MANDATORY for all FINANCIAL actions
            - For MODIFY: fill ALL fields (infer from recent conversation)
            - For DELETE: fill identifying fields (infer from recent conversation)
            - If unclear or cannot infer: return PENDING_CLARIFICATION
            """;
}

