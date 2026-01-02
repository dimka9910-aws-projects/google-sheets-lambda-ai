package com.github.dimka9910.sheets.ai.util;

import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.util.UUID;

/**
 * Quick utility to check last messages in database.
 */
@Slf4j
public class CheckLastMessage {

    private static final String DATABASE_URL = System.getenv("DATABASE_URL");
    
    public static void main(String[] args) {
        if (DATABASE_URL == null || DATABASE_URL.isBlank()) {
            log.error("❌ DATABASE_URL environment variable is not set");
            System.exit(1);
        }
        
        try {
            String jdbcUrl = convertToJdbcUrl(DATABASE_URL);
            String username = extractUsername(DATABASE_URL);
            String password = extractPassword(DATABASE_URL);
            
            // Load PostgreSQL driver
            Class.forName("org.postgresql.Driver");
            
            try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
                log.info("📊 Connected to database");
                log.info("");
                
                // Get DIMA user ID
                UUID dimaUserId;
                try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT id FROM users WHERE username = 'DIMA'")) {
                    ResultSet rs = stmt.executeQuery();
                    if (!rs.next()) {
                        log.error("❌ DIMA user not found");
                        return;
                    }
                    dimaUserId = (UUID) rs.getObject("id");
                }
                
                log.info("👤 DIMA user_id: {}", dimaUserId);
                log.info("");
                
                // Get last 20 chat messages (all messages)
                log.info("📝 Last 20 chat messages:");
                try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT role, content, created_at FROM chat_messages " +
                    "WHERE user_id = ? ORDER BY created_at DESC LIMIT 20")) {
                    stmt.setObject(1, dimaUserId);
                    ResultSet rs = stmt.executeQuery();
                    int i = 1;
                    boolean hasMessages = false;
                    while (rs.next()) {
                        hasMessages = true;
                        String role = rs.getString("role");
                        String content = rs.getString("content");
                        Timestamp createdAt = rs.getTimestamp("created_at");
                        log.info("{}. [{}] {} - {}", i++, role, createdAt, content);
                    }
                    if (!hasMessages) {
                        log.info("   (no messages found)");
                    }
                }
                
                log.info("");
                
                // Get last 20 financial operations (all operations)
                log.info("💰 Last 20 financial operations:");
                try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT operation_type, amount, currency, account_id, fund_id, description, transaction_date " +
                    "FROM financial_operations " +
                    "WHERE user_id = ? AND deleted_at IS NULL " +
                    "ORDER BY transaction_date DESC LIMIT 20")) {
                    stmt.setObject(1, dimaUserId);
                    ResultSet rs = stmt.executeQuery();
                    int i = 1;
                    boolean hasOps = false;
                    while (rs.next()) {
                        hasOps = true;
                        String opType = rs.getString("operation_type");
                        String amount = rs.getString("amount");
                        String currency = rs.getString("currency");
                        UUID accountId = (UUID) rs.getObject("account_id");
                        UUID fundId = (UUID) rs.getObject("fund_id");
                        String desc = rs.getString("description");
                        Timestamp txDate = rs.getTimestamp("transaction_date");
                        
                        log.info("{}. {} {} {} | account={} fund={} | {} | {}", 
                            i++, opType, amount, currency, accountId, fundId, desc, txDate);
                    }
                    if (!hasOps) {
                        log.info("   (no operations found)");
                    }
                }
                
                log.info("");
                
                // Search for "етел" or "yettel" or "200" in messages
                log.info("🔍 Searching for 'етел' or 'yettel' or '200' in messages:");
                try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT role, content, created_at FROM chat_messages " +
                    "WHERE user_id = ? AND (LOWER(content) LIKE '%етел%' OR LOWER(content) LIKE '%yettel%' OR content LIKE '%200%') " +
                    "ORDER BY created_at DESC LIMIT 10")) {
                    stmt.setObject(1, dimaUserId);
                    ResultSet rs = stmt.executeQuery();
                    int i = 1;
                    boolean found = false;
                    while (rs.next()) {
                        found = true;
                        String role = rs.getString("role");
                        String content = rs.getString("content");
                        Timestamp createdAt = rs.getTimestamp("created_at");
                        log.info("{}. [{}] {} - {}", i++, role, createdAt, content);
                    }
                    if (!found) {
                        log.info("   (no matching messages found)");
                    }
                }
                
                log.info("");
                
                // Check ALL recent messages (no filter)
                log.info("📨 ALL recent messages (last 5, any user):");
                try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT u.username, cm.role, cm.content, cm.created_at " +
                    "FROM chat_messages cm " +
                    "JOIN users u ON cm.user_id = u.id " +
                    "ORDER BY cm.created_at DESC LIMIT 5")) {
                    ResultSet rs = stmt.executeQuery();
                    int i = 1;
                    boolean hasAny = false;
                    while (rs.next()) {
                        hasAny = true;
                        String user = rs.getString("username");
                        String role = rs.getString("role");
                        String content = rs.getString("content");
                        Timestamp createdAt = rs.getTimestamp("created_at");
                        log.info("{}. [{}] [{}] {} - {}", i++, user, role, createdAt, content);
                    }
                    if (!hasAny) {
                        log.info("   (no messages in database at all)");
                    }
                }
                
            }
            
        } catch (Exception e) {
            log.error("❌ Error", e);
            System.exit(1);
        }
    }
    
    private static String convertToJdbcUrl(String databaseUrl) {
        if (databaseUrl.startsWith("jdbc:")) {
            return databaseUrl.replaceAll("&?channel_binding=require", "");
        }
        
        if (databaseUrl.startsWith("postgresql://")) {
            String afterProtocol = databaseUrl.substring("postgresql://".length());
            int atIndex = afterProtocol.indexOf("@");
            
            if (atIndex > 0) {
                String hostAndDb = afterProtocol.substring(atIndex + 1);
                String jdbcUrl = "jdbc:postgresql://" + hostAndDb;
                jdbcUrl = jdbcUrl.replaceAll("&?channel_binding=require", "");
                return jdbcUrl;
            }
        }
        
        throw new IllegalArgumentException("Invalid DATABASE_URL format: " + databaseUrl);
    }
    
    private static String extractUsername(String databaseUrl) {
        if (databaseUrl.startsWith("postgresql://")) {
            String afterProtocol = databaseUrl.substring("postgresql://".length());
            int atIndex = afterProtocol.indexOf("@");
            
            if (atIndex > 0) {
                String userPass = afterProtocol.substring(0, atIndex);
                int colonIndex = userPass.indexOf(":");
                if (colonIndex > 0) {
                    return userPass.substring(0, colonIndex);
                }
            }
        }
        
        return null;
    }
    
    private static String extractPassword(String databaseUrl) {
        if (databaseUrl.startsWith("postgresql://")) {
            String afterProtocol = databaseUrl.substring("postgresql://".length());
            int atIndex = afterProtocol.indexOf("@");
            
            if (atIndex > 0) {
                String userPass = afterProtocol.substring(0, atIndex);
                int colonIndex = userPass.indexOf(":");
                if (colonIndex > 0) {
                    return userPass.substring(colonIndex + 1);
                }
            }
        }
        
        return null;
    }
}

