package com.github.dimka9910.sheets.ai.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * DataSource configuration for AWS Lambda with PostgreSQL.
 * 
 * Configures HikariCP connection pool optimized for AWS Lambda environment:
 * - Small pool size (2 connections max)
 * - No idle connections
 * - Fast timeouts
 * 
 * If DATABASE_URL env var is not set, database features are disabled.
 */
@Slf4j
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConditionalOnProperty(name = "DATABASE_URL")
    public DataSource dataSource() {
        String databaseUrl = System.getenv("DATABASE_URL");
        
        if (databaseUrl == null || databaseUrl.isBlank()) {
            log.warn("⚠️ DATABASE_URL not set, using default Spring Boot datasource config");
            return null;
        }

        log.info("🔌 Initializing Lambda-optimized database connection pool...");
        
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(databaseUrl);
        
        // ⚠️ CRITICAL: Lambda optimization
        // Each Lambda instance handles 1 request at a time, but may scale to multiple instances
        config.setMaximumPoolSize(2);           // Max 2 connections per Lambda instance
        config.setMinimumIdle(0);               // No idle connections
        config.setConnectionTimeout(10000);     // 10s max wait
        config.setIdleTimeout(30000);           // 30s idle = close
        config.setMaxLifetime(60000);           // 1min max lifetime
        config.setKeepaliveTime(30000);         // 30s keepalive
        config.setLeakDetectionThreshold(60000); // Detect connection leaks after 60s
        
        // Connection validation
        config.setConnectionTestQuery("SELECT 1");
        config.setValidationTimeout(3000);
        
        // Performance
        config.setAutoCommit(true);
        config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        
        // Pool name for debugging
        config.setPoolName("FinanceTrackerHikariPool");
        
        log.info("✅ Database connection pool initialized (max_pool_size=2, min_idle=0)");
        
        return new HikariDataSource(config);
    }
}

