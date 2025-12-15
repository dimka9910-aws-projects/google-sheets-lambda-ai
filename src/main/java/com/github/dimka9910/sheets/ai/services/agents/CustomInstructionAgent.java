package com.github.dimka9910.sheets.ai.services.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimka9910.sheets.ai.dto.user.AccountEntry;
import com.github.dimka9910.sheets.ai.dto.user.FundEntry;
import com.github.dimka9910.sheets.ai.dto.user.LinkedUserEntry;
import com.github.dimka9910.sheets.ai.dto.user.UserEntity;
import com.github.dimka9910.sheets.ai.dto.actions.InstructionAction;
import com.github.dimka9910.sheets.ai.services.llm.LLMClient;
import com.github.dimka9910.sheets.ai.services.llm.OpenAIClient;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * CustomInstructionAgent - intelligent context manager.
 * 
 * Responsibilities:
 * - Classify new instructions to correct context (aliases, defaults, custom instructions)
 * - Manage conflicts and optimize user context
 * - Add/remove/update instructions intelligently
 * - Ask for clarification when uncertain
 * 
 * Model: gpt-4o-mini
 */
@Slf4j
public class CustomInstructionAgent {

    private static final String MODEL = "gpt-4o";
    private static final int MAX_COMPLETION_TOKENS = 1500;

    public record Request(
            List<String> instructions,
            UserEntity userEntity
    ) {}

    public record Response(
            List<InstructionAction> actions,
            String explanation,
            long latencyMs,
            int tokensUsed,
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
               Example: "remember I call KIKI as ЗАЯ" → add alias "ЗАЯ" to linked user KIKI
            
            2. **Account Aliases** - nicknames for accounts
               Example: "райф is my Raiffeisen card" → add alias "райф" to account CARD_RAIF
            
            3. **Fund Aliases** - nicknames for categories
               Example: "кафе is FOOD" → add alias "кафе" to fund FOOD
            
            4. **Default Updates** - permanent preferences
               Example: "I always spend in EUR" → update defaultCurrency to EUR
            
            5. **Custom Instructions** - complex rules that don't fit above
               Example: "трасса = 100 dinars from cash in family budget"
            """;
    
    private static final String PROMPT_ACTIONS = """
            
            ## Available Actions
            
            **Alias Management:**
            - **ADD_LINKED_USER_ALIAS**: {"actionType": "ADD_LINKED_USER_ALIAS", "userName": "KIKI", "alias": "ЗАЯ"}
            - **REMOVE_LINKED_USER_ALIAS**: {"actionType": "REMOVE_LINKED_USER_ALIAS", "userName": "KIKI", "alias": "ЗАЯ"}
            - **ADD_ACCOUNT_ALIAS**: {"actionType": "ADD_ACCOUNT_ALIAS", "accountId": "CARD_RAIF", "alias": "райф"}
            - **REMOVE_ACCOUNT_ALIAS**: {"actionType": "REMOVE_ACCOUNT_ALIAS", "accountId": "CARD_RAIF", "alias": "райф"}
            - **ADD_FUND_ALIAS**: {"actionType": "ADD_FUND_ALIAS", "fundId": "FOOD", "alias": "кафе"}
            - **REMOVE_FUND_ALIAS**: {"actionType": "REMOVE_FUND_ALIAS", "fundId": "FOOD", "alias": "кафе"}
            
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
                {"actionType": "REMOVE_ACCOUNT_ALIAS", "accountId": "CARD_RAIF", "alias": "старый"},
                {"actionType": "ADD_ACCOUNT_ALIAS", "accountId": "CARD_RAIF", "alias": "райф"},
                {"actionType": "REMOVE_CUSTOM_INSTRUCTION", "index": 2}
              ],
              "explanation": "Replaced old alias with 'райф' and removed outdated instruction [2]"
            }
            ```
            
            **Important:**
            - Always return valid JSON
            - `actions` can be empty array if nothing to do
            - Use exact actionType strings from Available Actions list
            - When referring to accounts/funds/users, use their exact IDs from context
            - Explanation should be concise (1-2 sentences)
            """;
    
    private final LLMClient client;
    private final ObjectMapper objectMapper;

    public CustomInstructionAgent() {
        this.client = OpenAIClient.getInstance();
        this.objectMapper = new ObjectMapper();
    }

    public CustomInstructionAgent(LLMClient client) {
        this.client = client;
        this.objectMapper = new ObjectMapper();
    }

    public Response process(Request request) {
        long start = System.currentTimeMillis();
        try {
            String prompt = buildPrompt(request);
            log.debug("CustomInstructionAgent prompt length: {} chars", prompt.length());

            LLMClient.Response llmResponse = client.complete(MODEL, prompt, MAX_COMPLETION_TOKENS);
            log.info("CustomInstructionAgent raw response: {}", llmResponse.content());

            return parseResponse(llmResponse, start);
        } catch (Exception e) {
            log.error("CustomInstructionAgent error: {}", e.getMessage(), e);
            return new Response(
                    List.of(),
                    "Error processing instruction: " + e.getMessage(),
                    System.currentTimeMillis() - start,
                    0,
                    e.getMessage()
            );
        }
    }

    private String buildPrompt(Request request) {
        StringBuilder sb = new StringBuilder();
        
        sb.append(PROMPT_INTRO);
        sb.append(PROMPT_CAPABILITIES);
        sb.append(PROMPT_ACTIONS);
        sb.append(PROMPT_CONFLICT_MANAGEMENT);
        sb.append(PROMPT_CLARIFICATION);
        
        sb.append("\n## Current User Context\n\n");
        
        UserEntity user = request.userEntity();
        
        // Defaults
        sb.append("### Defaults:\n");
        sb.append("- Currency: ").append(user.getDefaultCurrency() != null ? user.getDefaultCurrency() : "NOT SET").append("\n");
        sb.append("- Account: ").append(user.getDefaultAccount() != null ? user.getDefaultAccount() : "NOT SET").append("\n");
        sb.append("- Fund: ").append(user.getDefaultFund() != null ? user.getDefaultFund() : "NOT SET").append("\n\n");
        
        // Accounts
        List<AccountEntry> accounts = user.getAccounts();
        if (accounts != null && !accounts.isEmpty()) {
            sb.append("### Accounts:\n");
            for (AccountEntry acc : accounts) {
                sb.append("- ").append(acc.getAccountId());
                if (acc.getDisplayName() != null) {
                    sb.append(" (").append(acc.getDisplayName()).append(")");
                }
                if (acc.getAliases() != null && !acc.getAliases().isEmpty()) {
                    sb.append(" — aliases: ").append(String.join(", ", acc.getAliases()));
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        
        // Funds
        List<FundEntry> funds = user.getFunds();
        if (funds != null && !funds.isEmpty()) {
            sb.append("### Funds:\n");
            for (FundEntry fund : funds) {
                sb.append("- ").append(fund.getFundId());
                if (fund.getDisplayName() != null) {
                    sb.append(" (").append(fund.getDisplayName()).append(")");
                }
                if (fund.getAliases() != null && !fund.getAliases().isEmpty()) {
                    sb.append(" — aliases: ").append(String.join(", ", fund.getAliases()));
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        
        // Linked Users
        List<LinkedUserEntry> linkedUsers = user.getLinkedUsers();
        if (linkedUsers != null && !linkedUsers.isEmpty()) {
            sb.append("### Linked Users:\n");
            for (LinkedUserEntry linked : linkedUsers) {
                sb.append("- ").append(linked.getUserName());
                if (linked.getDisplayName() != null) {
                    sb.append(" (").append(linked.getDisplayName()).append(")");
                }
                if (linked.getAliases() != null && !linked.getAliases().isEmpty()) {
                    sb.append(" — aliases: ").append(String.join(", ", linked.getAliases()));
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        
        // Custom Instructions
        List<String> instructions = user.getCustomInstructions();
        if (instructions != null && !instructions.isEmpty()) {
            sb.append("### Custom Instructions:\n");
            for (int i = 0; i < instructions.size(); i++) {
                sb.append("[").append(i).append("] ").append(instructions.get(i)).append("\n");
            }
            sb.append("\n");
        } else {
            sb.append("### Custom Instructions:\n(none)\n\n");
        }
        
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
        
        sb.append(PROMPT_RESPONSE_FORMAT);
        
        return sb.toString();
    }

    private Response parseResponse(LLMClient.Response llmResponse, long startTime) {
        try {
            String json = cleanJsonResponse(llmResponse.content());
            JsonNode root = objectMapper.readTree(json);

            List<InstructionAction> actions = new ArrayList<>();
            if (root.has("actions") && root.path("actions").isArray()) {
                for (JsonNode actionNode : root.path("actions")) {
                    InstructionAction action = objectMapper.treeToValue(actionNode, InstructionAction.class);
                    actions.add(action);
                }
            }

            String explanation = root.path("explanation").asText("No explanation provided.");

            long latency = System.currentTimeMillis() - startTime;
            log.info("CustomInstructionAgent parsed: {} actions ({}ms, {} tokens)",
                    actions.size(), latency, llmResponse.totalTokens());

            return new Response(actions, explanation, latency, llmResponse.totalTokens(), null);

        } catch (Exception e) {
            log.error("CustomInstructionAgent parse error: {}", e.getMessage(), e);
            return new Response(
                    List.of(),
                    "Parse error: " + e.getMessage(),
                    System.currentTimeMillis() - startTime,
                    llmResponse.totalTokens(),
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
