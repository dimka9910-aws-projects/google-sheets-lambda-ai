package com.github.dimka9910.sheets.ai.services.agents;

import com.github.dimka9910.sheets.ai.dto.response.FinancialAction;
import com.github.dimka9910.sheets.ai.dto.response.FinancialAction.OperationType;
import com.github.dimka9910.sheets.ai.dto.response.FinancialAgentResponse;
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
import org.apache.commons.collections4.CollectionUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Universal financial agent handling ALL simple financial operations:
 * - EXPENSE (simple spending)
 * - TRANSFER (between own accounts OR with linked users)
 * - INCOME (receiving money)
 * - EXPENSE for linked users (spending on their funds)
 * 
 * Uses gpt-4o-mini for fast, cost-effective processing.
 * Replaces: SimpleExpenseAgent, InternalTransferAgent, ThirdPartyActionAgent
 */
@Slf4j
@Component
public class FinancialAgent {
    
    private static final String MODEL = "gpt-4o-mini";
    private static final int MAX_TOKENS = 600;
    
    private final ChatModel chatModel;
    private final UserContextToPromptMapper contextMapper;
    
    // Cache converter to avoid reflection overhead on each call
    private final BeanOutputConverter<FinancialAgentResponse> outputConverter;
    
    public FinancialAgent(ChatModel chatModel, UserContextToPromptMapper contextMapper) {
        this.chatModel = chatModel;
        this.contextMapper = contextMapper;
        // Initialize converter once (expensive reflection operation)
        this.outputConverter = new BeanOutputConverter<>(FinancialAgentResponse.class);
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PROCESS
    // ═══════════════════════════════════════════════════════════════════════════
    
    public FinancialAgentResponse process(String message, UserEntity userContext) {
        return process(message, userContext, true);
    }
    
    /**
     * Process financial operation with optional linked users context.
     * 
     * @param includeLinkedUsersContext If true, includes linked users in prompt (for third-party operations).
     *                                   If false, omits linked users to save tokens (for simple operations).
     */
    public FinancialAgentResponse process(String message, UserEntity userContext, boolean includeLinkedUsersContext) {
        log.info("🔷 FinancialAgent processing: \"{}\" (includeLinkedUsers={})", message, includeLinkedUsersContext);
        
        if (message == null || message.isBlank()) {
            String lang = com.github.dimka9910.sheets.ai.util.UserFacingText.detectLanguage(userContext, message);
            return FinancialAgentResponse.builder()
                    .financialActions(List.of())
                    .message(com.github.dimka9910.sheets.ai.util.UserFacingText.emptyMessage(lang))
                    .build();
        }
        
        try {
            // Build prompt and append JSON schema
            String systemPrompt = buildSystemPrompt(userContext, includeLinkedUsersContext);
            String userPrompt = "User message: " + message;
            
            // Add JSON schema to prompt (cached, no reflection overhead)
            String jsonSchema = outputConverter.getFormat();
            systemPrompt += "\n\n" + jsonSchema;
            
            // Create Spring AI Prompt
            @SuppressWarnings("null")
            Prompt prompt = new Prompt(
                    List.of(
                            new SystemMessage(systemPrompt),
                            new UserMessage(userPrompt)
                    ),
                    OpenAiChatOptions.builder()
                            .model(MODEL)
                            .maxCompletionTokens(MAX_TOKENS)
                            .temperature(0.0)  // Deterministic for consistent parsing
                            .build()
            );
            
            // Call LLM
            ChatResponse chatResponse = chatModel.call(prompt);
            String content = chatResponse.getResult().getOutput().getText();
            
            if (content == null || content.isBlank()) {
                log.error("❌ Empty response from LLM");
                String lang = com.github.dimka9910.sheets.ai.util.UserFacingText.detectLanguage(userContext, message);
                return FinancialAgentResponse.builder()
                        .financialActions(List.of())
                        .message(com.github.dimka9910.sheets.ai.util.UserFacingText.emptyModelResponse(lang))
                        .build();
            }
            
            // Parse FinancialAgentResponse using BeanOutputConverter
            FinancialAgentResponse result = outputConverter.convert(content);
            
            // Normalize "not set" to null (model may echo our default placeholder)
            normalizeNotSet(result);

            // Enforce policy: do NOT infer Fund from purchase item text.
            // If user didn't explicitly reference a fund/category/budget (or didn't literally mention a fund name/id),
            // we treat fund as "unspecified" and rely on defaults / clarification.
            enforceFundSelectionPolicy(result, userContext, message);

            // Apply defaults in code (prefer defaults over model guessing).
            applyDefaults(result, userContext);
            
            // Validate that model followed instructions
            validateResult(result, userContext);
            
            log.info("✅ FinancialAgent result: {} financial actions, pending={}", 
                    !CollectionUtils.isEmpty(result.getFinancialActions()) ? result.getFinancialActions().size() : 0, 
                    !CollectionUtils.isEmpty(result.getPendingClarifications()));
            
            return result;
            
        } catch (ModelValidationException e) {
            log.warn("⚠️ FinancialAgent model validation failed: {}", e.getMessage());
            String clarificationMessage = buildUserFriendlyClarificationMessage(e, userContext, message);
            return FinancialAgentResponse.builder()
                    .financialActions(List.of())
                    .pendingClarifications(List.of(
                            com.github.dimka9910.sheets.ai.dto.response.PendingClarificationAction.builder()
                                    .context(buildPendingContext(e))
                                    .build()
                    ))
                    .message(clarificationMessage)
                    .build();
        } catch (Exception e) {
            log.error("❌ FinancialAgent error: {}", e.getMessage(), e);
            String lang = com.github.dimka9910.sheets.ai.util.UserFacingText.detectLanguage(userContext, message);
            return FinancialAgentResponse.builder()
                    .financialActions(List.of())
                    .message(com.github.dimka9910.sheets.ai.util.UserFacingText.genericError(lang))
                    .build();
        }
    }
    
    /**
     * Normalize "not set" placeholder to null (model may return our default placeholder literally).
     */
    private void normalizeNotSet(FinancialAgentResponse response) {
        if (response.getFinancialActions() == null) return;
        for (FinancialAction action : response.getFinancialActions()) {
            if ("not set".equals(action.getAccount())) action.setAccount(null);
            if ("not set".equals(action.getTargetAccount())) action.setTargetAccount(null);
            if ("not set".equals(action.getFund())) action.setFund(null);
        }
    }

    /**
     * Backend-enforced defaults (so model doesn't need to guess).
     * Rule: if a field wasn't explicitly provided, use defaults when available; otherwise keep null.
     */
    private void applyDefaults(FinancialAgentResponse response, UserEntity userContext) {
        if (response.getFinancialActions() == null) return;
        String defaultAccountId = userContext.getDefaultAccount() != null ? userContext.getDefaultAccount().getAccountId() : null;
        String defaultFundId = userContext.getDefaultFund() != null ? userContext.getDefaultFund().getFundId() : null;
        String defaultCurrency = userContext.getDefaultCurrency();

        for (FinancialAction action : response.getFinancialActions()) {
            if (action == null || action.getOperationType() == null) continue;

            if (action.getCurrency() == null && defaultCurrency != null) {
                action.setCurrency(defaultCurrency);
            }

            switch (action.getOperationType()) {
                case EXPENSE -> {
                    if (action.getAccount() == null && defaultAccountId != null) action.setAccount(defaultAccountId);
                    if (action.getFund() == null && defaultFundId != null) action.setFund(defaultFundId);
                }
                case INCOME -> {
                    if (action.getAccount() == null && defaultAccountId != null) action.setAccount(defaultAccountId);
                }
                case TRANSFER -> {
                    // For TRANSFER, defaults are ambiguous: leave missing accounts as-is (will be clarified/validated).
                }
                default -> {
                    // no-op
                }
            }
        }
    }

    /**
     * Fund selection policy:
     * - If user explicitly references a fund/category/budget OR literally mentions a known fund token -> allow model-chosen fund.
     * - Otherwise -> do not allow inference; clear fund so defaults/clarification apply.
     */
    private void enforceFundSelectionPolicy(FinancialAgentResponse response, UserEntity userContext, String userMessage) {
        if (response == null || response.getFinancialActions() == null) return;
        String msg = userMessage != null ? userMessage.toLowerCase() : "";

        boolean hasFundSignalWord = containsAny(msg,
                " fund", "fund ", "category", "budget", "bucket",
                " фонд", "фонд ", "категор", "бюджет"
        );

        // Build known fund tokens (externalId, displayName, aliases) to detect literal mentions.
        Set<String> fundTokens = new HashSet<>();
        if (userContext != null && userContext.getFunds() != null) {
            for (var f : userContext.getFunds()) {
                if (f == null) continue;
                if (f.getFundId() != null) fundTokens.add(f.getFundId().toLowerCase());
                if (f.getDisplayName() != null) fundTokens.add(f.getDisplayName().toLowerCase());
                if (f.getAliases() != null) {
                    for (String a : f.getAliases()) {
                        if (a != null && !a.isBlank()) fundTokens.add(a.toLowerCase());
                    }
                }
            }
        }

        boolean literallyMentionsKnownFund = false;
        for (String t : fundTokens) {
            if (t.length() < 3) continue;
            if (msg.contains(t)) {
                literallyMentionsKnownFund = true;
                break;
            }
        }

        // If no explicit fund signal and no literal mention of a known fund -> clear fund to prevent inference.
        if (!hasFundSignalWord && !literallyMentionsKnownFund) {
            for (FinancialAction action : response.getFinancialActions()) {
                if (action != null && action.getOperationType() == OperationType.EXPENSE) {
                    action.setFund(null);
                }
            }
        }
    }

    private boolean containsAny(String haystack, String... needles) {
        if (haystack == null || haystack.isBlank()) return false;
        for (String n : needles) {
            if (n == null || n.isBlank()) continue;
            if (haystack.contains(n)) return true;
        }
        return false;
    }

    private String buildPendingContext(ModelValidationException e) {
        // Keep this short so it doesn't bloat context; detailed logs already contain full info.
        String msg = e != null && e.getMessage() != null ? e.getMessage() : "Validation failed";
        if (msg.length() > 240) return msg.substring(0, 240) + "...";
        return msg;
    }

    private String buildUserFriendlyClarificationMessage(ModelValidationException e, UserEntity userContext, String userMessage) {
        String lang = detectLanguage(userContext, userMessage);
        String err = e != null && e.getMessage() != null ? e.getMessage() : "";

        boolean missingAmount = err.contains("Missing: amount") || err.contains("Need: amount") || err.contains("amount");
        boolean missingAccount = err.contains("Missing:") ? err.contains("account") : err.contains("account") && err.contains("requires");
        boolean missingFund = err.contains("Missing:") ? err.contains("fund") : err.contains("fund") && err.contains("requires");

        // Special case: invalid ID returned by model (guardrail)
        boolean invalidAccount = err.contains("AI returned account='") || err.contains("AI returned targetAccount='");
        boolean invalidFund = err.contains("AI returned fund='");

        if ("ru".equals(lang)) {
            if (invalidAccount) {
                return "Не смог однозначно выбрать счёт. Укажи, пожалуйста, какой счёт использовать (из списка твоих счетов).";
            }
            if (invalidFund || missingFund) {
                // If default fund is missing, ask explicitly about fund/category.
                if (userContext == null || userContext.getDefaultFund() == null) {
                    return "Не могу выбрать фонд/категорию для этой траты. Какой фонд использовать?";
                }
                return "Не смог однозначно выбрать фонд/категорию. Подтверди, пожалуйста, какой фонд использовать.";
            }
            if (missingAccount) {
                return "Не могу выбрать счёт для этой операции. Какой счёт использовать?";
            }
            if (missingAmount) {
                return "Сколько именно (сумма)?";
            }
            return "Нужны уточнения, чтобы записать операцию. Что именно ты имел в виду?";
        }

        // Default: English
        if (invalidAccount) {
            return "I couldn't confidently choose the account. Which account should I use?";
        }
        if (invalidFund || missingFund) {
            if (userContext == null || userContext.getDefaultFund() == null) {
                return "I can't choose a fund/category for this expense. Which fund should I use?";
            }
            return "I couldn't confidently choose the fund/category. Which fund should I use?";
        }
        if (missingAccount) {
            return "I can't choose the account for this operation. Which account should I use?";
        }
        if (missingAmount) {
            return "How much was it?";
        }
        return "I need a clarification to record this. What exactly did you mean?";
    }

    private String detectLanguage(UserEntity userContext, String userMessage) {
        String preferred = userContext != null ? userContext.getPreferredLanguage() : null;
        if (preferred != null && !preferred.isBlank()) {
            String p = preferred.trim().toLowerCase();
            // Accept both "ru" and "russian"
            if (p.startsWith("ru")) return "ru";
            if (p.startsWith("en")) return "en";
            return p; // best effort
        }
        if (userMessage != null) {
            for (int i = 0; i < userMessage.length(); i++) {
                char ch = userMessage.charAt(i);
                // Cyrillic blocks
                if ((ch >= '\u0400' && ch <= '\u04FF') || (ch >= '\u0500' && ch <= '\u052F')) {
                    return "ru";
                }
            }
        }
        return "en";
    }
    
    /**
     * Universal validation for all financial operation types.
     * Checks required fields based on operationType.
     */
    private void validateResult(FinancialAgentResponse response, UserEntity userContext) {
        if (response.getFinancialActions() == null) return;

        Set<String> allowedAccounts = buildAllowedAccountIds(userContext);
        Set<String> allowedFunds = buildAllowedFundIds(userContext);
        
        for (FinancialAction financial : response.getFinancialActions()) {
            OperationType type = financial.getOperationType();
            
            // Common fields for all operations
            if (financial.getAmount() == null || financial.getCurrency() == null) {
                throwValidationError("amount and currency are MANDATORY for all operations", financial);
            }

            // Guardrail: prevent invented IDs (accounts/funds)
            requireAllowed("account", financial.getAccount(), allowedAccounts);
            requireAllowed("targetAccount", financial.getTargetAccount(), allowedAccounts);
            requireAllowed("fund", financial.getFund(), allowedFunds);
            
            switch (type) {
                case EXPENSE -> {
                    // EXPENSE: account and fund are mandatory
                    // targetPerson is optional (for third-party expenses)
                    if (financial.getAccount() == null || financial.getFund() == null) {
                        throwValidationError("EXPENSE requires: amount, currency, account, fund", financial);
                    }
                    
                    // If targetPerson is set, validate it's a valid userName
                    if (financial.getTargetPerson() != null) {
                        validateUserName(financial.getTargetPerson(), userContext, "targetPerson");
                    }
                    
                    log.debug("✅ EXPENSE validated: amount={}, fund={}, targetPerson={}", 
                        financial.getAmount(), financial.getFund(), financial.getTargetPerson());
                }
                
                case TRANSFER -> {
                    // TRANSFER: check if it's internal or with linked user
                    // Normalize userName based on context:
                    if (financial.getTargetPerson() != null && financial.getUserName() == null) {
                        // If targetPerson is set but userName is missing, assume sender is currentUser
                        financial.setUserName(userContext.getUserName());
                    } else if (financial.getUserName() != null && financial.getUserName().equals(userContext.getUserName()) && financial.getTargetPerson() == null) {
                        // If userName=currentUser but targetPerson=null, this is internal transfer (model error)
                        financial.setUserName(null);
                    }
                    
                    boolean hasUserNames = financial.getUserName() != null || financial.getTargetPerson() != null;
                    
                    if (hasUserNames) {
                        // TRANSFER with linked user: userName, targetPerson, account, targetAccount mandatory
                        if (financial.getUserName() == null || financial.getTargetPerson() == null ||
                            financial.getAccount() == null || financial.getTargetAccount() == null) {
                            throwValidationError("TRANSFER (linked user) requires: amount, currency, userName, targetPerson, account, targetAccount", financial);
                        }
                        
                        // Validate userName and targetPerson are exact userNames
                        validateUserName(financial.getUserName(), userContext, "userName");
                        validateUserName(financial.getTargetPerson(), userContext, "targetPerson");
                        
                        log.debug("✅ TRANSFER (linked user) validated: {} → {} (amount={})", 
                            financial.getUserName(), financial.getTargetPerson(), financial.getAmount());
                    } else {
                        // TRANSFER between own accounts: account and targetAccount mandatory, no userName/targetPerson
                        if (financial.getAccount() == null || financial.getTargetAccount() == null) {
                            throwValidationError("TRANSFER (between own accounts) requires: amount, currency, account, targetAccount. userName/targetPerson must be NULL.", financial);
                        }
                        
                        log.debug("✅ TRANSFER (between own accounts) validated: {} → {} (amount={})", 
                            financial.getAccount(), financial.getTargetAccount(), financial.getAmount());
                    }
                }
                
                case INCOME -> {
                    // INCOME: account is mandatory
                    if (financial.getAccount() == null) {
                        throwValidationError("INCOME requires: amount, currency, account", financial);
                    }
                    
                    log.debug("✅ INCOME validated: amount={}, account={}", 
                        financial.getAmount(), financial.getAccount());
                }
                
                default -> {
                    log.warn("⚠️ Unknown operationType: {}", type);
                }
            }
        }
    }

    private Set<String> buildAllowedAccountIds(UserEntity userContext) {
        Set<String> ids = new HashSet<>();
        if (userContext.getAccounts() != null) {
            for (var a : userContext.getAccounts()) {
                if (a != null && a.getAccountId() != null) ids.add(a.getAccountId());
            }
        }
        if (userContext.getLinkedUserEntitys() != null) {
            for (UserEntity linked : userContext.getLinkedUserEntitys().values()) {
                if (linked != null && linked.getAccounts() != null) {
                    for (var a : linked.getAccounts()) {
                        if (a != null && a.getAccountId() != null) ids.add(a.getAccountId());
                    }
                }
            }
        }
        return ids;
    }

    private Set<String> buildAllowedFundIds(UserEntity userContext) {
        Set<String> ids = new HashSet<>();
        if (userContext.getFunds() != null) {
            for (var f : userContext.getFunds()) {
                if (f != null && f.getFundId() != null) ids.add(f.getFundId());
            }
        }
        if (userContext.getLinkedUserEntitys() != null) {
            for (UserEntity linked : userContext.getLinkedUserEntitys().values()) {
                if (linked != null && linked.getFunds() != null) {
                    for (var f : linked.getFunds()) {
                        if (f != null && f.getFundId() != null) ids.add(f.getFundId());
                    }
                }
            }
        }
        return ids;
    }

    private void requireAllowed(String field, String value, Set<String> allowed) {
        if (value == null) return;
        if (!allowed.contains(value)) {
            throw new ModelValidationException(
                    "AI returned " + field + "='" + value + "' which is not present in available IDs. " +
                    "Please ask the user to choose a valid " + field + " from their list."
            );
        }
    }

    private static class ModelValidationException extends RuntimeException {
        ModelValidationException(String message) { super(message); }
    }
    
    private void throwValidationError(String requirement, FinancialAction action) {
        String errorMsg = String.format(
            "❌ Model returned invalid action. %s. " +
            "Model MUST fill all fields or return PENDING_CLARIFICATION. " +
            "Received: operationType=%s, amount=%s, currency=%s, account=%s, fund=%s, " +
            "targetAccount=%s, userName=%s, targetPerson=%s",
            requirement,
            action.getOperationType(),
            action.getAmount(),
            action.getCurrency(),
            action.getAccount(),
            action.getFund(),
            action.getTargetAccount(),
            action.getUserName(),
            action.getTargetPerson()
        );
        
        log.error(errorMsg);
        // Treat validation failures as "need clarification" instead of hard errors.
        throw new ModelValidationException(errorMsg);
    }
    
    private void validateUserName(String userName, UserEntity userContext, String fieldName) {
        String currentUserName = userContext.getUserName();
        boolean isValid = userName.equals(currentUserName) ||
                (userContext.getLinkedUsers() != null && userContext.getLinkedUsers().stream()
                        .anyMatch(lu -> lu.getUserName().equals(userName)));
        
        if (!isValid) {
            String errorMsg = String.format(
                "❌ Model returned invalid %s='%s'. " +
                "Must be EXACT userName from linked users or current user. " +
                "currentUser=%s, linkedUsers=%s",
                fieldName, userName,
                currentUserName,
                userContext.getLinkedUsers() != null ? 
                    userContext.getLinkedUsers().stream().map(lu -> lu.getUserName()).toList() : 
                    List.of()
            );
            
            log.error(errorMsg);
            throw new ModelValidationException(errorMsg);
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // BUILD PROMPT
    // ═══════════════════════════════════════════════════════════════════════════
    
    private static final String PROMPT_TEMPLATE = """
        # ROLE: High-Precision Financial Data Extractor
        Your goal: Map human language to a structured JSON financial record.
        
        ## PARSING PROTOCOL (Follow strictly):
        1. **Identify OperationType**:\s
           - TRANSFER: Movement of money between accounts (own accounts OR with linked users).
             * Between own accounts: "transfer 1000 from card to cash" → targetAccount required, targetPerson=null
             * With linked users: "sent 500 to Kiki" → targetAccount+targetPerson required (or just targetPerson if their default account)
           - INCOME: External money coming in.
           - EXPENSE: Everything else (default).
        
        2. **Map Entities (Priority: Explicit > Inferred > Default)**:
           - **Amount**: should be provided Explicitly as number or text or slang.
           - **Currency**: Match explicit word -> Infer from slang -> Use {currency} default.
           - **Account**: Match user keyword to EXACT ID from "Available Accounts" list below.
           - **Fund**: Match user keyword to EXACT ID from "Available Funds" list below.
             
          **⚠️ CRITICAL MATCHING RULES (MUST FOLLOW):**
          
          1. **ONLY USE VALUES FROM AVAILABLE LISTS!**
             - Account MUST be one of the IDs from "Available Accounts" list below
             - Fund MUST be one of the IDs from "Available Funds" list below
             - **NEVER invent or guess values that are not in the lists!**
             - If you can't find a match → PENDING_CLARIFICATION
          
          2. **PHONETIC/TRANSLITERATION MATCHING:**
             - User may write in different script (cyrillic vs latin) - match by sound!
             - Always consider phonetic similarity across scripts
             - Example: if user writes in cyrillic but account name is in latin, match by pronunciation
          
          3. **SEMANTIC MATCHING:**
             - Match by meaning when exact word is not in the list
             - Look for semantically related fund/account in Available lists
             - If the user uses a different language, mentally translate key terms to English before matching
             - **DO NOT infer Fund from the purchase item itself.**
               - If user only describes an expense (e.g., "200 for taxi", "200 for tickets") without explicitly referencing a fund/category/budget:
                 use defaultFund if available, otherwise ask for clarification.
               - Only attempt to match a Fund when the user explicitly references a fund/category/budget (e.g., "to fund X", "category X", "budget X").
               - If user tried to reference a fund but you can't match confidently → PENDING_CLARIFICATION.
          
          4. **FALLBACK PRIORITY:**
             - First: Try to match user's word to Available list (phonetic + semantic)
             - Second: Use default ({defaultAccount} / {defaultFund}) if user didn't specify anything
             - Third: If no default AND can't match → PENDING_CLARIFICATION
          
          5. **WHEN TO ASK (PENDING_CLARIFICATION):**
             - User tried to refer something but NO match found in Available lists
             - You're not 99% confident about the match
             - No default available and user didn't specify
             - **NEVER guess or invent values!**
          
          - Custom Instructions might have special rules for matching, pay attention to them
          
                  
        3. **Handle Linked Users (If applicable)**:
           - if list of Linked Users is not empty - then user's request might be an operation with linked user.
           - first of all try to identify if user's really refers it's linked user, as user clearly should refer one.
             - targetPerson should ALWAYS be one of linked users, never invent unlisted one
             - you should match if it's a linked user by it's name of aliases
             - if user have a linked user "spouse" and says - he transfer money to wife - you can clearly match a linked user
             - if user have linked user "Robert" and says he bought something for bob - you can clearly match a linked user
             - if user have a linked user "Linda" and says he bought a present for mom - it most likely just a simple EXPENSE operation with comment "present for mom" and not a linked user operation.
             - so don't try to invent anything here, if it clearly doesn't match - it's a simple expense operation with a comment.
            - Use EXACT `userName` from the list (do not invent).
             - Never use nicknames or aliases in the `userName` or `targetPerson` fields.
             - also user's Custom Instructions might have special rules for linked user matching, so pay attention to them as well
           - So if you clearly identified that operation involves a LINKED_USER there might be two possible scenarios 
             - Transfer to a linked user, like:
               - I venmo to Alice for lunch 200 bucks
               - gave 100 to Tim from my wallet
               - transfer from my card to Wife 200
               - my wife gave me 200 euro she had
             - Payment with your own money for someone else's fund, in that case you have to make a simple EXPENSE, but pick a fund from Linked User's list.
               - Payed for Alice for her present for mom 200, on her personal budget (here you clearly have to match to some of here personal budgets like MY_DAILY_SPEND)
               - and also payed for her fuel 2000 (here you have to probably pick one of linked user's defaults)
        
        ### Linked Users:
        {linkedUsers}
        
        
        ## FIELD SPECIFIC RULES:
        - **message**: Write a detailed, natural confirmation in user's preferred language: {preferredLanguage}
          **For successful operations, include ALL key details in ONE sentence:**
          - Amount + Currency
          - Operation type (spent/transferred/received)
          - Account (where from)
          - Fund/Category (for expenses) OR Target account (for transfers)
          - Target person (if applicable)
          - Comment (if provided)
          
          **Examples of good confirmations (use ACTUAL account/fund names from Available lists!):**
          - EXPENSE: "Recorded expense 200 {currency} from <ACCOUNT_FROM_LIST> to <FUND_FROM_LIST> (coffee)."
          - TRANSFER: "Recorded transfer 1000 {currency} from <ACCOUNT_FROM_LIST> to <TARGET_ACCOUNT_FROM_LIST>."
          - INCOME: "Recorded income 50000 {currency} to <ACCOUNT_FROM_LIST> (salary)."
          
          **IMPORTANT: Use ACTUAL values from Available Accounts/Funds lists, not placeholder names.**
          
          **For clarifications, ask specific question:**
          - "How much did you spend on coffee?"
          - "Which account to transfer 1000 RSD from?"
        
        # USER CONTEXT (SITUATION AWARENESS)
        - Current User: {currentUser}
        - Preferred Language: {preferredLanguage} ← ALWAYS respond in this language
        - Defaults: Currency: {currency} | Account: {defaultAccount} | Fund: {defaultFund}
        
        ### Available Data:
        - Accounts: {accounts}
        - Funds: {funds}
        
        # USER'S CUSTOM INSTRUCTIONS - (Please pay attention to them, user might apply it's own rules to expense processing, anything from special rules for fund or account or currency selection, default amount, or even math operations to perform,
        or request to make one more financial operation in pair, literally anything, use rules which are relevant to this request):
        {customInstructions}
        
        ## Operation Types
        
        ### 1. EXPENSE - Simple spending (most common)
        **Required fields:** amount, currency, account, fund
        **Optional fields:** targetPerson (if spending FOR someone), comment
        
        **Examples (conceptual - use ACTUAL values from Available lists!):**
        - "coffee 200" → EXPENSE from default account to food-related fund FROM LIST
        - "taxi 500 cash" → EXPENSE from cash account FROM LIST to transport-related fund FROM LIST
        - "bought lunch for Sarah 1500" → EXPENSE with targetPerson (if Sarah is linked user)
        
        ### 2. TRANSFER - Moving money between accounts
        **Case A: Between OWN accounts (no people involved)**
        - **Required:** amount, currency, account (from), targetAccount (to)
        - **userName and targetPerson:** MUST BE NULL
        - **Examples:**
          - "transfer 1000 from card to cash" → TRANSFER between own accounts
          - "withdrew 500 from card" → TRANSFER from card to cash
          - "put 200 on card" → TRANSFER from cash to card
        
        ### 3. TRANSFER - Money to/from linked users
        **Required fields:** amount, currency, account, targetAccount, userName, targetPerson
        **CRITICAL:** userName and targetPerson must be EXACT userNames from Linked Users list
        **Note:** Detailed rules and examples for TRANSFER operations provided below (if linked users context is available)
        
        ### 4. INCOME - Receiving money (rare)
        **Required fields:** amount, currency, account
        **Examples:** "salary 50000", "got paid 3000"
        
      
        ## Examples (use ACTUAL values from Available lists, these are just format examples)
        
        ### EXPENSE:
        - "coffee 200" → {{"operationType": "EXPENSE", "amount": 200, "currency": "{currency}", "account": "{defaultAccount}", "fund": "<FUND_FROM_AVAILABLE_LIST>"}}
        
        ### TRANSFER (internal):
        - "transfer 1000 from card to cash" → {{"operationType": "TRANSFER", "amount": 1000, "currency": "{currency}", "account": "<ACCOUNT_FROM_LIST>", "targetAccount": "<ACCOUNT_FROM_LIST>"}}
        
        ### PENDING_CLARIFICATION (when you CAN'T find match in Available lists):
        - "coffee" → {{"context": "User wants to record coffee expense. Missing: amount."}}
        - "transfer 1000" → {{"context": "User wants to transfer 1000. Missing: source account and target account."}}
        - If user mentions something NOT in Available lists → Ask for clarification, NEVER invent values!
    """;
    
    // Additional section for TRANSFER operations with linked users (conditionally appended)
    private static final String THIRD_PARTY_OPERATIONS_SECTION = """
        
        ## TRANSFER Operations - Detailed Rules
        
        ### When RECEIVING money FROM linked user:
        - **userName**: linked user's userName (who SENDS)
        - **targetPerson**: current user's userName (who RECEIVES)
        - **account**: sender's account (source) - MUST be from Available Accounts
        - **targetAccount**: receiver's account (destination) - MUST be from Available Accounts
        
        ### When SENDING money TO linked user:
        - **userName**: current user's userName (who SENDS)
        - **targetPerson**: linked user's userName (who RECEIVES)
        - **account**: sender's account (source) - MUST be from Available Accounts
        - **targetAccount**: receiver's account (destination) - MUST be from Available Accounts
        
        ### userName and targetPerson Rules:
        - **CRITICAL: Must be EXACT userName from Linked Users list!**
        - Match user's words (names/aliases) to find linked user, then use their EXACT userName
        - ❌ WRONG: using aliases or nicknames in userName/targetPerson fields
        - ✅ CORRECT: using userName field value from the linked users list
        
        ### EXPENSE: Paying FOR someone on THEIR fund (Cross-user expense tracking)
        **This is NOT a TRANSFER! It's an EXPENSE where one person pays but tracks it on another person's budget.**
        
        **Scenario 1: I paid for linked user's expense → Track on THEIR fund**
        - **account**: MY account (I paid from my wallet/card)
        - **fund**: THEIR fund from their funds list (must exist in linked user's funds)
        - **targetPerson**: THEIR userName (who benefits)
        - **Use case**: Tracking expenses per person in shared finances
        
        **Scenario 2: Linked user paid for MY expense → Track on MY fund**
        - **account**: THEIR account (they paid from their wallet/card)
        - **fund**: MY fund from my funds list (must exist in current user's funds)
        - **targetPerson**: MY userName (who benefits)
        - **Use case**: Partner paid for my groceries, but it's my personal budget
        
        **CRITICAL: Fund matching logic:**
        - Look for fund in the BENEFICIARY's (targetPerson) fund list, NOT the payer's
        - Use beneficiary's default fund if fund not specified
        
        ### EXPENSE: Simple "for someone" (Generic third-party expense)
        - If user just says "bought coffee for Sarah" without specifying fund → generic expense
        - Use payer's account and payer's fund (infer from item: coffee→FOOD), set targetPerson
        - Example (format only): "bought coffee for <linked user>" → {"account":"<ACCOUNT_FROM_LIST>","fund":"<FUND_FROM_LIST>","targetPerson":"<LINKED_USER_USERNAME>"}
        """;

    
    private String buildSystemPrompt(UserEntity context, boolean includeLinkedUsersContext) {
        // Step 1: Build complete prompt by concatenating sections
        StringBuilder promptBuilder = new StringBuilder(PROMPT_TEMPLATE);
        
        // Conditionally append third-party operations section (token optimization)
        if (includeLinkedUsersContext) {
            promptBuilder.append(THIRD_PARTY_OPERATIONS_SECTION);
        }
        
        // Step 2: Prepare data parameters
        Map<String, Object> params = new HashMap<>();
        params.put("currentUser", context.getUserName() != null ? context.getUserName() : "USER");
        params.put("preferredLanguage", context.getPreferredLanguage() != null ? context.getPreferredLanguage() : "English");
        params.put("currency", context.getDefaultCurrency() != null ? context.getDefaultCurrency() : "RSD");
        params.put("defaultAccount", context.getDefaultAccount() != null ? 
                context.getDefaultAccount().getAccountId() : "not set");
        params.put("defaultFund", context.getDefaultFund() != null ? 
                context.getDefaultFund().getFundId() : "not set");
        
        String accountsList = contextMapper.formatAccountsList(context.getAccounts());
        String fundsList = contextMapper.formatFundsList(context.getFunds());
        String customInstructions = contextMapper.formatCustomInstructionsSection(context.getCustomInstructions());
        
        params.put("accounts", accountsList != null ? accountsList : "(No accounts)");
        params.put("funds", fundsList != null ? fundsList : "(No funds)");
        params.put("customInstructions", customInstructions != null ? customInstructions : "");
        
        // Include linked users data if requested (token optimization)
        if (includeLinkedUsersContext) {
            // Include linked users WITH their accounts (critical for TRANSFER operations)
            String linkedUsersList = contextMapper.formatLinkedUsersListWithAccounts(
                    context.getLinkedUserEntitys(), 
                    context.getLinkedUsers());
            params.put("linkedUsers", linkedUsersList != null ? linkedUsersList : "(No linked users)");
        } else {
            params.put("linkedUsers", "");
        }
        
        // Step 3: Render final prompt WITHOUT PromptTemplate/StringTemplate (prevents syntax errors with quotes/pipes)
        String rendered = promptBuilder.toString();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            String key = "{" + e.getKey() + "}";
            String value = e.getValue() != null ? String.valueOf(e.getValue()) : "";
            rendered = rendered.replace(key, value);
        }
        return rendered;
    }
}

