package com.github.dimka9910.sheets.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Результат парсинга — может содержать несколько команд.
 * Например: "кофе 300, такси 500" → 2 команды
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParsedCommandList {
    
    /**
     * Список распознанных команд (может быть 1 или несколько)
     */
    private List<ParsedCommand> commands;
    
    /**
     * Общий статус — все команды распознаны успешно
     */
    private boolean understood;
    
    /**
     * Уточняющий вопрос (если что-то непонятно)
     */
    private String clarification;
    
    /**
     * Сообщение об ошибке
     */
    private String errorMessage;
    
    /**
     * Предложенная инструкция для сохранения (Learning).
     * AI предлагает если заметил паттерн, который стоит запомнить.
     * Например: "шаурма = еда (FOOD category)"
     */
    private String suggestedInstruction;
    
    /**
     * Флаг коррекции — пользователь исправляет последнюю операцию.
     * Если true → нужно отменить lastOperation и записать новую.
     */
    private boolean correction;
    
    /**
     * Запрос на установку дефолтных значений.
     * Если не null — обновить дефолты пользователя.
     */
    private SetAsDefault setAsDefault;
    
    /**
     * Мета-команда (не финансовая операция).
     * AI определяет по смыслу сообщения на любом языке.
     */
    private MetaCommand metaCommand;
    
    /**
     * Вложенный класс для установки дефолтов
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SetAsDefault {
        private String account;
        private String currency;
        private String fund;
        
        public boolean hasAny() {
            return account != null || currency != null || fund != null;
        }
    }
    
    /**
     * Мета-команда — управление настройками, не финансовая операция.
     * AI распознаёт на любом языке: "покажи настройки" / "show settings" / "显示设置"
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetaCommand {
        /**
         * Тип команды:
         * - SHOW_SETTINGS: показать настройки
         * - ADD_ACCOUNT: добавить счёт
         * - ADD_FUND: добавить фонд/категорию
         * - ADD_INSTRUCTION: запомнить инструкцию
         * - SET_DEFAULT_CURRENCY: установить валюту по умолчанию
         * - SET_DEFAULT_ACCOUNT: установить счёт по умолчанию
         * - CLEAR_INSTRUCTIONS: очистить инструкции
         * - UNDO: отменить последнюю операцию
         * - HELP: помощь/примеры
         */
        private String type;
        
        /**
         * Значение (для ADD_ACCOUNT, ADD_FUND, ADD_INSTRUCTION, SET_DEFAULT_*)
         */
        private String value;
        
        public boolean isPresent() {
            return type != null && !type.isEmpty();
        }
    }
    
    /**
     * Создаёт список из одной команды (для обратной совместимости)
     */
    public static ParsedCommandList single(ParsedCommand cmd) {
        return ParsedCommandList.builder()
                .commands(List.of(cmd))
                .understood(cmd.isUnderstood())
                .clarification(cmd.getClarification())
                .errorMessage(cmd.getErrorMessage())
                .build();
    }
    
    /**
     * Проверяет, содержит ли ровно одну команду
     */
    public boolean isSingle() {
        return commands != null && commands.size() == 1;
    }
    
    /**
     * Возвращает первую команду (для обратной совместимости)
     */
    public ParsedCommand getFirst() {
        return commands != null && !commands.isEmpty() ? commands.get(0) : null;
    }
    
    /**
     * Количество команд
     */
    public int size() {
        return commands != null ? commands.size() : 0;
    }
}

