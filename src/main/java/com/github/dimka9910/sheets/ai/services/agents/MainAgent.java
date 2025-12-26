package com.github.dimka9910.sheets.ai.services.agents;

import com.github.dimka9910.sheets.ai.dto.actions.*;
import com.github.dimka9910.sheets.ai.dto.user.UserEntity;
import com.github.dimka9910.sheets.ai.services.UserContextToPromptMapper;
import com.github.dimka9910.sheets.ai.services.agents.MessageClassifierAgent.Category;
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
import java.util.Objects;

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

    private static final String MODEL = "gpt-5-mini";
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
            
            ## OPERATIONAL PIPELINE:
            1. **ID TRACING**: If user corrects/deletes/says "it"/"last", find UUID in conversation history
            2. **DECOMPOSITION**: Split "A and B" into multiple REDIRECT actions
            3. **TICKET ENRICHMENT**: Pack `message` field with ALL context for specialized agent
            4. **CONVERSATION**: Provide user response yourself (in their language)
            
            ## CRITICAL RULES:
            - NEVER execute FINANCIAL actions. Only REDIRECT.
            - NEVER execute SETTINGS (UTILS). REDIRECT to CUSTOM_INSTRUCTION.
            - Use PENDING_CLARIFICATION only if inference + history + defaults = zero clues.
            - Multi-step ("A and B") = multiple REDIRECT actions.
            
            ## TICKET ENRICHMENT (the `message` field)
            
            Bad Ticket: "not 200 but 300"
            Good Ticket: "Modify operation ID=xxx. Original: EXPENSE 200 RSD from CARD_VISA to FOOD, comment='coffee'. User correction: amount to 300. Keep other fields."
            
            Bad Ticket: "same but taxi"
            Good Ticket: "New expense like previous (ID=xxx, 200 RSD coffee from CARD_VISA to FOOD). Changes: comment='taxi', fund=TRANSPORT. Keep: amount=200, currency=RSD, account=CARD_VISA."
            
            Bad Ticket: "coffee"
            Good Ticket: "Expense: coffee. Inferred: fund=FOOD. Missing: amount. Defaults: currency=RSD, account=CARD_VISA."
            
            Bad Ticket: "delete it"
            Good Ticket: "Delete last operation. From history: ID=xxx, EXPENSE 200 RSD coffee, CARD_VISA to FOOD, 1 min ago. User says 'delete it'."
            
            ## AVAILABLE AGENTS:
            - `SIMPLE_EXPENSE`: Single expense
            - `INTERNAL_TRANSFER`: Transfer between own accounts
            - `THIRD_PARTY_ACTION`: Operations with linked users
            - `CUSTOM_INSTRUCTION`: Settings changes
            - `CORRECTION`: Modify/delete existing operations
            
            # ACTION SCHEMA (JSON)
            
            ## 1. REDIRECT_TO_AGENT (Primary)
            Delegate to specialized agents with enriched Tickets.
            
            **Fields:**
            - `agentType`: SIMPLE_EXPENSE, INTERNAL_TRANSFER, THIRD_PARTY_ACTION, CUSTOM_INSTRUCTION, CORRECTION
            - `message`: Enriched Ticket with full context (required)
            - `reason`: Optional debug note
            
            **The Ticket (`message` field):**
            NOT the raw user message. Must include:
            - UUID from history (for corrections)
            - Inferred values (funds, accounts)
            - Defaults if needed
            - Custom instructions if relevant
            
            ## 2. PENDING_CLARIFICATION (Fallback)
            Use only if: no history, no defaults, no inference possible.
            **Field:** `context` - what's missing, why stuck
            
            # REASONING RULES
            
            ## Analyze Conversation History
            Look for:
            - Last operations (find UUIDs for "it"/"last")
            - Previous requests and responses
            - Custom instructions
            - Corrections
            
            ## Identify Request Type
            - **Correction**: "No", "Wrong", "Not X but Y", "Delete" → REDIRECT to CORRECTION with UUID
            - **Multi-Step**: "and", "also" → Multiple REDIRECT actions
            - **Info Query**: "show settings" → NO actions, just response
            - **Partial**: Some info → REDIRECT with what you know
            
            ## Infer & Apply Defaults
            - Keywords: "coffee" → FOOD, "taxi" → TRANSPORT
            - Context: "same but..." → copy from previous
            - Instructions: Check custom rules
            - Defaults: Mention in Ticket if used
            
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
            
            **Recent Conversation History (for UUID tracing):**
            {conversationHistory}
            """;
    
    private static final String PENDING_CLARIFICATIONS_SECTION = """
            
            ## RESOLVING PENDING CLARIFICATIONS
            
            User has the following PENDING clarifications:
            
            {pendingList}
            
            **Your options:**
            - If resolved → create completed actions
            - If still unclear → NEW PENDING_CLARIFICATION
            - If topic changed → acknowledge, process new request
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
            
            Prompt prompt = new Prompt(
                    List.of(
                            new SystemMessage(systemPrompt),
                            new UserMessage(userPrompt)
                    ),
                    OpenAiChatOptions.builder()
                            .model(MODEL)
                            .maxCompletionTokens(MAX_COMPLETION_TOKENS)
                            .temperature(1.0) // gpt-5-mini (reasoning model) only supports default (1.0)
                            .build()
            );
            
            ChatResponse chatResponse = chatModel.call(prompt);
            String content = chatResponse.getResult().getOutput().getText();
            log.debug("AI response: {}", truncate(content, 400));
            
            MainAgentResponse result = outputConverter.convert(content);
            
            log.info("✅ Parsed: {} actions, response='{}'", 
                    result.getActions().size(), 
                    truncate(result.getResponse(), 50));
            
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
        params.put("linkedUsers", contextMapper.formatLinkedUsersList(context.getLinkedUsers()));
        
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
        
        // JSON Schema
        params.put("formatInstructions", outputConverter.getFormat());
        
        PromptTemplate template = new PromptTemplate(promptTemplate);
        return Objects.requireNonNull(template.render(params), "Prompt template render returned null");
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
