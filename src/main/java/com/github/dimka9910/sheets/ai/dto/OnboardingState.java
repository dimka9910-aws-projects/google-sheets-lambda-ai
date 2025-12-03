package com.github.dimka9910.sheets.ai.dto;

/**
 * Состояние онбординга пользователя
 */
public enum OnboardingState {
    NOT_STARTED,    // Онбординг не начат (старый пользователь)
    ASK_NAME,       // Спрашиваем имя
    ASK_CURRENCY,   // Спрашиваем валюту
    ASK_ACCOUNTS,   // Спрашиваем счета
    ASK_FUNDS,      // Спрашиваем фонды/категории
    ASK_LINKED,     // Спрашиваем про связанных пользователей (опционально)
    COMPLETED       // Онбординг завершён
}

