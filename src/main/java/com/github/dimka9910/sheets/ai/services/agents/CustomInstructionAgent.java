package com.github.dimka9910.sheets.ai.services.agents;

import com.github.dimka9910.sheets.ai.dto.response.CustomInstructionAgentResponse;
import com.github.dimka9910.sheets.ai.dto.user.LinkedUserEntry;
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

import java.util.ArrayList;
import java.util.List;

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

    private static final String MODEL = "gpt-5.2";
    private static final int MAX_COMPLETION_TOKENS = 1500;

    public record Request(
            List<String> instructions,
            UserEntity userEntity
    ) {}

    // ═══════════════════════════════════════════════════════════════════════════
    // PROMPT TEMPLATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private static final String PROMPT_TEMPLATE = """
            # ROLE: Settings & Instructions Manager

            You manage user's preferences and interpretation rules for a personal finance assistant.
            Your job is to output structured actions to update settings (aliases, defaults, custom instructions),
            or ask a clarification question if ambiguous.

            ## HARD RULES (MUST FOLLOW)
            - Output MUST be valid JSON only (no markdown, no comments).
            - Use ONLY IDs that exist in the provided context lists (accounts/funds/linked users).
            - If ambiguous, DO NOT guess: ask a question in `message` and add ONE item into `pendingClarifications`.
            - For aliases: store the alias value as provided (do not invent new aliases).
            - Keep `customInstructionActions[*].value` SHORT and canonical:
              - Max ~160 characters.
              - No examples, no parentheses, no long explanations.
              - Prefer a compact rule format: "WHEN <condition> THEN <action>".

            ## USER CONTEXT
            
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
            
            ## ACTIONS CONTRACT
            All actions must use THIS DTO shape:
            - actionType: one of CustomInstructionAction.ActionType enum values
            - entityType: one of: linkedUser | account | fund | customInstruction | default
            - entityId: the target ID (e.g. linked user's userName, accountId, fundId, or default key)
            - value: the value to add/set (alias string / instruction text / default value)
            - index: optional (only for REMOVE_CUSTOM_INSTRUCTION; if omitted, you may remove by value)

            Defaults supported via UPDATE_DEFAULT:
            - entityType="default"
            - entityId must be ONE of: currency | account | fund | language
            - value must be the desired value (for account/fund: use exact ID from list)

            ## RESPONSE FORMAT (JSON SCHEMA)
            {format}
            """;
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DEPENDENCIES
    // ═══════════════════════════════════════════════════════════════════════════
    
    private final ChatModel chatModel;
    private final UserContextToPromptMapper contextMapper;
    
    // Cached converter for better performance
    private final BeanOutputConverter<CustomInstructionAgentResponse> outputConverter;
    
    public CustomInstructionAgent(ChatModel chatModel, UserContextToPromptMapper contextMapper) {
        this.chatModel = chatModel;
        this.contextMapper = contextMapper;
        // Initialize converter once (expensive reflection operation)
        this.outputConverter = new BeanOutputConverter<>(CustomInstructionAgentResponse.class);
    }

    public CustomInstructionAgentResponse process(Request request) {
        try {
            log.debug("🎛️ CustomInstructionAgent processing {} instructions", 
                    request.instructions().size());
            
            // Build prompts
            String systemPrompt = buildSystemPrompt(request);
            String userPrompt = buildUserPrompt(request);
            
            log.debug("System prompt: {} chars, User prompt: {} chars", 
                    systemPrompt.length(), userPrompt.length());

            // Create Spring AI Prompt with SEPARATE system and user messages
            @SuppressWarnings("null")
            Prompt prompt = new Prompt(
                    List.of(
                            new SystemMessage(systemPrompt),
                            new UserMessage(userPrompt)
                    ),
                    OpenAiChatOptions.builder()
                            .model(MODEL)
                            .maxCompletionTokens(MAX_COMPLETION_TOKENS)
                            .temperature(1.0) // GPT-5.x reasoning models: keep default temperature
                            .reasoningEffort("low")
                            .build()
            );

            // Call LLM
            ChatResponse chatResponse = chatModel.call(prompt);
            String content = chatResponse.getResult().getOutput().getText();
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("Empty response from custom-instruction model");
            }
            
            log.debug("AI response length: {} chars", content.length());

            // Parse using BeanOutputConverter
            CustomInstructionAgentResponse result = outputConverter.convert(content);

            log.info("✅ CustomInstructionAgent parsed: {} actions, pending={}",
                    result.getCustomInstructionActions() != null ? result.getCustomInstructionActions().size() : 0,
                    result.getPendingClarifications() != null ? result.getPendingClarifications().size() : 0);
            
            return result;
            
        } catch (Exception e) {
            log.error("❌ CustomInstructionAgent error: {}", e.getMessage(), e);
            return CustomInstructionAgentResponse.builder()
                    .customInstructionActions(List.of())
                    .pendingClarifications(new ArrayList<>())
                    .message("Something went wrong. Please try again.")
                    .build();
        }
    }

    /**
     * Build system prompt using PromptTemplate (role, rules, context).
     */
    private String buildSystemPrompt(Request request) {
        UserEntity userContext = request.userEntity();
        
        // Format context sections (NO conversation history!)
        String userName = userContext.getUserName();
        String accounts = contextMapper.formatAccountsList(userContext.getAccounts());
        String funds = contextMapper.formatFundsList(userContext.getFunds());
        String linkedUsers = formatLinkedUsersList(userContext.getLinkedUsers());
        String customInstructions = formatCustomInstructionsList(userContext.getCustomInstructions());

        // Defaults
        String defaultCurrency = userContext.getDefaultCurrency() != null ? userContext.getDefaultCurrency() : "not set";
        String defaultAccount = userContext.getDefaultAccount() != null ? userContext.getDefaultAccount().getAccountId() : "not set";
        String defaultFund = userContext.getDefaultFund() != null ? userContext.getDefaultFund().getFundId() : "not set";

        // JSON schema for response
        String format = outputConverter.getFormat();

        // NOTE: Avoid PromptTemplate/StringTemplate here to prevent syntax issues with quotes/pipes in examples.
        return PROMPT_TEMPLATE
                .replace("{userName}", userName != null ? userName : "User")
                .replace("{accounts}", accounts != null ? accounts : "(No accounts)")
                .replace("{funds}", funds != null ? funds : "(No funds)")
                .replace("{linkedUsers}", linkedUsers != null ? linkedUsers : "(No linked users)")
                .replace("{customInstructions}", customInstructions != null ? customInstructions : "(No custom instructions)")
                .replace("{defaultCurrency}", defaultCurrency)
                .replace("{defaultAccount}", defaultAccount)
                .replace("{defaultFund}", defaultFund)
                .replace("{format}", format);
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
