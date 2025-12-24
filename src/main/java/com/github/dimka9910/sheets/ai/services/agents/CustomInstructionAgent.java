package com.github.dimka9910.sheets.ai.services.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimka9910.sheets.ai.dto.user.UserEntity;
import com.github.dimka9910.sheets.ai.dto.actions.CustomInstructionActionBase;
import com.github.dimka9910.sheets.ai.services.UserContextToPromptMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
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
@RequiredArgsConstructor
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

    private static final String PROMPT_INTRO = """
            You are an intelligent context manager for a personal finance bot.
            Your task is to analyze user's new instruction(s) and determine how to update their context.
            
            You may receive one or multiple instructions at once - process them all together to find optimal changes.
            """;
    
    private static final String PROMPT_CAPABILITIES = """
            
            ## Your Capabilities
            
            You can classify instructions into categories and manage user's context:
            
            1. **Linked User Aliases** - nicknames for people
               Example: "remember I call ALICE as sweetie" → add alias "sweetie" to linked user ALICE
            
            2. **Account Aliases** - nicknames for accounts
               Example: "main is my primary card" → add alias "main" to account CARD_USER_VISA
            
            3. **Fund Aliases** - nicknames for categories
               Example: "cafe is FOOD" → add alias "cafe" to fund FOOD
            
            4. **Default Updates** - permanent preferences
               Example: "I always spend in EUR" → update defaultCurrency to EUR
            
            5. **Custom Instructions** - complex rules that don't fit above
               Example: "highway = 100 dollars from cash in family budget"
            """;
    
    private static final String PROMPT_ACTIONS = """
            
            ## Available Actions
            
            **Alias Management:**
            - **ADD_LINKED_USER_ALIAS**: {"actionType": "ADD_LINKED_USER_ALIAS", "userName": "ALICE", "alias": "sweetie"}
            - **REMOVE_LINKED_USER_ALIAS**: {"actionType": "REMOVE_LINKED_USER_ALIAS", "userName": "ALICE", "alias": "sweetie"}
            - **ADD_ACCOUNT_ALIAS**: {"actionType": "ADD_ACCOUNT_ALIAS", "accountId": "CARD_USER_VISA", "alias": "main"}
            - **REMOVE_ACCOUNT_ALIAS**: {"actionType": "REMOVE_ACCOUNT_ALIAS", "accountId": "CARD_USER_VISA", "alias": "main"}
            - **ADD_FUND_ALIAS**: {"actionType": "ADD_FUND_ALIAS", "fundId": "FOOD", "alias": "cafe"}
            - **REMOVE_FUND_ALIAS**: {"actionType": "REMOVE_FUND_ALIAS", "fundId": "FOOD", "alias": "cafe"}
            
            **Custom Instructions:**
            - **ADD_CUSTOM_INSTRUCTION**: {"actionType": "ADD_CUSTOM_INSTRUCTION", "instruction": "text"}
            - **REMOVE_CUSTOM_INSTRUCTION**: {"actionType": "REMOVE_CUSTOM_INSTRUCTION", "index": 0}
            
            **Defaults:**
            - **UPDATE_DEFAULT**: {"actionType": "UPDATE_DEFAULT", "defaultType": "CURRENCY|ACCOUNT|FUND", "value": "EUR"}
            
            **Clarification:**
            - **ASK_CLARIFICATION**: {"actionType": "ASK_CLARIFICATION", "question": "Which account?", "context": "internal note"}
            
            **Note:** To update a custom instruction, use REMOVE (old index) + ADD (new text) in same response.
            """;
    
    private static final String PROMPT_CONFLICT_MANAGEMENT = """
            
            ## Conflict Management
            
            New instructions have HIGHER priority than old ones:
            - If new instruction contradicts existing one → REMOVE old, ADD new
            - If new instruction augments existing one → UPDATE or ADD
            - If new instruction makes old one redundant → REMOVE old
            - Optimize context: merge similar instructions, remove duplicates
            """;
    
    private static final String PROMPT_CLARIFICATION = """
            
            ## When to Ask Clarification
            
            Use ASK_CLARIFICATION when:
            - Instruction is ambiguous (multiple possible interpretations)
            - You're unsure which entity user refers to
            - Instruction contradicts multiple existing rules and resolution is unclear
            - Not enough information to confidently make changes
            """;
    
    private static final String PROMPT_RESPONSE_FORMAT = """
            
            ## Response Format (JSON only)
            
            Return a JSON object with:
            - `actions`: array of action objects (can be empty if instruction is just informational)
            - `explanation`: brief explanation of what you're doing and why
            
            ```json
            {
              "actions": [
                {"actionType": "REMOVE_ACCOUNT_ALIAS", "accountId": "CARD_USER_VISA", "alias": "old"},
                {"actionType": "ADD_ACCOUNT_ALIAS", "accountId": "CARD_USER_VISA", "alias": "main"},
                {"actionType": "REMOVE_CUSTOM_INSTRUCTION", "index": 2}
              ],
              "explanation": "Replaced old alias with 'main' and removed outdated instruction [2]"
            }
            ```
            
            **Important:**
            - Always return valid JSON
            - `actions` can be empty array if nothing to do
            - Use exact actionType strings from Available Actions list
            - When referring to accounts/funds/users, use their exact IDs from context
            - Explanation should be concise (1-2 sentences)
            """;
    
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final UserContextToPromptMapper contextMapper;

    public Response process(Request request) {
        try {
            // Build system and user prompts
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(request);
            
            log.debug("CustomInstructionAgent system prompt: {} chars, user prompt: {} chars", 
                    systemPrompt.length(), userPrompt.length());

            // Create Spring AI Prompt
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

            // Call LLM via Spring AI (observability handled automatically)
            ChatResponse chatResponse = chatModel.call(prompt);
            String content = chatResponse.getResult().getOutput().getText();  
            
            log.debug("CustomInstructionAgent raw response: {}", content);

            return parseResponse(chatResponse);
        } catch (Exception e) {
            log.error("CustomInstructionAgent error: {}", e.getMessage(), e);
            return new Response(
                    List.of(),
                    "Error processing instruction: " + e.getMessage(),
                    e.getMessage()
            );
        }
    }

    /**
     * Build system prompt (instructions + user context, NO user message).
     */
    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        
        sb.append(PROMPT_INTRO);
        sb.append(PROMPT_CAPABILITIES);
        sb.append(PROMPT_ACTIONS);
        sb.append(PROMPT_CONFLICT_MANAGEMENT);
        sb.append(PROMPT_CLARIFICATION);
        sb.append(PROMPT_RESPONSE_FORMAT);
        
        return sb.toString();
    }

    /**
     * Build user prompt (user context + new instructions).
     * Reuses UserContextToPromptMapper for consistent formatting (DRY principle).
     */
    private String buildUserPrompt(Request request) {
        StringBuilder sb = new StringBuilder();

        // CustomInstructionAgent is for SIMPLE_CUSTOM_INSTRUCTION category
        String userContext = contextMapper.buildContextPrompt(
                request.userEntity(), 
                MessageClassifierAgent.Category.SIMPLE_CUSTOM_INSTRUCTION
        );
        sb.append(userContext);
        
        // Add new instructions (specific to CustomInstructionAgent)
        sb.append("\n## User's New Instructions\n\n");
        List<String> newInstructions = request.instructions();
        if (newInstructions.size() == 1) {
            sb.append("```\n");
            sb.append(newInstructions.get(0));
            sb.append("\n```\n");
        } else {
            for (int i = 0; i < newInstructions.size(); i++) {
                sb.append((i + 1)).append(". ```\n");
                sb.append(newInstructions.get(i));
                sb.append("\n```\n\n");
            }
        }
        
        return sb.toString();
    }

    private Response parseResponse(ChatResponse chatResponse) {
        try {
            String content = chatResponse.getResult().getOutput().getText();
            String json = cleanJsonResponse(content);
            JsonNode root = objectMapper.readTree(json);

            List<CustomInstructionActionBase> actions = new ArrayList<>();
            if (root.has("actions") && root.path("actions").isArray()) {
                for (JsonNode actionNode : root.path("actions")) {
                    CustomInstructionActionBase action = objectMapper.treeToValue(actionNode, CustomInstructionActionBase.class);
                    actions.add(action);
                }
            }

            String explanation = root.path("explanation").asText("No explanation provided.");
            
            log.info("✅ CustomInstructionAgent parsed: {} actions",
                    actions.size());

            return new Response(actions, explanation, null);

        } catch (Exception e) {
            log.error("❌ CustomInstructionAgent parse error: {}", e.getMessage(), e);
            return new Response(
                    List.of(),
                    "Parse error: " + e.getMessage(),
                    "Parse error: " + e.getMessage()
            );
        }
    }

    private String cleanJsonResponse(String response) {
        String cleaned = response.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        return cleaned.trim();
    }
}
