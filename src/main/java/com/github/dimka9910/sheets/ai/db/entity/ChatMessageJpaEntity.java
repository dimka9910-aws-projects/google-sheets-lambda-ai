package com.github.dimka9910.sheets.ai.db.entity;

import com.github.dimka9910.sheets.ai.dto.response.FinancialAction;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JPA Entity for chat_messages table.
 */
@Entity
@Table(name = "chat_messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatMessageJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private MessageRole role;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Type(JsonBinaryType.class)
    @Column(name = "related_financial_actions", columnDefinition = "jsonb")
    private List<FinancialAction> relatedFinancialActions;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (relatedFinancialActions == null) {
            relatedFinancialActions = new ArrayList<>();
        }
    }

    /**
     * Enum for message role (user vs assistant).
     */
    public enum MessageRole {
        user,
        assistant
    }
}

