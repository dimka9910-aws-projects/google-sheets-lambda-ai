package com.github.dimka9910.sheets.ai.services.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimka9910.sheets.ai.dto.actions.CustomInstructionActionBase;
import com.github.dimka9910.sheets.ai.dto.user.LinkedUserEntry;
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

/**
 * Spring Component for custom instruction management using Spring AI + OpenAI.
 * 
 * Leverages Spring AI features:
 * - ChatModel for LLM calls
 * - Automatic retry and error handling
 * 
 * Responsibilities:
 * - Classify new instructions to correct context (aliases, defaults, custom instructions)
 * - Manage conflicts and optimize user context
 * - Add/remove/update instructions intelligently
 * - Ask for clarification when uncertain
 * 
 * Model: gpt-5-mini
 */
@Slf4j
@Component
public class CustomInstructionAgent {

    private static final String MODEL = "gpt-5-mini";
    private static final int MAX_COMPLETION_TOKENS = 1500;

    public record Request(
            List<String> instructions,
            UserEntity userEntity
    ) {}

    public record Response(
            List<CustomInstructionActionBase> actions,
            String explanation,
            String errorMessage
    ) {
        public boolean isSuccess() {
            return errorMessage == null;
        }
        
        public boolean hasActions() {
            return actions != null && !actions.isEmpty();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PROMPT TEMPLATE (Spring AI PromptTemplate with placeholders)
    // ═══════════════════════════════════════════════════════════════════════════
    
    private static final String PROMPT_TEMPLATE = """
            # Role: Custom Instruction Manager
            
            You manage user's preferences, aliases, and custom rules for their financial tracking system.
            
            ## Your Capabilities
            
            1. **Linked User Aliases** - nicknames for people
               Example: "remember I call ALICE as sweetie" → add alias "sweetie" to linked user ALICE
            
            2. **Account Aliases** - nicknames for accounts
               Example: "main is my primary card" → add alias "main" to account
            
            3. **Fund Aliases** - nicknames for categories
               Example: "cafe is FOOD" → add alias "cafe" to fund FOOD
            
            4. **Default Updates** - permanent preferences
               Example: "I always spend in EUR" → update defaultCurrency to EUR
            
            5. **Custom Instructions** - complex rules
               Example: "highway = 100 dollars from cash in family budget"
            
            ## User Context
            
            **Current User:** {userName}
            
            **Defaults:**
            - Currency: {defaultCurrency}
            - Account: {defaultAccount}
            - Fund: {defaultFund}
            
            **Accounts:**
            {accounts}
            
            **Funds:**
            {funds}
            
            **Linked Users:**
            {linkedUsers}
            
            **Existing Custom Instructions:**
            {customInstructions}
            
            ## Available Actions
            
            **Alias Management:**
            - ADD_LINKED_USER_ALIAS: \\{"actionType": "ADD_LINKED_USER_ALIAS", "userName": "ALICE", "alias": "sweetie"\\}
            - REMOVE_LINKED_USER_ALIAS: \\{"actionType": "REMOVE_LINKED_USER_ALIAS", "userName": "ALICE", "alias": "sweetie"\\}
            - ADD_ACCOUNT_ALIAS: \\{"actionType": "ADD_ACCOUNT_ALIAS", "accountId": "CARD_VISA", "alias": "main"\\}
            - REMOVE_ACCOUNT_ALIAS: \\{"actionType": "REMOVE_ACCOUNT_ALIAS", "accountId": "CARD_VISA", "alias": "main"\\}
            - ADD_FUND_ALIAS: \\{"actionType": "ADD_FUND_ALIAS", "fundId": "FOOD", "alias": "cafe"\\}
            - REMOVE_FUND_ALIAS: \\{"actionType": "REMOVE_FUND_ALIAS", "fundId": "FOOD", "alias": "cafe"\\}
            
            **Custom Instructions:**
            - ADD_CUSTOM_INSTRUCTION: \\{"actionType": "ADD_CUSTOM_INSTRUCTION", "instruction": "text"\\}
            - REMOVE_CUSTOM_INSTRUCTION: \\{"actionType": "REMOVE_CUSTOM_INSTRUCTION", "index": 0\\}
            
            **Defaults:**
            - UPDATE_DEFAULT: \\{"actionType": "UPDATE_DEFAULT", "defaultType": "CURRENCY|ACCOUNT|FUND", "value": "EUR"\\}
            
            **Clarification:**
            - ASK_CLARIFICATION: \\{"actionType": "ASK_CLARIFICATION", "question": "Which account?", "context": "note"\\}
            
            ## Conflict Management
            
            When adding alias that conflicts with existing:
            1. Remove old alias first (REMOVE action)
            2. Add new alias (ADD action)
            3. Return both actions in same response
            
            When updating custom instruction:
            - Use REMOVE (old index) + ADD (new text)
            
            ## When to Ask Clarification
            
            Use ASK_CLARIFICATION when:
            - Instruction is ambiguous
            - Unsure which entity user refers to
            - Not enough information
            
            ## Response Format
            
            {format}
            
            **IMPORTANT:**
            - Return PURE JSON without comments or markdown
            - `actions` can be empty array if nothing to do
            - Use exact actionType strings from Available Actions
            - When referring to accounts/funds/users, use their exact IDs from context
            - **NEVER include "id" field in actions** - ID is system-generated
            - Explanation should be concise (1-2 sentences)
            """;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DEPENDENCIES
    // ═══════════════════════════════════════════════════════════════════════════
    
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final UserContextToPromptMapper contextMapper;
    
    // Cached converter for better performance
    private final BeanOutputConverter<Response> outputConverter;
    
    public CustomInstructionAgent(ChatModel chatModel, UserContextToPromptMapper contextMapper) {
        this.chatModel = chatModel;
        this.contextMapper = contextMapper;
        this.objectMapper = new ObjectMapper();
        // Initialize converter once (expensive reflection operation)
        this.outputConverter = new BeanOutputConverter<>(Response.class);
    }

    public Response process(Request request) {
        try {
            log.debug("🎛️ CustomInstructionAgent processing {} instructions", 
                    request.instructions().size());
            
            // Build prompts
            String systemPrompt = buildSystemPrompt(request);
            String userPrompt = buildUserPrompt(request);
            
            log.debug("System prompt: {} chars, User prompt: {} chars", 
                    systemPrompt.length(), userPrompt.length());

            // Create Spring AI Prompt with SEPARATE system and user messages
            Prompt prompt = new Prompt(
                    List.of(
                            new SystemMessage(systemPrompt),
                            new UserMessage(userPrompt)
                    ),
                    OpenAiChatOptions.builder()
                            .model(MODEL)
                            .maxCompletionTokens(MAX_COMPLETION_TOKENS)
                            .temperature(0.7)
                            .build()
            );

            // Call LLM
            ChatResponse chatResponse = chatModel.call(prompt);
            String content = chatResponse.getResult().getOutput().getText();
            
            log.debug("AI response length: {} chars", content.length());

            // Parse using BeanOutputConverter
            Response result = outputConverter.convert(content);
            
            log.info("✅ CustomInstructionAgent parsed: {} actions", 
                    result.actions() != null ? result.actions().size() : 0);
            
            return result;
            
        } catch (Exception e) {
            log.error("❌ CustomInstructionAgent error: {}", e.getMessage(), e);
            return new Response(
                    List.of(),
                    "Error processing instruction: " + e.getMessage(),
                    e.getMessage()
            );
        }
    }

    /**
     * Build system prompt using PromptTemplate (role, rules, context).
     */
    private String buildSystemPrompt(Request request) {
        Map<String, Object> params = new HashMap<>();
        
        UserEntity userContext = request.userEntity();
        
        // Format context sections (NO conversation history!)
        params.put("userName", userContext.getUserName());
        params.put("accounts", contextMapper.formatAccountsList(userContext.getAccounts()));
        params.put("funds", contextMapper.formatFundsList(userContext.getFunds()));
        params.put("linkedUsers", formatLinkedUsersList(userContext.getLinkedUsers()));
        params.put("customInstructions", formatCustomInstructionsList(userContext.getCustomInstructions()));
        
        // Defaults
        params.put("defaultCurrency", userContext.getDefaultCurrency() != null 
                ? userContext.getDefaultCurrency() : "not set");
        params.put("defaultAccount", userContext.getDefaultAccount() != null 
                ? userContext.getDefaultAccount().getAccountId() : "not set");
        params.put("defaultFund", userContext.getDefaultFund() != null 
                ? userContext.getDefaultFund().getFundId() : "not set");
        
        // JSON schema for response
        params.put("format", outputConverter.getFormat());
        
        PromptTemplate template = new PromptTemplate(PROMPT_TEMPLATE);
        return template.render(params);
    }
    
    /**
     * Build user prompt (actual user input - the new instructions).
     */
    private String buildUserPrompt(Request request) {
        List<String> newInstructions = request.instructions();
        
        if (newInstructions.size() == 1) {
            return "Process this instruction:\n\n" + newInstructions.get(0);
        }
        
        StringBuilder sb = new StringBuilder("Process these instructions:\n\n");
        for (int i = 0; i < newInstructions.size(); i++) {
            sb.append((i + 1)).append(". ").append(newInstructions.get(i)).append("\n");
        }
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private String formatLinkedUsersList(List<LinkedUserEntry> linkedUsers) {
        if (linkedUsers == null || linkedUsers.isEmpty()) {
            return "No linked users.";
        }
        
        StringBuilder sb = new StringBuilder();
        for (LinkedUserEntry user : linkedUsers) {
            sb.append("- ").append(user.getUserName());
            if (user.getAliases() != null && !user.getAliases().isEmpty()) {
                sb.append(" (aliases: ").append(String.join(", ", user.getAliases())).append(")");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
    
    private String formatCustomInstructionsList(List<String> instructions) {
        if (instructions == null || instructions.isEmpty()) {
            return "No custom instructions yet.";
        }
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < instructions.size(); i++) {
            sb.append("[").append(i).append("] ").append(instructions.get(i)).append("\n");
        }
        return sb.toString();
    }
}
