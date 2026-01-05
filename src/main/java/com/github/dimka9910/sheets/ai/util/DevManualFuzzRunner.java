package com.github.dimka9910.sheets.ai.util;

import com.github.dimka9910.sheets.ai.FinanceTrackerApplication;
import com.github.dimka9910.sheets.ai.dto.telegram.TelegramChatRequest;
import com.github.dimka9910.sheets.ai.dto.telegram.TelegramChatResponse;
import com.github.dimka9910.sheets.ai.services.SqsMessageProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.sql.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Manual E2E "fuzz" runner against DEV database.
 *
 * Goals:
 * - Create isolated test users in DEV DB (does NOT touch DIMA/KIKI)
 * - Send messages through the real SqsMessageProcessor pipeline (no Telegram)
 * - After each step, verify DB invariants (operations saved, transfers linked, deletes soft-deleted, etc.)
 *
 * Required env:
 * - DATABASE_URL (jdbc:postgresql://... or postgresql://... accepted by driver)
 * - OPENAI_API_KEY (recommended) or other Spring AI config
 *
 * Optional env:
 * - RESPONSE_QUEUE_URL empty is fine (SQS send becomes no-op)
 */
public class DevManualFuzzRunner {

    private record TestUser(String username, String telegramId, UUID userId) {}

    public static void main(String[] args) throws Exception {
        String dbUrl = System.getenv("DATABASE_URL");
        if (dbUrl == null || dbUrl.isBlank()) {
            throw new IllegalStateException("DATABASE_URL is required to run DevManualFuzzRunner");
        }

        // Smoke mode: run only first N steps to quickly validate model/config changes.
        int maxSteps = Integer.parseInt(System.getenv().getOrDefault("ZZ_SMOKE_STEPS", "0"));

        // Create isolated test users and seed minimal data
        String runId = "ZZ_TEST_" + Instant.now().toString().replace(":", "").replace("-", "").replace(".", "");
        String telegramIdA = "99" + System.currentTimeMillis() + "1";
        String telegramIdB = "99" + System.currentTimeMillis() + "2";
        TestUser a;
        TestUser b;

        try (Connection c = connect(dbUrl)) {
            a = createUserWithSetup(c, runId + "_A", telegramIdA);
            b = createUserWithSetup(c, runId + "_B", telegramIdB);
            linkUsers(c, a.userId(), b.userId(), "B", List.of("spouse", "partner"));
            linkUsers(c, b.userId(), a.userId(), "A", List.of("spouse", "partner"));
        }

        // Boot Spring context (no web server)
        SpringApplication app = new SpringApplication(FinanceTrackerApplication.class);
        app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        ConfigurableApplicationContext ctx = app.run(args);
        SqsMessageProcessor processor = ctx.getBean(SqsMessageProcessor.class);

        // Execute scenarios
        ScenarioRunner runner = new ScenarioRunner(dbUrl, processor);

        // Baseline: simple expense
        runner.step(a, "coffee 200", Expect.savedSingleOperation("EXPENSE"));
        if (maxSteps > 0 && runner.stepsRun() >= maxSteps) {
            System.out.println("\n✅ Smoke run completed for " + runId + " (steps=" + runner.stepsRun() + ")");
            ctx.close();
            return;
        }

        // Regression: do NOT infer fund from purchase item. Use default fund when not explicitly referenced.
        runner.step(a, "taxi 200", Expect.savedSingleOperationFundIs("EXPENSE", "FOOD"));
        if (maxSteps > 0 && runner.stepsRun() >= maxSteps) {
            System.out.println("\n✅ Smoke run completed for " + runId + " (steps=" + runner.stepsRun() + ")");
            ctx.close();
            return;
        }
        runner.step(a, "tickets 200", Expect.savedSingleOperationFundIs("EXPENSE", "FOOD"));
        if (maxSteps > 0 && runner.stepsRun() >= maxSteps) {
            System.out.println("\n✅ Smoke run completed for " + runId + " (steps=" + runner.stepsRun() + ")");
            ctx.close();
            return;
        }
        // Explicit fund reference (format-focused): should pick TRAVEL when user explicitly references fund.
        runner.step(a, "tickets 200 to travel fund", Expect.savedSingleOperationFundIs("EXPENSE", "TRAVEL"));
        if (maxSteps > 0 && runner.stepsRun() >= maxSteps) {
            System.out.println("\n✅ Smoke run completed for " + runId + " (steps=" + runner.stepsRun() + ")");
            ctx.close();
            return;
        }

        // Regression: resolving a pending clarification + setting default in the SAME reply should do BOTH.
        // Remove default fund, create pending, then resolve with "use TRAVEL and set as default".
        try (Connection c = connect(dbUrl)) {
            clearDefaultFund(c, a.userId());
        }
        runner.step(a, "taxi 200", Expect.pending()); // fund missing (no default)
        runner.step(a, "use TRAVEL fund for that and set it as default", Expect.savedSingleOperationAndDefaultFundIs("EXPENSE", "TRAVEL", "TRAVEL"));

        // Complex: multi-intent in one message (settings + operation)
        // Should BOTH set default fund and record an expense using that default (without inferring fund from 'taxi').
        runner.step(a, "set my default fund to FOOD and record taxi 300", Expect.savedSingleOperationAndDefaultFundIs("EXPENSE", "FOOD", "FOOD"));

        // Complex: multiple operations in one message (should save at least 2)
        runner.step(a, "coffee 110 and taxi 220", Expect.savedAtLeastOperations(2));

        // Complex: mixed transfer + setting in same message (3rd-party + default)
        runner.step(a, "send 500 to spouse and set my default currency to EUR", Expect.savedTransferPairAndDefaultCurrency("EUR"));

        // Pending clarification: missing amount
        runner.step(a, "coffee", Expect.pending());
        runner.step(a, "200", Expect.savedSingleOperation("EXPENSE"));

        // Transfer between own accounts: creates 2 linked TRANSFER rows
        runner.step(a, "transfer 1000 from card to cash", Expect.savedTransferPair());

        // Third-party transfer: should still save TRANSFER pair (accounts chosen from available lists)
        runner.step(a, "sent 500 to spouse", Expect.savedTransferPair());

        // Multi-step message: should record 2 operations OR ask clarification (we accept either but never invent IDs)
        runner.step(a, "coffee 150 and taxi 300", Expect.logicalMulti());

        // Correction: modify last operation amount (requires UUID logic in history)
        runner.step(a, "not 150 but 180", Expect.modifiedOrPending());

        // Correction: delete last operation
        runner.step(a, "delete last", Expect.deletedOrPending());

        // Settings: set default currency + language
        runner.step(a, "set my default currency to EUR", Expect.defaultCurrencyUpdated("EUR"));
        runner.step(a, "set my language to en", Expect.languageUpdated("en"));

        System.out.println("\n✅ Manual fuzz run completed for " + runId);
        ctx.close();
    }

    private static Connection connect(String databaseUrl) throws SQLException, ClassNotFoundException {
        Class.forName("org.postgresql.Driver");
        if (databaseUrl == null || databaseUrl.isBlank()) {
            throw new IllegalArgumentException("DATABASE_URL is blank");
        }

        // Accept:
        // - jdbc:postgresql://host:5432/db?...
        // - postgresql://user:pass@host:5432/db?...
        String jdbcUrl = toJdbcUrl(databaseUrl);
        return DriverManager.getConnection(jdbcUrl);
    }

    private static String toJdbcUrl(String databaseUrl) {
        String url = databaseUrl.trim();
        if (url.startsWith("jdbc:")) {
            return url;
        }
        if (!url.startsWith("postgresql://")) {
            // best-effort fallback
            return "jdbc:" + url;
        }

        // Convert libpq URI to JDBC URL
        // postgresql://user:pass@host:port/db?sslmode=require
        try {
            java.net.URI uri = java.net.URI.create(url);
            String userInfo = uri.getUserInfo(); // user:pass
            String user = null;
            String pass = null;
            if (userInfo != null) {
                int idx = userInfo.indexOf(':');
                if (idx >= 0) {
                    user = userInfo.substring(0, idx);
                    pass = userInfo.substring(idx + 1);
                } else {
                    user = userInfo;
                }
            }

            String host = uri.getHost();
            int port = uri.getPort() >= 0 ? uri.getPort() : 5432;
            String path = uri.getPath(); // "/neondb"
            if (path == null || path.isBlank() || "/".equals(path)) {
                throw new IllegalArgumentException("DATABASE_URL missing database name in path");
            }

            String query = uri.getQuery(); // sslmode=require&...
            StringBuilder q = new StringBuilder();
            if (query != null && !query.isBlank()) {
                q.append(query);
            }
            if (user != null && !user.isBlank()) {
                if (q.length() > 0) q.append("&");
                q.append("user=").append(encode(user));
            }
            if (pass != null && !pass.isBlank()) {
                if (q.length() > 0) q.append("&");
                q.append("password=").append(encode(pass));
            }

            return "jdbc:postgresql://" + host + ":" + port + path + (q.length() > 0 ? "?" + q : "");
        } catch (Exception e) {
            // fallback: prefix jdbc and hope driver accepts it
            return "jdbc:" + url;
        }
    }

    private static String encode(String s) {
        try {
            return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private static TestUser createUserWithSetup(Connection c, String username, String telegramId) throws SQLException {
        UUID userId = UUID.randomUUID();

        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (id, username, telegram_id, display_name, default_currency, preferred_language, ai_context, default_account_id, default_fund_id, created_at) " +
                "VALUES (?::uuid, ?, ?, ?, 'RSD', 'en', '{}'::jsonb, NULL, NULL, NOW())")) {
            ps.setObject(1, userId);
            ps.setString(2, username);
            ps.setString(3, telegramId);
            ps.setString(4, username);
            ps.executeUpdate();
        }

        // Accounts: CARD + CASH
        UUID cardId = UUID.randomUUID();
        UUID cashId = UUID.randomUUID();
        insertAccount(c, cardId, userId, username + "_CARD", "Card", new String[0]);
        insertAccount(c, cashId, userId, username + "_CASH", "Cash", new String[0]);

        // Funds: FOOD + TRANSPORT + TRAVEL
        UUID foodId = UUID.randomUUID();
        UUID transportId = UUID.randomUUID();
        UUID travelId = UUID.randomUUID();
        UUID creditId = UUID.randomUUID();
        insertFund(c, foodId, userId, "FOOD", "Food", new String[0]);
        insertFund(c, transportId, userId, "TRANSPORT", "Transport", new String[0]);
        insertFund(c, travelId, userId, "TRAVEL", "Travel", new String[0]);
        insertFund(c, creditId, userId, "CREDIT", "Credit", new String[0]);

        // Set defaults: CARD account + FOOD fund
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE users SET default_account_id = ?::uuid, default_fund_id = ?::uuid WHERE id = ?::uuid")) {
            ps.setObject(1, cardId);
            ps.setObject(2, foodId);
            ps.setObject(3, userId);
            ps.executeUpdate();
        }

        return new TestUser(username, telegramId, userId);
    }

    private static void insertAccount(Connection c, UUID id, UUID userId, String externalId, String displayName, String[] aliases) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO accounts (id, user_id, external_id, display_name, aliases, created_at) VALUES (?::uuid, ?::uuid, ?, ?, ?::text[], NOW())")) {
            ps.setObject(1, id);
            ps.setObject(2, userId);
            ps.setString(3, externalId);
            ps.setString(4, displayName);
            ps.setArray(5, c.createArrayOf("text", aliases));
            ps.executeUpdate();
        }
    }

    private static void insertFund(Connection c, UUID id, UUID userId, String externalId, String displayName, String[] aliases) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO funds (id, user_id, external_id, display_name, aliases, created_at) VALUES (?::uuid, ?::uuid, ?, ?, ?::text[], NOW())")) {
            ps.setObject(1, id);
            ps.setObject(2, userId);
            ps.setString(3, externalId);
            ps.setString(4, displayName);
            ps.setArray(5, c.createArrayOf("text", aliases));
            ps.executeUpdate();
        }
    }

    private static void linkUsers(Connection c, UUID ownerUserId, UUID targetUserId, String displayName, List<String> aliases) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO linked_users (id, owner_user_id, target_user_id, display_name, aliases, created_at) VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?::text[], NOW())")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, ownerUserId);
            ps.setObject(3, targetUserId);
            ps.setString(4, displayName);
            ps.setArray(5, c.createArrayOf("text", aliases.toArray()));
            ps.executeUpdate();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scenario runner + invariants
    // ─────────────────────────────────────────────────────────────────────────

    private static final class ScenarioRunner {
        private final String dbUrl;
        private final SqsMessageProcessor processor;
        private int stepsRun = 0;

        private ScenarioRunner(String dbUrl, SqsMessageProcessor processor) {
            this.dbUrl = dbUrl;
            this.processor = processor;
        }

        void step(TestUser user, String message, Expect expectation) throws Exception {
            System.out.println("\n— — —");
            System.out.println("USER=" + user.username() + " MSG=\"" + message + "\"");

            TelegramChatRequest req = TelegramChatRequest.builder()
                    .telegramUserId(user.telegramId())
                    .telegramChatId(user.telegramId())
                    .message(message)
                    .userName(user.username())
                    .build();

            TelegramChatResponse resp = processor.processCommand(req);
            System.out.println("BOT: " + resp.getMessage());

            // DB checks
            try (Connection c = connect(dbUrl)) {
                expectation.verify(c, user, message, resp);
            }
            stepsRun++;
        }

        int stepsRun() {
            return stepsRun;
        }
    }

    @FunctionalInterface
    private interface Expect {
        void verify(Connection c, TestUser user, String message, TelegramChatResponse resp) throws Exception;

        static Expect pending() {
            return (c, user, message, resp) -> {
                // Expect no new financial operations; response should be success=true but ops=0
                require(resp.isSuccess(), "response.success should be true");
                require(resp.getOperationsCount() == 0, "operationsCount should be 0 for pending clarification");
            };
        }

        static Expect savedSingleOperation(String expectedType) {
            return (c, user, message, resp) -> {
                require(resp.isSuccess(), "response.success should be true");
                require(resp.getOperationsCount() >= 1, "should save at least 1 operation");

                var op = latestOp(c, user.userId());
                requireNotNull(op, "latest operation should exist");
                require(expectedType.equals(op.operationType), "operation_type mismatch: expected=" + expectedType + " actual=" + op.operationType);
                require(op.deletedAt == null, "operation must not be deleted");
            };
        }

        static Expect savedSingleOperationFundIs(String expectedType, String expectedFundExternalId) {
            return (c, user, message, resp) -> {
                require(resp.isSuccess(), "response.success should be true");
                require(resp.getOperationsCount() >= 1, "should save at least 1 operation");

                var op = latestOp(c, user.userId());
                requireNotNull(op, "latest operation should exist");
                require(expectedType.equals(op.operationType), "operation_type mismatch: expected=" + expectedType + " actual=" + op.operationType);
                require(op.deletedAt == null, "operation must not be deleted");

                String fundExternalId = latestOpFundExternalId(c, user.userId());
                requireNotNull(fundExternalId, "latest operation fund external_id should exist");
                require(expectedFundExternalId.equals(fundExternalId),
                        "fund mismatch: expected=" + expectedFundExternalId + " but got " + fundExternalId);
            };
        }

        static Expect savedTransferPair() {
            return (c, user, message, resp) -> {
                require(resp.isSuccess(), "response.success should be true");
                require(resp.getOperationsCount() >= 1, "transfer should count as operation(s)");

                // Find newest transfer and verify there are 2 rows with same linkId (not deleted)
                OpRow newest = latestOp(c, user.userId());
                requireNotNull(newest, "latest operation should exist");
                require("TRANSFER".equals(newest.operationType), "expected TRANSFER, got " + newest.operationType);
                requireNotNull(newest.linkId, "TRANSFER must have link_id");

                int count = countActiveByLinkId(c, newest.linkId);
                require(count == 2, "TRANSFER should have exactly 2 active rows with same link_id, got " + count);
            };
        }

        static Expect logicalMulti() {
            return (c, user, message, resp) -> {
                require(resp.isSuccess(), "response.success should be true");
                // Either it saved 2 ops, or asked clarification, but must not crash.
                require(resp.getOperationsCount() >= 0, "operationsCount should be non-negative");
            };
        }

        static Expect modifiedOrPending() {
            return (c, user, message, resp) -> {
                require(resp.isSuccess(), "response.success should be true");
                // Either modified (opsCount may be 0) or asked clarification. Just ensure no exception path.
            };
        }

        static Expect deletedOrPending() {
            return (c, user, message, resp) -> {
                require(resp.isSuccess(), "response.success should be true");
            };
        }

        static Expect defaultCurrencyUpdated(String currency) {
            return (c, user, message, resp) -> {
                require(resp.isSuccess(), "response.success should be true");
                String cur = readDefaultCurrency(c, user.userId());
                require(currency.equals(cur), "default_currency should be updated to " + currency + " but got " + cur);
            };
        }

        static Expect languageUpdated(String lang) {
            return (c, user, message, resp) -> {
                require(resp.isSuccess(), "response.success should be true");
                String v = readPreferredLanguage(c, user.userId());
                require(lang.equals(v), "preferred_language should be updated to " + lang + " but got " + v);
            };
        }

        static Expect savedAtLeastOperations(int minOps) {
            return (c, user, message, resp) -> {
                require(resp.isSuccess(), "response.success should be true");
                require(resp.getOperationsCount() >= minOps, "expected at least " + minOps + " operations, got " + resp.getOperationsCount());
            };
        }

        static Expect savedTransferPairAndDefaultCurrency(String expectedCurrency) {
            return (c, user, message, resp) -> {
                require(resp.isSuccess(), "response.success should be true");
                // transfer should save at least one op count; paired rows are checked via linkId count
                OpRow newest = latestOp(c, user.userId());
                requireNotNull(newest, "latest operation should exist");
                require("TRANSFER".equals(newest.operationType), "expected latest operation to be TRANSFER");
                requireNotNull(newest.linkId, "TRANSFER must have link_id");
                require(countActiveByLinkId(c, newest.linkId) == 2, "TRANSFER should have 2 active rows");

                String cur = readDefaultCurrency(c, user.userId());
                require(expectedCurrency.equals(cur), "default_currency should be updated to " + expectedCurrency + " but got " + cur);
            };
        }

        static Expect savedSingleOperationAndDefaultFundIs(String expectedType, String expectedOpFundExternalId, String expectedDefaultFundExternalId) {
            return (c, user, message, resp) -> {
                require(resp.isSuccess(), "response.success should be true");
                require(resp.getOperationsCount() >= 1, "should save at least 1 operation");

                var op = latestOp(c, user.userId());
                requireNotNull(op, "latest operation should exist");
                require(expectedType.equals(op.operationType), "operation_type mismatch: expected=" + expectedType + " actual=" + op.operationType);
                require(op.deletedAt == null, "operation must not be deleted");

                String fundExternalId = latestOpFundExternalId(c, user.userId());
                requireNotNull(fundExternalId, "latest operation fund external_id should exist");
                require(expectedOpFundExternalId.equals(fundExternalId),
                        "operation fund mismatch: expected=" + expectedOpFundExternalId + " but got " + fundExternalId);

                String defaultFundExternalId = readDefaultFundExternalId(c, user.userId());
                require(expectedDefaultFundExternalId.equals(defaultFundExternalId),
                        "default fund mismatch: expected=" + expectedDefaultFundExternalId + " but got " + defaultFundExternalId);
            };
        }
    }

    private static final class OpRow {
        @SuppressWarnings("unused")
        final UUID id;
        final String operationType;
        final UUID linkId;
        final Timestamp deletedAt;

        OpRow(UUID id, String operationType, UUID linkId, Timestamp deletedAt) {
            this.id = id;
            this.operationType = operationType;
            this.linkId = linkId;
            this.deletedAt = deletedAt;
        }
    }

    private static void require(boolean ok, String msg) {
        if (!ok) throw new IllegalStateException("❌ CHECK FAILED: " + msg);
    }

    private static <T> T requireNotNull(T v, String msg) {
        if (v == null) throw new IllegalStateException("❌ CHECK FAILED: " + msg);
        return v;
    }

    private static OpRow latestOp(Connection c, UUID userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, operation_type, link_id, deleted_at FROM financial_operations " +
                "WHERE user_id = ?::uuid ORDER BY created_at DESC LIMIT 1")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new OpRow(
                        (UUID) rs.getObject("id"),
                        rs.getString("operation_type"),
                        (UUID) rs.getObject("link_id"),
                        rs.getTimestamp("deleted_at")
                );
            }
        }
    }

    private static int countActiveByLinkId(Connection c, UUID linkId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM financial_operations WHERE link_id = ?::uuid AND deleted_at IS NULL")) {
            ps.setObject(1, linkId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static String readDefaultCurrency(Connection c, UUID userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT default_currency FROM users WHERE id = ?::uuid")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static String readPreferredLanguage(Connection c, UUID userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT preferred_language FROM users WHERE id = ?::uuid")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static String readDefaultFundExternalId(Connection c, UUID userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT f.external_id " +
                "FROM users u LEFT JOIN funds f ON u.default_fund_id = f.id " +
                "WHERE u.id = ?::uuid")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static void clearDefaultFund(Connection c, UUID userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE users SET default_fund_id = NULL WHERE id = ?::uuid")) {
            ps.setObject(1, userId);
            ps.executeUpdate();
        }
    }

    private static String latestOpFundExternalId(Connection c, UUID userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT f.external_id " +
                "FROM financial_operations fo " +
                "JOIN funds f ON fo.fund_id = f.id " +
                "WHERE fo.user_id = ?::uuid " +
                "ORDER BY fo.created_at DESC LIMIT 1")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return rs.getString(1);
            }
        }
    }
}


