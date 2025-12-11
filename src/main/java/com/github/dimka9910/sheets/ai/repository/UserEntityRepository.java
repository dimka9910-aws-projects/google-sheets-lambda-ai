package com.github.dimka9910.sheets.ai.repository;

import com.github.dimka9910.sheets.ai.config.AppConfig;
import com.github.dimka9910.sheets.ai.dto.UserEntity;
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
 * Repository for UserEntity in DynamoDB.
 * 
 * Table: finance-tracker-users-{env}
 * PK: userName (e.g., "DIMA", "KIKI")
 * GSI: telegramId-index (for lookup from Telegram)
 */
@Slf4j
public class UserEntityRepository {

    private final DynamoDbTable<UserEntity> table;
    private final DynamoDbIndex<UserEntity> telegramIdIndex;

    public UserEntityRepository() {
        String tableName = AppConfig.getUsersTableName();
        String region = AppConfig.getAwsRegion();
        
        log.info("Initializing UserEntityRepository: table={}, region={}", tableName, region);
        
        DynamoDbClient dynamoDbClient = DynamoDbClient.builder()
                .region(Region.of(region))
                .build();

        DynamoDbEnhancedClient enhancedClient = DynamoDbEnhancedClient.builder()
                .dynamoDbClient(dynamoDbClient)
                .build();

        this.table = enhancedClient.table(tableName, TableSchema.fromBean(UserEntity.class));
        this.telegramIdIndex = table.index("telegramId-index");
    }

    public UserEntityRepository(DynamoDbEnhancedClient enhancedClient, String tableName) {
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(UserEntity.class));
        this.telegramIdIndex = table.index("telegramId-index");
    }

    /**
     * Get by userName (primary key).
     */
    public Optional<UserEntity> getByUserName(String userName) {
        log.debug("Getting context for userName: {}", userName);
        try {
            UserEntity context = table.getItem(Key.builder()
                    .partitionValue(userName)
                    .build());
            return Optional.ofNullable(context);
        } catch (Exception e) {
            log.error("Error getting context for userName {}: {}", userName, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Get by Telegram ID (via GSI).
     */
    public Optional<UserEntity> getByTelegramId(String telegramId) {
        log.debug("Getting context for telegramId: {}", telegramId);
        try {
            QueryConditional queryConditional = QueryConditional.keyEqualTo(
                    Key.builder().partitionValue(telegramId).build()
            );
            
            return telegramIdIndex.query(queryConditional)
                    .stream()
                    .flatMap(page -> page.items().stream())
                    .findFirst();
        } catch (Exception e) {
            log.error("Error getting context for telegramId {}: {}", telegramId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Save context (create or update).
     */
    public void save(UserEntity context) {
        log.info("Saving context for userName: {}", context.getUserName());
        try {
            table.putItem(context);
            log.debug("Saved context: {}", context.getUserName());
        } catch (Exception e) {
            log.error("Error saving context for {}: {}", context.getUserName(), e.getMessage(), e);
            throw new RuntimeException("Failed to save user context", e);
        }
    }

    /**
     * Delete by userName.
     */
    public void delete(String userName) {
        log.info("Deleting context for userName: {}", userName);
        try {
            table.deleteItem(Key.builder()
                    .partitionValue(userName)
                    .build());
        } catch (Exception e) {
            log.error("Error deleting context for {}: {}", userName, e.getMessage(), e);
            throw new RuntimeException("Failed to delete user context", e);
        }
    }

    /**
     * Check if user exists.
     */
    public boolean exists(String userName) {
        return getByUserName(userName).isPresent();
    }
}
