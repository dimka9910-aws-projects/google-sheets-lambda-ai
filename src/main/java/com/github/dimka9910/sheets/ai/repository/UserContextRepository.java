package com.github.dimka9910.sheets.ai.repository;

import com.github.dimka9910.sheets.ai.config.AppConfig;
import com.github.dimka9910.sheets.ai.dto.UserContext;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.util.Optional;

/**
 * Repository для работы с UserContext в DynamoDB.
 */
@Slf4j
public class UserContextRepository {

    private final DynamoDbTable<UserContext> table;
    private final DynamoDbIndex<UserContext> telegramIdIndex;

    public UserContextRepository() {
        String tableName = AppConfig.getUsersTableName();
        String region = AppConfig.getAwsRegion();
        
        log.info("Initializing UserContextRepository with table: {}, region: {}", tableName, region);
        
        DynamoDbClient dynamoDbClient = DynamoDbClient.builder()
                .region(Region.of(region))
                .build();

        DynamoDbEnhancedClient enhancedClient = DynamoDbEnhancedClient.builder()
                .dynamoDbClient(dynamoDbClient)
                .build();

        this.table = enhancedClient.table(tableName, TableSchema.fromBean(UserContext.class));
        this.telegramIdIndex = table.index("telegramId-index");
    }

    // Конструктор для тестирования
    public UserContextRepository(DynamoDbEnhancedClient enhancedClient, String tableName) {
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(UserContext.class));
        this.telegramIdIndex = table.index("telegramId-index");
    }

    /**
     * Получить контекст по userId
     */
    public Optional<UserContext> getByUserId(String userId) {
        log.debug("Getting user context for userId: {}", userId);
        try {
            UserContext context = table.getItem(Key.builder()
                    .partitionValue(userId)
                    .build());
            return Optional.ofNullable(context);
        } catch (Exception e) {
            log.error("Error getting user context for userId {}: {}", userId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Получить контекст по Telegram ID (через GSI)
     */
    public Optional<UserContext> getByTelegramId(String telegramId) {
        log.debug("Getting user context for telegramId: {}", telegramId);
        try {
            QueryConditional queryConditional = QueryConditional.keyEqualTo(
                    Key.builder().partitionValue(telegramId).build()
            );
            
            return telegramIdIndex.query(queryConditional)
                    .stream()
                    .flatMap(page -> page.items().stream())
                    .findFirst();
        } catch (Exception e) {
            log.error("Error getting user context for telegramId {}: {}", telegramId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Сохранить контекст (создать или обновить)
     */
    public void save(UserContext context) {
        log.info("Saving user context for userId: {}", context.getUserId());
        try {
            table.putItem(context);
            log.debug("Successfully saved user context: {}", context);
        } catch (Exception e) {
            log.error("Error saving user context for userId {}: {}", context.getUserId(), e.getMessage(), e);
            throw new RuntimeException("Failed to save user context", e);
        }
    }

    /**
     * Удалить контекст
     */
    public void delete(String userId) {
        log.info("Deleting user context for userId: {}", userId);
        try {
            table.deleteItem(Key.builder()
                    .partitionValue(userId)
                    .build());
        } catch (Exception e) {
            log.error("Error deleting user context for userId {}: {}", userId, e.getMessage(), e);
            throw new RuntimeException("Failed to delete user context", e);
        }
    }

    /**
     * Проверить существует ли пользователь
     */
    public boolean exists(String userId) {
        return getByUserId(userId).isPresent();
    }
}

