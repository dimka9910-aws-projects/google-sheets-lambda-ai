package com.github.dimka9910.sheets.ai.dto.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

import java.util.ArrayList;
import java.util.List;

/**
 * Fund/category entry for expense categorization.
 * Stored in UserEntity.funds in DynamoDB.
 * 
 * Examples:
 * - fundId: "FOOD"
 * - displayName: "Food & Dining"
 * - aliases: ["еда", "кафе", "restaurant", "groceries"]
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class FundEntry {
    
    /**
     * Unique fund identifier (e.g., "FOOD", "TRANSPORT", "ENTERTAINMENT")
     * Used in financial operations and Google Sheets.
     */
    private String fundId;
    
    /**
     * Display name for user-friendly output (e.g., "Food & Dining", "Transport", "Fun")
     */
    private String displayName;
    
    /**
     * Alternative names/references for this fund.
     * E.g. ["еда", "food", "кафе", "cafe", "restaurant", "groceries"]
     */
    @Builder.Default
    private List<String> aliases = new ArrayList<>();
    
    /**
     * Check if a reference matches this fund.
     */
    public boolean matches(String reference) {
        if (reference == null) return false;
        String lower = reference.toLowerCase().trim();
        
        if (fundId != null && fundId.toLowerCase().equals(lower)) return true;
        if (displayName != null && displayName.toLowerCase().equals(lower)) return true;
        if (aliases != null) {
            for (String alias : aliases) {
                if (alias.toLowerCase().equals(lower)) return true;
            }
        }
        return false;
    }
    
    /**
     * Get display name, fallback to fundId if not set.
     */
    public String getName() {
        return displayName != null ? displayName : fundId;
    }
}

