package com.github.dimka9910.sheets.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

/**
 * Результат парсинга текстовой команды от AI
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class ParsedCommand {
    
    private OperationTypeEnum operationType;
    
    private Double amount;
    private String currency;
    private String accountName;
    private String fundName;       // Категория расхода (еда, транспорт и т.д.)
    private String comment;
    
    // Для переводов
    private String secondPerson;
    private String secondAccount;
    private String secondCurrency;
    
    // Мета-информация
    private boolean understood;    // Удалось ли распознать команду
    private String errorMessage;   // Сообщение об ошибке, если не удалось
    private String clarification;  // Уточняющий вопрос к пользователю
}

