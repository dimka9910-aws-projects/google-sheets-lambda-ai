package com.github.dimka9910.sheets.ai.db.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimka9910.sheets.ai.db.entity.*;
import com.github.dimka9910.sheets.ai.dto.actions.PendingClarificationAction;
import com.github.dimka9910.sheets.ai.dto.user.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Mapper between JPA entities (PostgreSQL) and UserEntity DTO (legacy DynamoDB format).
 * Maintains backward compatibility with existing code.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserEntityMapper {

    private final ObjectMapper objectMapper;

    // ═══════════════════════════════════════════════════════════════════════════
    // TO DTO (JPA → UserEntity)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Convert JPA entities to UserEntity DTO with all nested data.
     */
    public UserEntity toDto(
            UserJpaEntity userJpa,
            List<AccountJpaEntity> accounts,
            List<FundJpaEntity> funds,
            List<LinkedUserJpaEntity> linkedUsers,
            List<ChatMessageJpaEntity> chatMessages
    ) {
        UserEntity.UserEntityBuilder builder = UserEntity.builder()
                .id(userJpa.getId())  // Database primary key (required for saving operations)
                .userName(userJpa.getUsername())
                .telegramId(userJpa.getTelegramId())
                .displayName(userJpa.getDisplayName())
                .preferredLanguage(userJpa.getPreferredLanguage())
                .defaultCurrency(userJpa.getDefaultCurrency());

        // Convert accounts first (needed for defaultAccount resolution)
        List<AccountEntry> accountEntries = accounts.stream()
                .map(this::toAccountEntry)
                .collect(Collectors.toList());
        builder.accounts(accountEntries);

        // Convert funds first (needed for defaultFund resolution)
        List<FundEntry> fundEntries = funds.stream()
                .map(this::toFundEntry)
                .collect(Collectors.toList());
        builder.funds(fundEntries);

        // Set default account/fund as objects (not just IDs)
        if (userJpa.getDefaultAccountId() != null) {
            accountEntries.stream()
                    .filter(a -> a.getId().equals(userJpa.getDefaultAccountId()))
                    .findFirst()
                    .ifPresent(builder::defaultAccount);
        }

        if (userJpa.getDefaultFundId() != null) {
            fundEntries.stream()
                    .filter(f -> f.getId().equals(userJpa.getDefaultFundId()))
                    .findFirst()
                    .ifPresent(builder::defaultFund);
        }

        // Convert linked users (just metadata, not full contexts yet)
        builder.linkedUsers(linkedUsers.stream()
                .map(this::toLinkedUserEntry)
                .collect(Collectors.toList()));

        // Convert chat messages
        builder.conversationHistory(chatMessages.stream()
                .map(this::toConversationMessage)
                .collect(Collectors.toList()));

        // Parse AI context from JSONB
        if (userJpa.getAiContext() != null) {
            parseAiContext(userJpa.getAiContext(), builder);
        }

        return builder.build();
    }

    /**
     * Lightweight version - only user data, no nested entities.
     */
    public UserEntity toDto(UserJpaEntity userJpa) {
        return toDto(userJpa, List.of(), List.of(), List.of(), List.of());
    }

    private AccountEntry toAccountEntry(AccountJpaEntity jpa) {
        return AccountEntry.builder()
                .id(jpa.getId())  // UUID for internal reference
                .accountId(jpa.getExternalId())
                .displayName(jpa.getDisplayName())
                .aliases(jpa.getAliases() != null ? List.of(jpa.getAliases()) : List.of())
                .build();
    }

    private FundEntry toFundEntry(FundJpaEntity jpa) {
        return FundEntry.builder()
                .id(jpa.getId())  // UUID for internal reference
                .fundId(jpa.getExternalId())
                .displayName(jpa.getDisplayName())
                .aliases(jpa.getAliases() != null ? List.of(jpa.getAliases()) : List.of())
                .build();
    }

    private LinkedUserEntry toLinkedUserEntry(LinkedUserJpaEntity jpa) {
        // Note: userName and name will be resolved later when loading target user
        return LinkedUserEntry.builder()
                .targetUserId(jpa.getTargetUserId())  // Store UUID for loading
                .displayName(jpa.getDisplayName())
                .aliases(jpa.getAliases() != null ? List.of(jpa.getAliases()) : List.of())
                .build();
    }

    private ConversationMessage toConversationMessage(ChatMessageJpaEntity jpa) {
        return ConversationMessage.builder()
                .role(jpa.getRole().name())
                .content(jpa.getContent())
                .wasClarification(jpa.getWasClarification())
                .relatedFinancialActions(jpa.getRelatedFinancialActions())
                .timestamp(jpa.getCreatedAt() != null ? jpa.getCreatedAt().toEpochMilli() : null)
                .build();
    }

    private void parseAiContext(Map<String, Object> aiContext, UserEntity.UserEntityBuilder builder) {
        try {
            // Custom instructions
            if (aiContext.containsKey("customInstructions")) {
                List<String> instructions = objectMapper.convertValue(
                        aiContext.get("customInstructions"),
                        new TypeReference<List<String>>() {}
                );
                builder.customInstructions(instructions);
            }

            // Pending actions
            if (aiContext.containsKey("pendingActions")) {
                List<PendingClarificationAction> actions = objectMapper.convertValue(
                        aiContext.get("pendingActions"),
                        new TypeReference<List<PendingClarificationAction>>() {}
                );
                builder.pendingActions(actions);
            }
        } catch (Exception e) {
            log.warn("Failed to parse AI context: {}", e.getMessage());
            builder.customInstructions(new ArrayList<>());
            builder.pendingActions(new ArrayList<>());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TO JPA (UserEntity → JPA entities)
    // ═══════════════════════════════════════════════════════════════════════════

    public UserJpaEntity toJpaUser(UserEntity dto) {
        UserJpaEntity.UserJpaEntityBuilder builder = UserJpaEntity.builder()
                .username(dto.getUserName())
                .telegramId(dto.getTelegramId())
                .displayName(dto.getDisplayName())
                .preferredLanguage(dto.getPreferredLanguage())
                .defaultCurrency(dto.getDefaultCurrency());

        // Build AI context JSONB
        Map<String, Object> aiContext = new HashMap<>();
        if (dto.getCustomInstructions() != null && !dto.getCustomInstructions().isEmpty()) {
            aiContext.put("customInstructions", dto.getCustomInstructions());
        }
        if (dto.getPendingActions() != null && !dto.getPendingActions().isEmpty()) {
            aiContext.put("pendingActions", dto.getPendingActions());
        }
        builder.aiContext(aiContext);

        return builder.build();
    }

    public AccountJpaEntity toJpaAccount(AccountEntry dto, UUID userId) {
        return AccountJpaEntity.builder()
                .userId(userId)
                .externalId(dto.getAccountId())
                .displayName(dto.getDisplayName())
                .aliases(dto.getAliases() != null ? dto.getAliases().toArray(new String[0]) : new String[0])
                .build();
    }

    public FundJpaEntity toJpaFund(FundEntry dto, UUID userId) {
        return FundJpaEntity.builder()
                .userId(userId)
                .externalId(dto.getFundId())
                .displayName(dto.getDisplayName())
                .aliases(dto.getAliases() != null ? dto.getAliases().toArray(new String[0]) : new String[0])
                .build();
    }

    public LinkedUserJpaEntity toJpaLinkedUser(LinkedUserEntry dto, UUID ownerUserId, UUID targetUserId) {
        return LinkedUserJpaEntity.builder()
                .ownerUserId(ownerUserId)
                .targetUserId(targetUserId)
                .displayName(dto.getDisplayName())
                .aliases(dto.getAliases() != null ? dto.getAliases().toArray(new String[0]) : new String[0])
                .build();
    }

    public ChatMessageJpaEntity toJpaChatMessage(ConversationMessage dto, UUID userId) {
        return ChatMessageJpaEntity.builder()
                .userId(userId)
                .role(ChatMessageJpaEntity.MessageRole.valueOf(dto.getRole()))
                .content(dto.getContent())
                .wasClarification(dto.getWasClarification())
                .relatedFinancialActions(dto.getRelatedFinancialActions())
                .build();
    }
}

