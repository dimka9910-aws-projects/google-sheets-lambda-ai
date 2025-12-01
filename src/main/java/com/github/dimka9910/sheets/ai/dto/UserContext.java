package com.github.dimka9910.sheets.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Контекст пользователя - его настройки, предпочтения, временные указания.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserContext {

    private String userId;
    
    // Валюта по умолчанию
    private String defaultCurrency;
    
    // Счёт по умолчанию
    private String defaultAccount;
    
    // Список кастомных указаний от пользователя
    @Builder.Default
    private List<String> customInstructions = new ArrayList<>();
    
    // Список известных счетов пользователя
    @Builder.Default
    private List<String> knownAccounts = new ArrayList<>();
    
    // Список категорий расходов пользователя
    @Builder.Default
    private List<String> customCategories = new ArrayList<>();
    
    public void addInstruction(String instruction) {
        if (customInstructions == null) {
            customInstructions = new ArrayList<>();
        }
        customInstructions.add(instruction);
    }
    
    public void removeInstruction(String instruction) {
        if (customInstructions != null) {
            customInstructions.remove(instruction);
        }
    }
    
    public void clearInstructions() {
        if (customInstructions != null) {
            customInstructions.clear();
        }
    }
}

