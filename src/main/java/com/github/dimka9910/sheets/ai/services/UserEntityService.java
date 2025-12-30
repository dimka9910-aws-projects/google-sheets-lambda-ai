package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.db.entity.*;
import com.github.dimka9910.sheets.ai.db.mapper.UserEntityMapper;
import com.github.dimka9910.sheets.ai.db.repository.*;
import com.github.dimka9910.sheets.ai.dto.user.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Spring Service for managing user context with PostgreSQL (JPA).
 * Replaces DynamoDB implementation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserEntityService {

    private final UserJpaRepository userRepository;
    private final AccountJpaRepository accountRepository;
    private final FundJpaRepository fundRepository;
    private final LinkedUserJpaRepository linkedUserRepository;
    private final ChatMessageJpaRepository chatMessageRepository;
    private final UserEntityMapper mapper;

    // ═══════════════════════════════════════════════════════════════════════════
    // GET CONTEXT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get context by Telegram ID.
     * Returns Optional.empty() if not found.
     * Loads CORE data (user, accounts, funds) + chat history.
     * 
     * Использует JOIN FETCH для accounts/funds → 2 queries вместо 4!
     */
    @Transactional(readOnly = true)
    public Optional<UserEntity> getByTelegramId(String telegramId) {
        log.info("Getting context for telegramId: {}", telegramId);
        
        // JOIN FETCH: загружает user + accounts + funds за ОДИН запрос
        Optional<UserJpaEntity> userOpt = userRepository.findByTelegramIdWithAccountsAndFunds(telegramId);
        
        if (userOpt.isEmpty()) {
            return Optional.empty();
        }

        UserEntity dto = loadCoreContext(userOpt.get());
        loadChatHistory(dto, 10);  // Load last 10 messages (отдельный запрос)
        
        return Optional.of(dto);
    }

    /**
     * Resolve user context from Telegram ID with loaded linked users.
     * Loads CORE data + chat history + linked users + linked users' full contexts.
     */
    @Transactional(readOnly = true)
    public Optional<UserEntity> resolveWithLinkedUsers(String telegramId) {
        if (telegramId == null || telegramId.isBlank()) {
            log.warn("Cannot resolve: telegramId is null or blank");
            return Optional.empty();
        }

        log.info("Resolving telegramId={}", telegramId);
        
        var found = getByTelegramId(telegramId);
        if (found.isEmpty()) {
            log.info("User not found for telegramId={}", telegramId);
            return Optional.empty();
        }

        UserEntity userContext = found.get();
        String userName = userContext.getUserName();
        log.info("Resolved telegramId={} → userName={}", telegramId, userName);

        // Load linked users with their full contexts
        loadLinkedUsersWithContexts(userContext);

        return Optional.of(userContext);
    }

    /**
     * Save ONLY conversation history + AI context (pending actions, custom instructions).
     * Does NOT modify accounts, funds, or linked users.
     * Use this method after processing a message to avoid constraint violations.
     */
    @Transactional
    public void saveConversationAndAiContext(UserEntity context) {
        log.debug("Saving conversation history + AI context for userName: {}", context.getUserName());
        
        // 1. Find user
        UserJpaEntity userJpa = userRepository.findByUsername(context.getUserName())
                .orElseThrow(() -> new IllegalStateException("User not found: " + context.getUserName()));

        UUID userId = userJpa.getId();

        // 2. Update AI context (custom instructions, pending actions)
        Map<String, Object> aiContext = new HashMap<>();
        if (context.getCustomInstructions() != null && !context.getCustomInstructions().isEmpty()) {
            aiContext.put("customInstructions", context.getCustomInstructions());
        }
        if (context.getPendingActions() != null && !context.getPendingActions().isEmpty()) {
            aiContext.put("pendingActions", context.getPendingActions());
        }
        userJpa.setAiContext(aiContext);
        userRepository.save(userJpa);

        // 3. Save conversation history (append new messages)
        if (context.getConversationHistory() != null && !context.getConversationHistory().isEmpty()) {
            log.debug("💾 Attempting to save conversation history. Total messages in context: {}", context.getConversationHistory().size());
            
            // Get last saved timestamp to identify where old messages end
            List<ChatMessageJpaEntity> lastMessages = chatMessageRepository.findLastNMessages(userId, 1);
            Long lastSavedTimestamp = lastMessages.isEmpty() ? null : 
                (lastMessages.get(0).getCreatedAt() != null ? lastMessages.get(0).getCreatedAt().toEpochMilli() : null);
            
            // Find index where new messages start (first message without timestamp or after last saved)
            int newMessagesStartIdx = 0;
            if (lastSavedTimestamp != null) {
                for (int i = context.getConversationHistory().size() - 1; i >= 0; i--) {
                    ConversationMessage msg = context.getConversationHistory().get(i);
                    if (msg.getTimestamp() != null && msg.getTimestamp() <= lastSavedTimestamp) {
                        newMessagesStartIdx = i + 1;
                        break;
                    }
                }
            }
            
            // Save new messages
            List<ConversationMessage> newMessages = context.getConversationHistory().subList(
                newMessagesStartIdx, context.getConversationHistory().size());
            
            log.debug("💾 New messages to save: {}", newMessages.size());
            
            if (!newMessages.isEmpty()) {
                for (ConversationMessage msgDto : newMessages) {
                    ChatMessageJpaEntity msgJpa = mapper.toJpaChatMessage(msgDto, userId);
                    chatMessageRepository.save(msgJpa);
                }
                
                log.debug("Saved {} new chat messages", newMessages.size());
            } else {
                log.debug("💾 No new messages to save");
            }
        }
        
        log.debug("✅ Saved conversation history + AI context for userName: {}", context.getUserName());
    }

    /**
     * Save FULL user context to PostgreSQL (accounts, funds, linked users, chat history).
     * Use this method for onboarding or when user explicitly modifies their configuration.
     */
    @Transactional
    public void saveContext(UserEntity context) {
        log.info("Saving FULL context for userName: {}", context.getUserName());
        
        // 1. Find or create user
        UserJpaEntity userJpa = userRepository.findByUsername(context.getUserName())
                .orElseGet(() -> {
                    UserJpaEntity newUser = mapper.toJpaUser(context);
                    return userRepository.save(newUser);
                });

        // Update user fields
        userJpa.setTelegramId(context.getTelegramId());
        userJpa.setDisplayName(context.getDisplayName());
        userJpa.setPreferredLanguage(context.getPreferredLanguage());
        userJpa.setDefaultCurrency(context.getDefaultCurrency());
        
        // Update AI context (custom instructions, pending actions)
        Map<String, Object> aiContext = new HashMap<>();
        if (context.getCustomInstructions() != null && !context.getCustomInstructions().isEmpty()) {
            aiContext.put("customInstructions", context.getCustomInstructions());
        }
        if (context.getPendingActions() != null && !context.getPendingActions().isEmpty()) {
            aiContext.put("pendingActions", context.getPendingActions());
        }
        userJpa.setAiContext(aiContext);

        userRepository.save(userJpa);
        UUID userId = userJpa.getId();

        // 2. Save accounts (delete old + insert new)
        accountRepository.deleteByUserId(userId);
        if (context.getAccounts() != null) {
            for (AccountEntry accountDto : context.getAccounts()) {
                AccountJpaEntity accountJpa = mapper.toJpaAccount(accountDto, userId);
                accountJpa = accountRepository.save(accountJpa);
                
                // Update default account ID if this is the default
                if (context.getDefaultAccount() != null && 
                    context.getDefaultAccount().getAccountId().equals(accountDto.getAccountId())) {
                    userJpa.setDefaultAccountId(accountJpa.getId());
                }
            }
        }

        // 3. Save funds (delete old + insert new)
        fundRepository.deleteByUserId(userId);
        if (context.getFunds() != null) {
            for (FundEntry fundDto : context.getFunds()) {
                FundJpaEntity fundJpa = mapper.toJpaFund(fundDto, userId);
                fundJpa = fundRepository.save(fundJpa);
                
                // Update default fund ID if this is the default
                if (context.getDefaultFund() != null && 
                    context.getDefaultFund().getFundId().equals(fundDto.getFundId())) {
                    userJpa.setDefaultFundId(fundJpa.getId());
                }
            }
        }

        // 4. Save linked users (delete old + insert new)
        linkedUserRepository.deleteByOwnerUserId(userId);
        if (context.getLinkedUsers() != null) {
            for (LinkedUserEntry linkedDto : context.getLinkedUsers()) {
                // Resolve target user ID from userName
                UUID targetUserId = linkedDto.getTargetUserId();
                if (targetUserId == null && linkedDto.getUserName() != null) {
                    targetUserId = userRepository.findByUsername(linkedDto.getUserName())
                            .map(UserJpaEntity::getId)
                            .orElse(null);
                }
                
                if (targetUserId != null) {
                    LinkedUserJpaEntity linkedJpa = mapper.toJpaLinkedUser(linkedDto, userId, targetUserId);
                    linkedUserRepository.save(linkedJpa);
                }
            }
        }

        // 5. Save conversation history (append new messages)
        if (context.getConversationHistory() != null && !context.getConversationHistory().isEmpty()) {
            // Get last saved timestamp to identify where old messages end
            List<ChatMessageJpaEntity> lastMessages = chatMessageRepository.findLastNMessages(userId, 1);
            Long lastSavedTimestamp = lastMessages.isEmpty() ? null : 
                (lastMessages.get(0).getCreatedAt() != null ? lastMessages.get(0).getCreatedAt().toEpochMilli() : null);
            
            // Find index where new messages start (first message without timestamp or after last saved)
            int newMessagesStartIdx = 0;
            if (lastSavedTimestamp != null) {
                for (int i = context.getConversationHistory().size() - 1; i >= 0; i--) {
                    ConversationMessage msg = context.getConversationHistory().get(i);
                    if (msg.getTimestamp() != null && msg.getTimestamp() <= lastSavedTimestamp) {
                        newMessagesStartIdx = i + 1;
                        break;
                    }
                }
            }
            
            // Save new messages
            List<ConversationMessage> newMessages = context.getConversationHistory().subList(
                newMessagesStartIdx, context.getConversationHistory().size());
            
            if (!newMessages.isEmpty()) {
                for (ConversationMessage msgDto : newMessages) {
                    ChatMessageJpaEntity msgJpa = mapper.toJpaChatMessage(msgDto, userId);
                    chatMessageRepository.save(msgJpa);
                }
                
                log.debug("Saved {} new chat messages", newMessages.size());
            }
        }

        // Save user again to update default FK references
        userRepository.save(userJpa);
        
        log.info("✅ Saved FULL context for userName: {}", context.getUserName());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // USER MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Delete user by userName.
     */
    @Transactional
    public void deleteUser(String userName) {
        userRepository.findByUsername(userName).ifPresent(user -> {
            userRepository.delete(user);
            log.info("Deleted user {}", userName);
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Load CORE user context: user + accounts + funds.
     * Chat messages и linked users загружаются отдельно по требованию.
     * 
     * ВАЖНО: userJpa должен быть загружен через findByTelegramIdWithAccountsAndFunds(),
     * иначе будут отдельные queries для accounts/funds.
     */
    private UserEntity loadCoreContext(UserJpaEntity userJpa) {
        // Accounts и funds УЖЕ загружены через JOIN FETCH (если использовали правильный метод)
        // или загружаем сейчас (если userJpa пришел без них)
        List<AccountJpaEntity> accounts = userJpa.getAccounts();
        List<FundJpaEntity> funds = userJpa.getFunds();
        
        // Если не загружены (старый код), грузим отдельно
        if (accounts.isEmpty() && funds.isEmpty()) {
            UUID userId = userJpa.getId();
            accounts = accountRepository.findByUserId(userId);
            funds = fundRepository.findByUserId(userId);
        }
        
        // Map to DTO (без chat messages и linked users)
        UserEntity dto = mapper.toDto(userJpa, accounts, funds, List.of(), List.of());
        
        return dto;
    }
    
    /**
     * Load chat history for user (отдельный запрос).
     */
    private void loadChatHistory(UserEntity userContext, int limit) {
        UUID userId = userRepository.findByUsername(userContext.getUserName())
                .map(UserJpaEntity::getId)
                .orElse(null);
        
        if (userId != null) {
            List<ChatMessageJpaEntity> chatMessages = chatMessageRepository.findLastNMessages(userId, limit);
            Collections.reverse(chatMessages);
            
            List<ConversationMessage> messages = chatMessages.stream()
                    .map(jpa -> ConversationMessage.builder()
                            .role(jpa.getRole().name())
                            .content(jpa.getContent())
                            .relatedFinancialActions(jpa.getRelatedFinancialActions())
                            .timestamp(jpa.getCreatedAt() != null ? jpa.getCreatedAt().toEpochMilli() : null)
                            .build())
                    .collect(Collectors.toList());
            
            userContext.setConversationHistory(messages);
        }
    }
    
    /**
     * Load linked users with full contexts.
     * Делает всё за один раз: metadata + full contexts.
     */
    private void loadLinkedUsersWithContexts(UserEntity userContext) {
        UUID userId = userRepository.findByUsername(userContext.getUserName())
                .map(UserJpaEntity::getId)
                .orElse(null);
        
        if (userId == null) return;
        
        // 1. Load linked_users metadata
        List<LinkedUserJpaEntity> linkedUsers = linkedUserRepository.findByOwnerUserId(userId);
        if (linkedUsers.isEmpty()) return;
        
        // 2. Batch resolve target user names
        List<UUID> targetUserIds = linkedUsers.stream()
                .map(LinkedUserJpaEntity::getTargetUserId)
                .distinct()
                .collect(Collectors.toList());
        
        Map<UUID, UserJpaEntity> targetUsersMap = userRepository.findAllById(targetUserIds).stream()
                .collect(Collectors.toMap(UserJpaEntity::getId, u -> u));
        
        // 3. Build LinkedUserEntry + load full contexts
        List<LinkedUserEntry> linkedEntries = new ArrayList<>();
        
        for (LinkedUserJpaEntity linkedJpa : linkedUsers) {
            UUID targetId = linkedJpa.getTargetUserId();
            UserJpaEntity targetUser = targetUsersMap.get(targetId);
            
            if (targetUser == null) continue;
            
            // Build metadata entry
            LinkedUserEntry entry = LinkedUserEntry.builder()
                    .targetUserId(targetId)
                    .userName(targetUser.getUsername())
                    .displayName(linkedJpa.getDisplayName() != null 
                        ? linkedJpa.getDisplayName() 
                        : targetUser.getDisplayName())
                    .aliases(linkedJpa.getAliases() != null 
                        ? Arrays.asList(linkedJpa.getAliases()) 
                        : List.of())
                    .build();
            linkedEntries.add(entry);
            
            // Load full context (accounts, funds, etc.)
            try {
                UserEntity linkedContext = loadCoreContext(targetUser);
                loadChatHistory(linkedContext, 10);
                userContext.addLinkedUserEntity(targetUser.getUsername(), linkedContext);
                log.debug("Loaded linked user full context: {}", targetUser.getUsername());
            } catch (Exception e) {
                log.warn("Failed to load linked user context: {}", targetUser.getUsername(), e);
            }
        }
        
        userContext.setLinkedUsers(linkedEntries);
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ALIAS MANAGEMENT (for CustomInstructionActions)
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Add alias to account by accountId.
     */
    @Transactional
    public boolean addAccountAlias(UUID userId, String accountId, String alias) {
        var accountOpt = accountRepository.findByUserIdAndExternalId(userId, accountId);
        if (accountOpt.isEmpty()) {
            log.warn("Account not found: userId={}, accountId={}", userId, accountId);
            return false;
        }
        
        AccountJpaEntity account = accountOpt.get();
        List<String> aliases = account.getAliases() != null 
            ? new ArrayList<>(Arrays.asList(account.getAliases())) 
            : new ArrayList<>();
        
        if (!aliases.contains(alias)) {
            aliases.add(alias);
            account.setAliases(aliases.toArray(new String[0]));
            accountRepository.save(account);
            log.info("✅ Added alias '{}' to account '{}'", alias, accountId);
            return true;
        } else {
            log.info("⚠️ Alias '{}' already exists for account '{}'", alias, accountId);
            return false;
        }
    }
    
    /**
     * Remove alias from account by accountId.
     */
    @Transactional
    public boolean removeAccountAlias(UUID userId, String accountId, String alias) {
        var accountOpt = accountRepository.findByUserIdAndExternalId(userId, accountId);
        if (accountOpt.isEmpty()) {
            log.warn("Account not found: userId={}, accountId={}", userId, accountId);
            return false;
        }
        
        AccountJpaEntity account = accountOpt.get();
        if (account.getAliases() == null) {
            return false;
        }
        
        List<String> aliases = new ArrayList<>(Arrays.asList(account.getAliases()));
        boolean removed = aliases.remove(alias);
        
        if (removed) {
            account.setAliases(aliases.toArray(new String[0]));
            accountRepository.save(account);
            log.info("✅ Removed alias '{}' from account '{}'", alias, accountId);
        }
        return removed;
    }
    
    /**
     * Add alias to fund by fundId.
     */
    @Transactional
    public boolean addFundAlias(UUID userId, String fundId, String alias) {
        var fundOpt = fundRepository.findByUserIdAndExternalId(userId, fundId);
        if (fundOpt.isEmpty()) {
            log.warn("Fund not found: userId={}, fundId={}", userId, fundId);
            return false;
        }
        
        FundJpaEntity fund = fundOpt.get();
        List<String> aliases = fund.getAliases() != null 
            ? new ArrayList<>(Arrays.asList(fund.getAliases())) 
            : new ArrayList<>();
        
        if (!aliases.contains(alias)) {
            aliases.add(alias);
            fund.setAliases(aliases.toArray(new String[0]));
            fundRepository.save(fund);
            log.info("✅ Added alias '{}' to fund '{}'", alias, fundId);
            return true;
        } else {
            log.info("⚠️ Alias '{}' already exists for fund '{}'", alias, fundId);
            return false;
        }
    }
    
    /**
     * Remove alias from fund by fundId.
     */
    @Transactional
    public boolean removeFundAlias(UUID userId, String fundId, String alias) {
        var fundOpt = fundRepository.findByUserIdAndExternalId(userId, fundId);
        if (fundOpt.isEmpty()) {
            log.warn("Fund not found: userId={}, fundId={}", userId, fundId);
            return false;
        }
        
        FundJpaEntity fund = fundOpt.get();
        if (fund.getAliases() == null) {
            return false;
        }
        
        List<String> aliases = new ArrayList<>(Arrays.asList(fund.getAliases()));
        boolean removed = aliases.remove(alias);
        
        if (removed) {
            fund.setAliases(aliases.toArray(new String[0]));
            fundRepository.save(fund);
            log.info("✅ Removed alias '{}' from fund '{}'", alias, fundId);
        }
        return removed;
    }
    
    /**
     * Add alias to linked user by userName.
     */
    @Transactional
    public boolean addLinkedUserAlias(UUID ownerUserId, String targetUserName, String alias) {
        // Find target user by username
        Optional<UserJpaEntity> targetUserOpt = userRepository.findByUsername(targetUserName);
        if (targetUserOpt.isEmpty()) {
            log.warn("Target user not found: {}", targetUserName);
            return false;
        }
        UUID targetUserId = targetUserOpt.get().getId();
        
        // Find link
        Optional<LinkedUserJpaEntity> linkOpt = linkedUserRepository.findByOwnerUserIdAndTargetUserId(ownerUserId, targetUserId);
        if (linkOpt.isEmpty()) {
            log.warn("Link not found: ownerUserId={}, targetUserName={}", ownerUserId, targetUserName);
            return false;
        }
        
        LinkedUserJpaEntity link = linkOpt.get();
        List<String> aliases = link.getAliases() != null 
            ? new ArrayList<>(Arrays.asList(link.getAliases())) 
            : new ArrayList<>();
        
        if (!aliases.contains(alias)) {
            aliases.add(alias);
            link.setAliases(aliases.toArray(new String[0]));
            linkedUserRepository.save(link);
            log.info("✅ Added alias '{}' to linked user '{}'", alias, targetUserName);
            return true;
        } else {
            log.info("⚠️ Alias '{}' already exists for linked user '{}'", alias, targetUserName);
            return false;
        }
    }
    
    /**
     * Remove alias from linked user by userName.
     */
    @Transactional
    public boolean removeLinkedUserAlias(UUID ownerUserId, String targetUserName, String alias) {
        // Find target user by username
        Optional<UserJpaEntity> targetUserOpt = userRepository.findByUsername(targetUserName);
        if (targetUserOpt.isEmpty()) {
            log.warn("Target user not found: {}", targetUserName);
            return false;
        }
        UUID targetUserId = targetUserOpt.get().getId();
        
        // Find link
        Optional<LinkedUserJpaEntity> linkOpt = linkedUserRepository.findByOwnerUserIdAndTargetUserId(ownerUserId, targetUserId);
        if (linkOpt.isEmpty()) {
            log.warn("Link not found: ownerUserId={}, targetUserName={}", ownerUserId, targetUserName);
            return false;
        }
        
        LinkedUserJpaEntity link = linkOpt.get();
        if (link.getAliases() == null) {
            return false;
        }
        
        List<String> aliases = new ArrayList<>(Arrays.asList(link.getAliases()));
        boolean removed = aliases.remove(alias);
        
        if (removed) {
            link.setAliases(aliases.toArray(new String[0]));
            linkedUserRepository.save(link);
            log.info("✅ Removed alias '{}' from linked user '{}'", alias, targetUserName);
        }
        return removed;
    }
}

