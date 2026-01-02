package com.github.dimka9910.sheets.ai.util;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Utility to execute SQL script for DEV database setup.
 * Usage: java -cp ... DevDatabaseSetup
 */
@Slf4j
public class DevDatabaseSetup {

    private static final String DATABASE_URL = System.getenv("DATABASE_URL");
    
    public static void main(String[] args) {
        if (DATABASE_URL == null || DATABASE_URL.isBlank()) {
            log.error("❌ DATABASE_URL environment variable is not set");
            System.exit(1);
        }
        
        log.info("🔧 DEV Database Setup");
        log.info("═══════════════════════════════════════════════════════════════════════════");
        log.info("⚠️  WARNING: This will DELETE all data for DIMA and KIKI users!");
        log.info("   - All accounts, funds, chat history, financial operations");
        log.info("   - All linked user relationships");
        log.info("   - All custom instructions and AI context");
        log.info("");
        
        try {
            // Parse DATABASE_URL
            String jdbcUrl = convertToJdbcUrl(DATABASE_URL);
            String username = extractUsername(DATABASE_URL);
            String password = extractPassword(DATABASE_URL);
            
            log.info("📊 Connecting to database...");
            log.info("   URL: {}", jdbcUrl.replaceAll("password=[^&@]*", "password=***"));
            
            // Load PostgreSQL driver
            try {
                Class.forName("org.postgresql.Driver");
            } catch (ClassNotFoundException e) {
                log.error("❌ PostgreSQL driver not found. Make sure postgresql dependency is in classpath.");
                System.exit(1);
            }
            
            // Load SQL script
            InputStream sqlStream = DevDatabaseSetup.class
                .getResourceAsStream("/db/dev-cleanup-and-setup.sql");
            
            if (sqlStream == null) {
                log.error("❌ SQL script not found: /db/dev-cleanup-and-setup.sql");
                System.exit(1);
            }
            
            // Read and parse SQL statements
            List<String> statements = parseSqlScript(sqlStream);
            log.info("📝 Found {} SQL statements to execute", statements.size());
            
            // Execute statements
            try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
                conn.setAutoCommit(false);
                
                try (Statement stmt = conn.createStatement()) {
                    for (int i = 0; i < statements.size(); i++) {
                        String sql = statements.get(i);
                        if (sql.trim().isEmpty() || sql.trim().startsWith("--")) {
                            continue;
                        }
                        
                        log.info("Executing statement {} of {}...", i + 1, statements.size());
                        stmt.execute(sql);
                    }
                    
                    conn.commit();
                    log.info("✅ All statements executed successfully!");
                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                }
            }
            
            log.info("");
            log.info("✅ Done! Database cleaned and filled with fresh data.");
            log.info("📋 Summary:");
            log.info("   - DIMA: 5 accounts, 7 funds");
            log.info("   - KIKI: 3 accounts, 6 funds");
            log.info("   - Linked users: DIMA ↔ KIKI");
            log.info("   - All defaults: NULL (empty)");
            
        } catch (Exception e) {
            log.error("❌ Error executing SQL script", e);
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
                // Remove channel_binding parameter
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
    
    private static List<String> parseSqlScript(InputStream sqlStream) throws Exception {
        List<String> statements = new ArrayList<>();
        StringBuilder currentStatement = new StringBuilder();
        
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(sqlStream, StandardCharsets.UTF_8))) {
            
            String line;
            boolean inMultiLineComment = false;
            
            while ((line = reader.readLine()) != null) {
                // Skip comments
                if (line.trim().startsWith("--")) {
                    continue;
                }
                
                // Handle multi-line comments
                if (line.contains("/*")) {
                    inMultiLineComment = true;
                }
                if (line.contains("*/")) {
                    inMultiLineComment = false;
                    continue;
                }
                if (inMultiLineComment) {
                    continue;
                }
                
                // Append to current statement
                currentStatement.append(line).append("\n");
                
                // Check if statement ends with semicolon (outside of string)
                if (line.trim().endsWith(";")) {
                    String statement = currentStatement.toString().trim();
                    if (!statement.isEmpty() && !statement.equals(";")) {
                        statements.add(statement);
                    }
                    currentStatement.setLength(0);
                }
            }
            
            // Add remaining statement if any
            String remaining = currentStatement.toString().trim();
            if (!remaining.isEmpty() && !remaining.equals(";")) {
                statements.add(remaining);
            }
        }
        
        return statements;
    }
}

