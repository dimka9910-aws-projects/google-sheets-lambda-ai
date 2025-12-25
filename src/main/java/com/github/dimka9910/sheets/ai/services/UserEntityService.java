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
     * Save user context to PostgreSQL.
     */
    @Transactional
    public void saveContext(UserEntity context) {
        log.info("Saving context for userName: {}", context.getUserName());
        
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
        if (context.getConversationHistory() != null) {
            // Get existing message count
            int existingCount = chatMessageRepository.findLastNMessages(userId, 1000).size();
            int newCount = context.getConversationHistory().size();
            
            // Only save new messages (if count increased)
            if (newCount > existingCount) {
                List<ConversationMessage> newMessages = context.getConversationHistory()
                        .subList(existingCount, newCount);
                
                for (ConversationMessage msgDto : newMessages) {
                    ChatMessageJpaEntity msgJpa = mapper.toJpaChatMessage(msgDto, userId);
                    chatMessageRepository.save(msgJpa);
                }
                
                log.debug("Saved {} new chat messages", newMessages.size());
            }
        }

        // Save user again to update default FK references
        userRepository.save(userJpa);
        
        log.info("✅ Saved context for userName: {}", context.getUserName());
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
                            .wasClarification(jpa.getWasClarification())
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
}

