package com.github.dimka9910.sheets.ai.db.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;
import io.hypersistence.utils.hibernate.type.array.StringArrayType;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA Entity for accounts table.
 */
@Entity
@Table(name = "accounts", uniqueConstraints = {
    @UniqueConstraint(name = "uk_accounts_user_external", columnNames = {"user_id", "external_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "external_id", nullable = false)
    private String externalId;  // CARD_DIMA_VISA

    @Column(name = "display_name")
    private String displayName;

    @Type(StringArrayType.class)
    @Column(name = "aliases", columnDefinition = "text[]")
    private String[] aliases;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}

