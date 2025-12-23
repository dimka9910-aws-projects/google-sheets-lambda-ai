package com.github.dimka9910.sheets.ai.dto.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Linked user entry for shared finances.
 * Now stored in PostgreSQL linked_users table.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LinkedUserEntry {
    
    /**
     * UUID of target user (PostgreSQL).
     * Used to load target user's data.
     */
    private UUID targetUserId;
    
    /**
     * System user name (e.g., "KIKI", "DIMA") — resolved from targetUserId.
     */
    private String userName;
    
    /**
     * Display name (can be same as userName or more friendly, e.g., "Kiki", "Дима")
     */
    private String displayName;
    
    /**
     * Alternative names/references for this person.
     * E.g. ["girlfriend", "девушка", "жена", "wife", "она", "her"]
     */
    @Builder.Default
    private List<String> aliases = new ArrayList<>();
    
    /**
     * Check if a reference matches this linked user.
     */
    public boolean matches(String reference) {
        if (reference == null) return false;
        String lower = reference.toLowerCase().trim();
        
        if (userName != null && userName.toLowerCase().equals(lower)) return true;
        if (displayName != null && displayName.toLowerCase().equals(lower)) return true;
        if (aliases != null) {
            for (String alias : aliases) {
                if (alias.toLowerCase().equals(lower)) return true;
            }
        }
        return false;
    }
    
    /**
     * Get display name, fallback to userName if not set.
     */
    public String getName() {
        return displayName != null ? displayName : userName;
    }
}

