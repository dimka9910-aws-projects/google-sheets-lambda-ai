package com.github.dimka9910.sheets.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO для отправки в google-sheets-lambda
 * Должен соответствовать RecordDTO из того проекта
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SheetsRecordDTO {

    private Double amount;
    private String currency;
    private String userName;
    private String accountName;
    private String fundName;
    private String operationType;  // String, чтобы совпадать с OperationTypeEnum из sheets-lambda
    private String comment;

    private String secondPerson;
    private String secondAccount;
    private String secondCurrency;
    private Double accountRemains;
    
    // Флаг отмены операции (для удаления из Google Sheets)
    private boolean undo;

    /**
     * Создаёт SheetsRecordDTO из ParsedCommand
     */
    public static SheetsRecordDTO fromParsedCommand(ParsedCommand cmd, String userName) {
        return SheetsRecordDTO.builder()
                .amount(cmd.getAmount())
                .currency(cmd.getCurrency())
                .userName(userName)
                .accountName(cmd.getAccountName())
                .fundName(cmd.getFundName())
                .operationType(cmd.getOperationType().name())
                .comment(cmd.getComment())
                .secondPerson(cmd.getSecondPerson())
                .secondAccount(cmd.getSecondAccount())
                .secondCurrency(cmd.getSecondCurrency())
                .build();
    }
}

