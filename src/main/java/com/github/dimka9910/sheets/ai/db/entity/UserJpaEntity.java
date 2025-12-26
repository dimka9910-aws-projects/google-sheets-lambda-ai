package com.github.dimka9910.sheets.ai.db.entity;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JPA Entity for users table (PostgreSQL).
 * Maps to the new SQL schema replacing DynamoDB.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String username;  // System ID (e.g., "DIMA")

    @Column(name = "telegram_id", unique = true)
    private String telegramId;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "preferred_language", length = 5)
    private String preferredLanguage;

    @Column(name = "default_currency", length = 10)
    private String defaultCurrency;

    @Column(name = "default_account_id")
    private UUID defaultAccountId;

    @Column(name = "default_fund_id")
    private UUID defaultFundId;

    /**
     * AI Context stored as JSONB:
     * {
     *   "customInstructions": ["instruction1", "instruction2"],
     *   "pendingActions": [...]
     * }
     */
    @Type(JsonBinaryType.class)
    @Column(name = "ai_context", columnDefinition = "jsonb")
    private Map<String, Object> aiContext;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // ═══════════════════════════════════════════════════════════════════════════
    // RELATIONSHIPS для JOIN FETCH (избегаем N+1)
    // Используем SUBSELECT чтобы избежать MultipleBagFetchException
    // ═══════════════════════════════════════════════════════════════════════════

    @OneToMany(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", referencedColumnName = "id")
    @Fetch(FetchMode.SUBSELECT)
    @Builder.Default
    private List<AccountJpaEntity> accounts = new ArrayList<>();

    @OneToMany(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", referencedColumnName = "id")
    @Fetch(FetchMode.SUBSELECT)
    @Builder.Default
    private List<FundJpaEntity> funds = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}

