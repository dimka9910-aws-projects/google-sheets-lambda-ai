package com.github.dimka9910.sheets.ai.dto.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Account entry for user's financial accounts.
 * Now stored in PostgreSQL accounts table.
 * 
 * Examples:
 * - accountId: "CARD_DIMA_VISA_RAIF"
 * - displayName: "Visa Raif"
 * - aliases: ["райф", "raif", "виза", "visa"]
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountEntry {
    
    /**
     * Internal UUID from PostgreSQL (accounts.id)
     */
    private UUID id;
    
    /**
     * Unique account identifier (e.g., "CARD_DIMA_VISA_RAIF", "CASH_USD", "REVOLUT")
     * Used in financial operations and Google Sheets.
     */
    private String accountId;
    
    /**
     * Display name for user-friendly output (e.g., "Visa Raif", "Cash USD", "Revolut")
     */
    private String displayName;
    
    /**
     * Alternative names/references for this account.
     * E.g. ["райф", "raif", "виза", "visa", "райфка"]
     */
    @Builder.Default
    private List<String> aliases = new ArrayList<>();
    
    /**
     * Check if a reference matches this account.
     */
    public boolean matches(String reference) {
        if (reference == null) return false;
        String lower = reference.toLowerCase().trim();
        
        if (accountId != null && accountId.toLowerCase().equals(lower)) return true;
        if (displayName != null && displayName.toLowerCase().equals(lower)) return true;
        if (aliases != null) {
            for (String alias : aliases) {
                if (alias.toLowerCase().equals(lower)) return true;
            }
        }
        return false;
    }
    
    /**
     * Get display name, fallback to accountId if not set.
     */
    public String getName() {
        return displayName != null ? displayName : accountId;
    }
}

