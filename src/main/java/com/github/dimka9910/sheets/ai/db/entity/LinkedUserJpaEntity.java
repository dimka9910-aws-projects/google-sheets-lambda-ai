package com.github.dimka9910.sheets.ai.db.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;
import io.hypersistence.utils.hibernate.type.array.StringArrayType;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA Entity for linked_users table.
 */
@Entity
@Table(name = "linked_users", uniqueConstraints = {
    @UniqueConstraint(name = "uk_linked_users_owner_target", columnNames = {"owner_user_id", "target_user_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LinkedUserJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(name = "target_user_id", nullable = false)
    private UUID targetUserId;

    @Column(name = "display_name")
    private String displayName;  // "Жена", "Муж"

    @Type(StringArrayType.class)
    @Column(name = "aliases", columnDefinition = "text[]")
    private String[] aliases;  // ["Ксюша", "Kiki"]

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}

