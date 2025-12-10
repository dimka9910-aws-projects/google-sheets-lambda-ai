package com.github.dimka9910.sheets.ai.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.IOException;
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
     * Can be String (legacy) or Object {type, value}.
     */
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = SuggestedInstructionDeserializer.class)
    private SuggestedInstruction suggestedInstruction;
    
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
     * DEBUG: информация о потраченных токенах
     */
    private String tokenUsage;
    
    /**
     * Request for additional context.
     * If model determines it needs more context to handle request,
     * it returns list of tags: ["SETTINGS", "TRANSFER", etc.]
     * Caller should re-run with additional context loaded.
     */
    private List<String> needsContext;
    
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
     * Предложенная инструкция для Learning.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SuggestedInstruction {
        private String type;   // SET_DEFAULT_CURRENCY, ADD_INSTRUCTION, etc.
        private String value;  // EUR, "coffee = 300", etc.
        
        public SuggestedInstruction(String value) {
            this.type = "RAW";
            this.value = value;
        }
    }
    
    /**
     * Deserializer for SuggestedInstruction - handles both String and Object formats.
     */
    public static class SuggestedInstructionDeserializer extends com.fasterxml.jackson.databind.JsonDeserializer<SuggestedInstruction> {
        @Override
        public SuggestedInstruction deserialize(com.fasterxml.jackson.core.JsonParser p, 
                                                 com.fasterxml.jackson.databind.DeserializationContext ctxt) 
                throws java.io.IOException {
            com.fasterxml.jackson.databind.JsonNode node = p.getCodec().readTree(p);
            
            if (node == null || node.isNull()) {
                return null;
            }
            
            // Handle String format (legacy): "шаурма = еда"
            if (node.isTextual()) {
                return new SuggestedInstruction(node.asText());
            }
            
            // Handle Object format: {"type": "SET_DEFAULT_CURRENCY", "value": "EUR"}
            if (node.isObject()) {
                String type = node.has("type") ? node.get("type").asText() : "RAW";
                String value = node.has("value") && !node.get("value").isNull() 
                        ? node.get("value").asText() : null;
                return new SuggestedInstruction(type, value);
            }
            
            return null;
        }
    }
    
    /**
     * Мета-команда — управление настройками, не финансовая операция.
     * AI распознаёт на любом языке: "покажи настройки" / "show settings" / "显示设置"
     * 
     * Supports both formats from AI:
     * - Object: {"type": "UNDO", "value": null}
     * - String: "UNDO" (shorthand)
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonDeserialize(using = MetaCommand.MetaCommandDeserializer.class)
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
         * - CANCEL_PENDING: отменить pending команды (забей, отмени)
         */
        private String type;
        
        /**
         * Значение (для ADD_ACCOUNT, ADD_FUND, ADD_INSTRUCTION, SET_DEFAULT_*)
         */
        private String value;
        
        public boolean isPresent() {
            return type != null && !type.isEmpty();
        }
        
        /**
         * Custom deserializer: handles both string and object formats.
         */
        public static class MetaCommandDeserializer extends JsonDeserializer<MetaCommand> {
            @Override
            public MetaCommand deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                JsonNode node = p.getCodec().readTree(p);
                
                if (node.isTextual()) {
                    // String format: "UNDO" → MetaCommand(type="UNDO", value=null)
                    return new MetaCommand(node.asText(), null);
                } else if (node.isObject()) {
                    // Object format: {"type": "UNDO", "value": null}
                    String type = node.has("type") ? node.get("type").asText(null) : null;
                    String value = node.has("value") && !node.get("value").isNull() 
                            ? node.get("value").asText() : null;
                    return new MetaCommand(type, value);
                }
                
                return null;
            }
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

