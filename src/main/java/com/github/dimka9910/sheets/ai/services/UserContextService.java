package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.dto.UserContext;
import com.github.dimka9910.sheets.ai.repository.UserContextRepository;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

/**
 * Сервис для управления контекстом пользователей.
 * Читает/пишет в DynamoDB через UserContextRepository.
 */
@Slf4j
public class UserContextService {

    private final UserContextRepository repository;

    public UserContextService() {
        this.repository = new UserContextRepository();
    }

    // Конструктор для тестирования
    public UserContextService(UserContextRepository repository) {
        this.repository = repository;
    }

    /**
     * Получить контекст пользователя по userId.
     * Если не найден — возвращает пустой контекст с дефолтами.
     */
    public UserContext getContext(String userId) {
        log.info("Getting context for userId: {}", userId);
        
        Optional<UserContext> contextOpt = repository.getByUserId(userId);
        
        if (contextOpt.isPresent()) {
            log.debug("Found existing context for userId: {}", userId);
            return contextOpt.get();
        }
        
        // Если пользователь не найден — возвращаем пустой контекст
        log.info("User {} not found in DynamoDB, returning empty context", userId);
        return UserContext.builder()
                .userId(userId)
                .build();
    }

    /**
     * Получить контекст по Telegram ID
     */
    public Optional<UserContext> getContextByTelegramId(String telegramId) {
        log.info("Getting context for telegramId: {}", telegramId);
        return repository.getByTelegramId(telegramId);
    }

    /**
     * Сохранить контекст пользователя
     */
    public void saveContext(UserContext context) {
        log.info("Saving context for userId: {}", context.getUserId());
        repository.save(context);
    }

    /**
     * Добавить кастомную инструкцию
     */
    public void addInstruction(String userId, String instruction) {
        UserContext context = getContext(userId);
        context.addInstruction(instruction);
        saveContext(context);
        log.info("Added instruction for user {}: {}", userId, instruction);
    }

    /**
     * Удалить инструкцию по индексу
     */
    public void removeInstruction(String userId, int index) {
        UserContext context = getContext(userId);
        context.removeInstruction(index);
        saveContext(context);
        log.info("Removed instruction {} for user {}", index, userId);
    }

    /**
     * Установить валюту по умолчанию
     */
    public void setDefaultCurrency(String userId, String currency) {
        UserContext context = getContext(userId);
        context.setDefaultCurrency(currency);
        saveContext(context);
        log.info("Set default currency for user {}: {}", userId, currency);
    }

    /**
     * Установить счёт по умолчанию
     */
    public void setDefaultAccount(String userId, String account) {
        UserContext context = getContext(userId);
        context.setDefaultAccount(account);
        saveContext(context);
        log.info("Set default account for user {}: {}", userId, account);
    }

    /**
     * Установить дефолтный фонд для личных трат
     */
    public void setDefaultPersonalFund(String userId, String fund) {
        UserContext context = getContext(userId);
        context.setDefaultPersonalFund(fund);
        saveContext(context);
        log.info("Set default personal fund for user {}: {}", userId, fund);
    }

    /**
     * Установить дефолтный фонд для совместных трат
     */
    public void setDefaultSharedFund(String userId, String fund) {
        UserContext context = getContext(userId);
        context.setDefaultSharedFund(fund);
        saveContext(context);
        log.info("Set default shared fund for user {}: {}", userId, fund);
    }

    /**
     * Добавить счёт
     */
    public void addAccount(String userId, String account) {
        UserContext context = getContext(userId);
        context.addAccount(account);
        saveContext(context);
        log.info("Added account for user {}: {}", userId, account);
    }

    /**
     * Добавить фонд
     */
    public void addFund(String userId, String fund) {
        UserContext context = getContext(userId);
        context.addFund(fund);
        saveContext(context);
        log.info("Added fund for user {}: {}", userId, fund);
    }

    /**
     * Очистить все кастомные инструкции
     */
    public void clearInstructions(String userId) {
        UserContext context = getContext(userId);
        context.clearInstructions();
        saveContext(context);
        log.info("Cleared instructions for user {}", userId);
    }

    /**
     * Привязать Telegram ID к пользователю
     */
    public void linkTelegram(String userId, String telegramId) {
        UserContext context = getContext(userId);
        context.setTelegramId(telegramId);
        saveContext(context);
        log.info("Linked telegramId {} to user {}", telegramId, userId);
    }

    /**
     * Удалить пользователя полностью (для reset/debug)
     */
    public void deleteUser(String userId) {
        repository.delete(userId);
        log.info("Deleted user {}", userId);
    }

    /**
     * Получить все настройки пользователя в текстовом виде
     */
    public String getContextSummary(String userId) {
        UserContext context = getContext(userId);
        StringBuilder sb = new StringBuilder();
        
        if (context.getDisplayName() != null) {
            sb.append("Имя: ").append(context.getDisplayName()).append("\n");
        }
        
        if (context.getDefaultCurrency() != null) {
            sb.append("Валюта по умолчанию: ").append(context.getDefaultCurrency()).append("\n");
        }
        
        if (context.getDefaultAccount() != null) {
            sb.append("Счёт по умолчанию: ").append(context.getDefaultAccount()).append("\n");
        }
        
        if (context.getDefaultPersonalFund() != null) {
            sb.append("Фонд для личных трат: ").append(context.getDefaultPersonalFund()).append("\n");
        }
        
        if (context.getDefaultSharedFund() != null) {
            sb.append("Фонд для общих трат: ").append(context.getDefaultSharedFund()).append("\n");
        }
        
        List<String> accounts = context.getAccounts();
        if (accounts != null && !accounts.isEmpty()) {
            sb.append("Счета: ").append(String.join(", ", accounts)).append("\n");
        }
        
        List<String> funds = context.getFunds();
        if (funds != null && !funds.isEmpty()) {
            sb.append("Фонды: ").append(String.join(", ", funds)).append("\n");
        }
        
        List<String> linkedUsers = context.getLinkedUsers();
        if (linkedUsers != null && !linkedUsers.isEmpty()) {
            sb.append("Связанные пользователи: ").append(String.join(", ", linkedUsers)).append("\n");
        }
        
        List<String> instructions = context.getCustomInstructions();
        if (instructions != null && !instructions.isEmpty()) {
            sb.append("Особые указания:\n");
            for (int i = 0; i < instructions.size(); i++) {
                sb.append("  ").append(i).append(". ").append(instructions.get(i)).append("\n");
            }
        }
        
        return sb.toString();
    }
}
