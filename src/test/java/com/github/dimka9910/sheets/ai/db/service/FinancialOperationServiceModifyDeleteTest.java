package com.github.dimka9910.sheets.ai.db.service;

import com.github.dimka9910.sheets.ai.db.entity.FinancialOperation;
import com.github.dimka9910.sheets.ai.db.mapper.FinancialOperationMapper;
import com.github.dimka9910.sheets.ai.db.repository.FinancialOperationRepository;
import com.github.dimka9910.sheets.ai.dto.response.FinancialAction;
import com.github.dimka9910.sheets.ai.dto.user.AccountEntry;
import com.github.dimka9910.sheets.ai.dto.user.FundEntry;
import com.github.dimka9910.sheets.ai.dto.user.UserEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("null")
class FinancialOperationServiceModifyDeleteTest {

    @Test
    void deleteOperation_deletesByLinkId_whenTransfer() {
        FinancialOperationRepository repo = mock(FinancialOperationRepository.class);
        FinancialOperationMapper mapper = mock(FinancialOperationMapper.class);
        FinancialOperationService service = new FinancialOperationService(repo, mapper, "true");

        UUID id = UUID.randomUUID();
        UUID linkId = UUID.randomUUID();
        FinancialOperation existing = FinancialOperation.builder()
                .id(id)
                .userId(UUID.randomUUID())
                .operationType("TRANSFER")
                .linkId(linkId)
                .amount(BigDecimal.valueOf(-100))
                .currency("RSD")
                .transactionDate(LocalDateTime.now())
                .build();

        when(repo.findActiveById(id)).thenReturn(Optional.of(existing));
        when(repo.softDeleteByLinkId(linkId)).thenReturn(2);

        service.deleteOperation(id);

        verify(repo).softDeleteByLinkId(linkId);
        verify(repo, never()).softDelete(id);
    }

    @Test
    void deleteOperation_deletesSingle_whenNoLinkId() {
        FinancialOperationRepository repo = mock(FinancialOperationRepository.class);
        FinancialOperationMapper mapper = mock(FinancialOperationMapper.class);
        FinancialOperationService service = new FinancialOperationService(repo, mapper, "true");

        UUID id = UUID.randomUUID();
        FinancialOperation existing = FinancialOperation.builder()
                .id(id)
                .userId(UUID.randomUUID())
                .operationType("EXPENSE")
                .amount(BigDecimal.valueOf(-100))
                .currency("RSD")
                .transactionDate(LocalDateTime.now())
                .build();

        when(repo.findActiveById(id)).thenReturn(Optional.of(existing));
        when(repo.softDelete(id)).thenReturn(1);

        service.deleteOperation(id);

        verify(repo).softDelete(id);
        verify(repo, never()).softDeleteByLinkId(any());
    }

    @Test
    void modifyOperation_updatesExpenseSignAndAccountFund() {
        FinancialOperationRepository repo = mock(FinancialOperationRepository.class);
        FinancialOperationMapper mapper = mock(FinancialOperationMapper.class);
        FinancialOperationService service = new FinancialOperationService(repo, mapper, "true");

        UUID userId = UUID.randomUUID();
        UUID opId = UUID.randomUUID();
        UUID oldAccountId = UUID.randomUUID();
        UUID oldFundId = UUID.randomUUID();
        FinancialOperation existing = FinancialOperation.builder()
                .id(opId)
                .userId(userId)
                .operationType("EXPENSE")
                .amount(BigDecimal.valueOf(-50))
                .currency("RSD")
                .accountId(oldAccountId)
                .fundId(oldFundId)
                .transactionDate(LocalDateTime.now())
                .description("old")
                .build();

        when(repo.findActiveById(opId)).thenReturn(Optional.of(existing));
        when(repo.save(any(FinancialOperation.class))).thenAnswer(inv -> {
            @SuppressWarnings("null")
            FinancialOperation arg = inv.getArgument(0, FinancialOperation.class);
            return arg;
        });

        UUID newAccountUuid = UUID.randomUUID();
        UUID newFundUuid = UUID.randomUUID();
        UserEntity ctx = UserEntity.builder()
                .id(userId)
                .accounts(List.of(AccountEntry.builder().id(newAccountUuid).accountId("CARD_X").build()))
                .funds(List.of(FundEntry.builder().id(newFundUuid).fundId("FUND_Y").build()))
                .build();

        FinancialAction action = FinancialAction.builder()
                .id(opId)
                .operationType(FinancialAction.OperationType.MODIFY)
                .amount(200.0)
                .currency("EUR")
                .account("CARD_X")
                .fund("FUND_Y")
                .comment("new")
                .correction(true)
                .build();

        service.modifyOperation(opId, action, ctx);

        assertEquals(BigDecimal.valueOf(-200.0), existing.getAmount());
        assertEquals("EUR", existing.getCurrency());
        assertEquals(newAccountUuid, existing.getAccountId());
        assertEquals(newFundUuid, existing.getFundId());
        assertEquals("new", existing.getDescription());
    }

    @Test
    void modifyOperation_updatesTransferBothSides() {
        FinancialOperationRepository repo = mock(FinancialOperationRepository.class);
        FinancialOperationMapper mapper = mock(FinancialOperationMapper.class);
        FinancialOperationService service = new FinancialOperationService(repo, mapper, "true");

        UUID userId = UUID.randomUUID();
        UUID opId = UUID.randomUUID();
        UUID linkId = UUID.randomUUID();

        FinancialOperation anySide = FinancialOperation.builder()
                .id(opId)
                .userId(userId)
                .operationType("TRANSFER")
                .linkId(linkId)
                .amount(BigDecimal.valueOf(-10))
                .currency("RSD")
                .transactionDate(LocalDateTime.now())
                .build();

        FinancialOperation debit = FinancialOperation.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .operationType("TRANSFER")
                .linkId(linkId)
                .amount(BigDecimal.valueOf(-10))
                .currency("RSD")
                .accountId(UUID.randomUUID())
                .transactionDate(LocalDateTime.now())
                .description("old (from)")
                .build();

        FinancialOperation credit = FinancialOperation.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .operationType("TRANSFER")
                .linkId(linkId)
                .amount(BigDecimal.valueOf(10))
                .currency("RSD")
                .accountId(UUID.randomUUID())
                .transactionDate(LocalDateTime.now())
                .description("old (to)")
                .build();

        when(repo.findActiveById(opId)).thenReturn(Optional.of(anySide));
        when(repo.findByLinkId(linkId)).thenReturn(List.of(debit, credit));
        when(repo.save(any(FinancialOperation.class))).thenAnswer(inv -> {
            @SuppressWarnings("null")
            FinancialOperation arg = inv.getArgument(0, FinancialOperation.class);
            return arg;
        });

        UUID srcUuid = UUID.randomUUID();
        UUID dstUuid = UUID.randomUUID();
        UserEntity ctx = UserEntity.builder()
                .id(userId)
                .accounts(List.of(
                        AccountEntry.builder().id(srcUuid).accountId("SRC").build(),
                        AccountEntry.builder().id(dstUuid).accountId("DST").build()
                ))
                .build();

        FinancialAction action = FinancialAction.builder()
                .id(opId)
                .operationType(FinancialAction.OperationType.MODIFY)
                .amount(300.0)
                .currency("EUR")
                .account("SRC")
                .targetAccount("DST")
                .comment("updated")
                .correction(true)
                .build();

        service.modifyOperation(opId, action, ctx);

        assertEquals(BigDecimal.valueOf(-300.0), debit.getAmount());
        assertEquals(BigDecimal.valueOf(300.0), credit.getAmount());
        assertEquals("EUR", debit.getCurrency());
        assertEquals("EUR", credit.getCurrency());
        assertEquals(srcUuid, debit.getAccountId());
        assertEquals(dstUuid, credit.getAccountId());
        assertEquals("updated (from)", debit.getDescription());
        assertEquals("updated (to)", credit.getDescription());
    }
}


