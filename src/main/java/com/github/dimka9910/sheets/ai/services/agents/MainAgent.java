package com.github.dimka9910.sheets.ai.services.agents;

import com.github.dimka9910.sheets.ai.dto.response.MainAgentResponse;
import com.github.dimka9910.sheets.ai.dto.user.UserEntity;
import com.github.dimka9910.sheets.ai.services.UserContextToPromptMapper;
import com.github.dimka9910.sheets.ai.services.agents.MessageClassifierAgent.Category;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import org.apache.commons.collections4.CollectionUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Main Orchestrator Agent (Master Router).
 * 
 * Responsibility: Decompose complex user intent, enrich with context (UUIDs, history), 
 * and route to specialized sub-agents.
 * 
 * Architecture:
 * - Uses gpt-5-mini (reasoning model) for complex decomposition
 * - Creates "Tickets" (enriched messages) for specialized agents
 * - Never executes FINANCIAL or UTILS actions directly - only routes
 */
@Slf4j
@Component
public class MainAgent {

    private static final String MODEL = "gpt-5.2";
    private static final int MAX_COMPLETION_TOKENS = 4000;
    
    private final ChatModel chatModel;
    private final UserContextToPromptMapper contextMapper;
    private final BeanOutputConverter<MainAgentResponse> outputConverter;

    public MainAgent(ChatModel chatModel, UserContextToPromptMapper contextMapper) {
        this.chatModel = chatModel;
        this.contextMapper = contextMapper;
        this.outputConverter = new BeanOutputConverter<>(MainAgentResponse.class);
    }

    public record Request(String message, UserEntity userContext, Category category) {}
    
    public record Response(MainAgentResponse result, String errorMessage) {
        public boolean isSuccess() { return errorMessage == null && result != null; }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PROMPT TEMPLATE
    // ═══════════════════════════════════════════════════════════════════════════

  private static final String PROMPT_TEMPLATE = """
            # MASTER ORCHESTRATION PROTOCOL
            
            You are the "Heavy Request Router". Simple requests bypass you. Complex ones come here.
            Your job: Transform human chaos into precise "Tickets" for specialized agents.
            
            ## Message Category: {categoryInfo}
            
            ## RECENT CONVERSATION HISTORY (for UUID tracing and context):
            {conversationHistory}
            
            ## OPERATIONAL PIPELINE:
            1. **DECOMPOSITION**: Split "A and B" into multiple REDIRECT actions
            2. **TICKET ENRICHMENT**: Pack `message` field with ALL context for specialized agent
            3. **CONVERSATION**: Provide user response yourself (in their language)
            4. **ID TRACING**: If user corrects/deletes/says "it"/"last", find UUID in conversation history (sorted newest-first, use FIRST match)
            
            
            ## CRITICAL RULES:
            - **OPERATION SELECTION**: Conversation history is newest-first. "Last"/"it" = FIRST operation in list. NEVER skip to older operations.
            - NEVER execute FINANCIAL actions. Only REDIRECT to specialized agents.
            - NEVER execute SETTINGS. REDIRECT to CUSTOM_INSTRUCTION.
            - Use PENDING_CLARIFICATION only if inference + history + defaults = zero clues.
            - Multi-step ("A and B") = multiple REDIRECT actions.
            - If user both RESOLVES a pending clarification AND asks to change defaults/settings in the same message:
              create multiple redirects (e.g., FINANCIAL + CUSTOM_INSTRUCTION) and describe BOTH outcomes in your `message`.
            - ALWAYS respond in the SAME language as the user's input message unless other instructions provided.
            
            ## CRITICAL: FINDING OPERATIONS FOR CORRECTIONS
            
            When user says "no", "it was", "change it", "delete it", "modify", "last one":
            1. Look at **Recent Conversation History** below
            2. Find the FIRST operation listed (= newest/last operation)
            3. Copy its UUID into your redirect message
            4. Example: If history shows "ID: abc-123, Type: EXPENSE, Amount: 300", use "Modify operation ID=abc-123..."
            
            ## TICKET ENRICHMENT (the `message` field)
            
            Bad Ticket: "not 200 but 300"
            Good Ticket: "Modify operation ID=abc-123. Original: EXPENSE 200 RSD from CARD_VISA to FOOD, comment='coffee'. User correction: amount to 300. Keep other fields."
            
            Bad Ticket: "no, it was 500"
            Good Ticket: "Modify operation ID=xyz-789 (LAST operation from history). Original: EXPENSE 300 RSD from CARD_VISA to TRANSPORT, comment='taxi'. User correction: amount to 500. Keep all other fields."
            
            Bad Ticket: "same but taxi"
            Good Ticket: "New expense like previous (ID=abc-123, 200 RSD coffee from CARD_VISA to FOOD). Changes: comment='taxi', fund=TRANSPORT. Keep: amount=200, currency=RSD, account=CARD_VISA."
            
            Bad Ticket: "coffee"
            Good Ticket: "Expense: coffee. Inferred: fund=FOOD. Missing: amount. Defaults: currency=RSD, account=CARD_VISA."
            
            Bad Ticket: "delete it"
            Good Ticket: "Delete operation ID=abc-123 (last operation from history): EXPENSE 200 RSD coffee, CARD_VISA to FOOD, 1 min ago."
            
            ## AVAILABLE AGENTS:
            - `FINANCIAL`: Financial operations WITHOUT linked users (expenses, transfers between own accounts, income)
            - `THIRD_PARTY_FINANCIAL`: Financial operations WITH linked users (transfers to/from linked users, expenses for linked users)
            - `CUSTOM_INSTRUCTION`: Settings changes (defaults, aliases, custom rules)
            - `CORRECTION`: Modify/delete existing operations (requires UUID from conversation history)
            
            # RESPONSE FORMAT
            
            Your response must be a JSON object with:
            - `redirects`: Array of REDIRECT_TO_AGENT actions (empty for conversational responses)
            - `pendingClarifications`: Array of PENDING_CLARIFICATION actions (empty if no clarification needed)
            - `message`: Your response text to the user (in their language)
            
            ## REDIRECT_TO_AGENT
            Delegate to specialized agents with enriched Tickets.
            
            Example redirect action:
            - agentType: FINANCIAL, THIRD_PARTY_FINANCIAL, or CUSTOM_INSTRUCTION
            - message: Enriched Ticket with full context (UUID for modifications, inferred values, defaults)
            - reason: Optional debug note
            
            **The Ticket (`message` field):**
            NOT the raw user message. Must include:
            - UUID from history (for corrections)
            - Inferred values (funds, accounts)
            - Defaults if needed
            - Custom instructions if relevant
            
            ## PENDING_CLARIFICATION
            Use only if: no history, no defaults, no inference possible.
            
            ```json
            {
              "context": "What's missing and why you're stuck"
            }
            ```
            
            # REASONING RULES
            
            ## Analyze Conversation History
            Look for:
            - Last operations (find UUIDs for "it"/"last")
            - Previous requests and responses
            - Custom instructions
            - Corrections
            
            ## Identify Request Type
            - **Correction**: negation / "actually" / "change" / "delete" / references to "last/it" → REDIRECT to CORRECTION with UUID
            - **Multi-Step**: "and", "also" → Multiple REDIRECT actions
            - **Info Query**: "show settings" → NO actions, just response
            - **Partial**: Some info → REDIRECT with what you know
            
            ## Defaults & Funds (IMPORTANT)
            - Do NOT guess a fund/category from the purchase item text.
            - If fund is not explicitly referenced and defaultFund exists → mention defaultFund in the Ticket.
            - If fund is not explicitly referenced and defaultFund is missing → resolve via clarification (or use user's answer to the pending question).
            - If user explicitly references a fund/category/budget → match it to an available fund ID.
            
            {formatInstructions}
            
            # USER CONTEXT
            
            **User:** {userName}
            
            **Defaults:**
            - Currency: {defaultCurrency}
            - Account: {defaultAccount}
            - Fund: {defaultFund}
            
            **Available Accounts:**
            {accounts}
            
            **Available Funds:**
            {funds}
            
            **Linked Users:**
            {linkedUsers}
            
            **Custom Instructions:**
            {customInstructions}
            """;

  private static final String PENDING_CLARIFICATIONS_SECTION = """
            
            ## RESOLVING PENDING CLARIFICATIONS
            
            User has the following PENDING clarifications:
            
            {pendingList}
            
            **Your options:**
            - If resolved → create completed actions
            - If still unclear → NEW PENDING_CLARIFICATION
            - If topic changed → acknowledge, process new request
            
            **IMPORTANT:**
            - If the user provides the missing fields (e.g., fund/account) → you MUST create the operation now (via redirect to FINANCIAL/THIRD_PARTY_FINANCIAL),
              not only update settings.
            - If the user also says "use this as default" → add an additional redirect to CUSTOM_INSTRUCTION in the same response.
            """;

    // ═══════════════════════════════════════════════════════════════════════════
    // PROCESS
    // ═══════════════════════════════════════════════════════════════════════════

    public Response process(Request request) {
        log.info("🧠 MainAgent Orchestrating: \"{}\"", truncate(request.message(), 60));
        
        try {
            String systemPrompt = buildSystemPrompt(request.userContext(), request.category());
            String userPrompt = "### User Message ###\n" + request.message();
            
            log.debug("System prompt length: {} chars, User prompt length: {} chars", 
                    systemPrompt.length(), userPrompt.length());
            log.debug("🔍 System prompt preview (last 2000 chars): ...{}", 
                    systemPrompt.substring(Math.max(0, systemPrompt.length() - 2000)));
            
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
            
            ChatResponse chatResponse = chatModel.call(prompt);
            String content = chatResponse.getResult().getOutput().getText();
            log.debug("AI response: {}", truncate(content, 400));

            if (content == null || content.isBlank()) {
                throw new IllegalStateException("Empty response from MainAgent model");
            }

            MainAgentResponse result = outputConverter.convert(content);
            
            log.info("✅ Parsed: redirects={}, pending={}, response='{}'", 
                    !CollectionUtils.isEmpty(result.getRedirects()) ? result.getRedirects().size() : 0,
                    !CollectionUtils.isEmpty(result.getPendingClarifications()) ? result.getPendingClarifications().size() : 0,
                    truncate(result.getMessage(), 50));
            
            return new Response(result, null);
            
        } catch (Exception e) {
            log.error("❌ MainAgent fatal error: {}", e.getMessage(), e);
            return new Response(null, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BUILD PROMPT
    // ═══════════════════════════════════════════════════════════════════════════

    private String buildSystemPrompt(UserEntity context, Category category) {
        // Conditional: add pending clarifications section if needed
        String promptTemplate = PROMPT_TEMPLATE;
        if (context.getPendingActions() != null && !context.getPendingActions().isEmpty()) {
            promptTemplate = PROMPT_TEMPLATE + PENDING_CLARIFICATIONS_SECTION;
        }
        
        Map<String, Object> params = new HashMap<>();
        
        // Category info
        params.put("categoryInfo", category.name());
        
        // User info
        params.put("userName", context.getUserName() != null ? context.getUserName() : "User");
        
        // Defaults
        params.put("defaultCurrency", context.getDefaultCurrency() != null ? context.getDefaultCurrency() : "not set");
        params.put("defaultAccount", context.getDefaultAccount() != null ? 
                context.getDefaultAccount().getAccountId() : "not set");
        params.put("defaultFund", context.getDefaultFund() != null ? 
                context.getDefaultFund().getFundId() : "not set");
        
        // Lists
        params.put("accounts", contextMapper.formatAccountsList(context.getAccounts()));
        params.put("funds", contextMapper.formatFundsList(context.getFunds()));
        // Include linked users WITH accounts for creating enriched tickets
        params.put("linkedUsers", contextMapper.formatLinkedUsersListWithAccounts(
                context.getLinkedUserEntitys(), context.getLinkedUsers()));
        
        // Custom instructions
        String customInstructions = contextMapper.formatCustomInstructionsSection(context.getCustomInstructions());
        params.put("customInstructions", customInstructions != null && !customInstructions.isBlank() 
                ? customInstructions : "(No custom instructions)");
        
        // Conversation history
        String history = contextMapper.formatConversationHistoryWithActions(context.getConversationHistory(), 10);
        params.put("conversationHistory", history != null && !history.isBlank() 
                ? history : "(No recent conversation)");
        
        // Pending clarifications list (if section was added)
        if (context.getPendingActions() != null && !context.getPendingActions().isEmpty()) {
            params.put("pendingList", contextMapper.formatPendingActionsList(context.getPendingActions()));
        }
        
        // Build prompt without PromptTemplate (to avoid StringTemplate syntax issues with quotes)
        String finalPrompt = promptTemplate
                .replace("{formatInstructions}", outputConverter.getFormat())
                .replace("{categoryInfo}", (String) params.get("categoryInfo"))
                .replace("{userName}", (String) params.get("userName"))
                .replace("{defaultCurrency}", (String) params.get("defaultCurrency"))
                .replace("{defaultAccount}", (String) params.get("defaultAccount"))
                .replace("{defaultFund}", (String) params.get("defaultFund"))
                .replace("{accounts}", (String) params.get("accounts"))
                .replace("{funds}", (String) params.get("funds"))
                .replace("{linkedUsers}", (String) params.get("linkedUsers"))
                .replace("{customInstructions}", (String) params.get("customInstructions"))
                .replace("{conversationHistory}", (String) params.get("conversationHistory"))
                .replace("{pendingList}", params.get("pendingList") != null ? (String) params.get("pendingList") : "");
        
        return finalPrompt;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
