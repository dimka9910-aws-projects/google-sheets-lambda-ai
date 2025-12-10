package com.github.dimka9910.sheets.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

import java.util.ArrayList;
import java.util.List;

/**
 * Linked user entry for shared finances.
 * Stored in UserContext.linkedUsers in DynamoDB.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class LinkedUserEntry {
    
    /**
     * User ID in the system (Telegram ID or internal ID).
     */
    private String userId;
    
    /**
     * Display name (KIKI, DIMA, etc.)
     */
    private String name;
    
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
        
        if (name != null && name.toLowerCase().equals(lower)) return true;
        if (userId != null && userId.equals(lower)) return true;
        if (aliases != null) {
            for (String alias : aliases) {
                if (alias.toLowerCase().equals(lower)) return true;
            }
        }
        return false;
    }
}

