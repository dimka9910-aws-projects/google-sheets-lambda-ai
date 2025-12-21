package com.github.dimka9910.sheets.ai.repository;

import com.github.dimka9910.sheets.ai.dto.user.UserEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import java.util.Optional;

/**
 * Spring Repository for UserEntity in DynamoDB.
 * Uses Spring DI for DynamoDB client and table name.
 * 
 * Table: finance-tracker-users-{env}
 * PK: userName (e.g., "DIMA", "KIKI")
 * GSI: telegramId-index (for lookup from Telegram)
 */
@Slf4j
@Repository
public class UserEntityRepository {

    private final DynamoDbTable<UserEntity> table;
    private final DynamoDbIndex<UserEntity> telegramIdIndex;

    public UserEntityRepository(
            DynamoDbEnhancedClient dynamoDbEnhancedClient,
            @Value("${USERS_TABLE_NAME:users-dev}") String tableName) {
        
        log.info("🔌 Initializing UserEntityRepository: table={}", tableName);

        this.table = dynamoDbEnhancedClient.table(tableName, TableSchema.fromBean(UserEntity.class));
        this.telegramIdIndex = table.index("telegramId-index");
        
        log.info("✅ UserEntityRepository initialized");
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
