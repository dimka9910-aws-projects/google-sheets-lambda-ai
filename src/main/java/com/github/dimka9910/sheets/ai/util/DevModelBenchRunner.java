package com.github.dimka9910.sheets.ai.util;

import com.github.dimka9910.sheets.ai.FinanceTrackerApplication;
import com.github.dimka9910.sheets.ai.dto.response.FinancialAction;
import com.github.dimka9910.sheets.ai.dto.response.FinancialAction.OperationType;
import com.github.dimka9910.sheets.ai.dto.user.AccountEntry;
import com.github.dimka9910.sheets.ai.dto.user.FundEntry;
import com.github.dimka9910.sheets.ai.dto.user.UserEntity;
import com.github.dimka9910.sheets.ai.services.agents.FinancialAgent;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local benchmarking utility for selecting best model/effort for FinancialAgent parsing.
 *
 * It does NOT persist operations; it calls FinancialAgent and prints a report:
 * - latency (ms)
 * - prompt/completion/total tokens (+ cached/reasoning tokens if available)
 * - estimated cost based on provided pricing table
 * - outcome classification (OK / PENDING / WRONG)
 *
 * Required env:
 * - OPENAI_API_KEY (from Spring AI config)
 *
 * Optional env:
 * - ZZ_BENCH_REPEATS (default 1)
 * - ZZ_BENCH_MAX_OUT (default 600)
 */
public class DevModelBenchRunner {

    private record Price(double inputUsdPer1M, double outputUsdPer1M) {}

    public static void main(String[] args) {
        int repeats = parseIntEnv("ZZ_BENCH_REPEATS", 1);
        int maxOut = parseIntEnv("ZZ_BENCH_MAX_OUT", 600);

        // Pricing table as provided by user (USD per 1M tokens)
        Map<String, Price> prices = new LinkedHashMap<>();
        prices.put("gpt-5.2", new Price(1.75, 14.00));
        prices.put("gpt-5.1", new Price(1.25, 10.00));
        prices.put("gpt-5", new Price(1.25, 10.00));
        prices.put("gpt-5-mini", new Price(0.25, 2.00));
        prices.put("gpt-5-nano", new Price(0.05, 0.40));
        prices.put("gpt-5.2-chat-latest", new Price(1.75, 14.00));
        prices.put("gpt-5.1-chat-latest", new Price(1.25, 10.00));
        prices.put("gpt-5-chat-latest", new Price(1.25, 10.00));

        List<String> efforts = List.of("low", "medium", "high");

        // Expected target behavior for the test message:
        // - Prefer saving 200 RSD to account CARD_DIMA_YETTEL and default fund FAMILY_MONTHLY_BUDGET if defaultAccount is set.
        // - Otherwise ask clarification about account (PENDING).
        String msg = "200 динар етел топливо";

        UserEntity ctxWithDefaultAccount = buildDimaLikeContext(true);
        UserEntity ctxWithoutDefaultAccount = buildDimaLikeContext(false);

        SpringApplication app = new SpringApplication(FinanceTrackerApplication.class);
        app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        ConfigurableApplicationContext ctx = app.run(args);
        FinancialAgent agent = ctx.getBean(FinancialAgent.class);

        System.out.println("== DevModelBenchRunner ==");
        System.out.println("message: \"" + msg + "\"");
        System.out.println("repeats=" + repeats + ", maxOut=" + maxOut);
        System.out.println("");

        benchScenario("WITH defaultAccount=CARD_DIMA_YETTEL", agent, msg, ctxWithDefaultAccount, prices, efforts, repeats, maxOut);
        System.out.println("");
        benchScenario("WITHOUT defaultAccount (expect PENDING about account)", agent, msg, ctxWithoutDefaultAccount, prices, efforts, repeats, maxOut);

        ctx.close();
    }

    private static void benchScenario(String title,
                                      FinancialAgent agent,
                                      String msg,
                                      UserEntity userCtx,
                                      Map<String, Price> prices,
                                      List<String> efforts,
                                      int repeats,
                                      int maxOut) {
        System.out.println("### " + title);
        System.out.println("model,effort,llm_ms,prompt_toks,completion_toks,total_toks,cached_toks,reasoning_toks,est_cost_usd,outcome,account,fund,currency,message_snip");

        for (String model : prices.keySet()) {
            for (String effort : efforts) {
                long sumMs = 0;
                long sumPrompt = 0;
                long sumCompletion = 0;
                long sumTotal = 0;
                long sumCached = 0;
                long sumReasoning = 0;
                int nUsage = 0;
                String lastOutcome = "";
                String lastAccount = "";
                String lastFund = "";
                String lastCurrency = "";
                String lastMsg = "";

                for (int i = 0; i < repeats; i++) {
                    FinancialAgent.BenchRunResult r = agent.processBench(msg, userCtx, false, model, effort, maxOut);
                    var m = r.metrics();
                    sumMs += m.llmMs();
                    if (m.promptTokens() != null) { sumPrompt += m.promptTokens(); nUsage++; }
                    if (m.completionTokens() != null) sumCompletion += m.completionTokens();
                    if (m.totalTokens() != null) sumTotal += m.totalTokens();
                    if (m.cachedTokens() != null) sumCached += m.cachedTokens();
                    if (m.reasoningTokens() != null) sumReasoning += m.reasoningTokens();

                    Outcome o = classify(r.response(), userCtx);
                    lastOutcome = o.label;
                    lastAccount = o.account;
                    lastFund = o.fund;
                    lastCurrency = o.currency;
                    lastMsg = snip(r.response() != null ? r.response().getMessage() : null, 80);
                }

                long avgMs = repeats > 0 ? (sumMs / repeats) : 0;
                long avgPrompt = repeats > 0 ? (sumPrompt / repeats) : 0;
                long avgCompletion = repeats > 0 ? (sumCompletion / repeats) : 0;
                long avgTotal = repeats > 0 ? (sumTotal / repeats) : 0;
                long avgCached = repeats > 0 ? (sumCached / repeats) : 0;
                long avgReasoning = repeats > 0 ? (sumReasoning / repeats) : 0;

                Price p = prices.get(model);
                double estCost = estimateCostUsd(avgPrompt, avgCompletion, p);

                System.out.println(csv(model, effort,
                        String.valueOf(avgMs),
                        tok(avgPrompt, nUsage),
                        tok(avgCompletion, nUsage),
                        tok(avgTotal, nUsage),
                        tok(avgCached, nUsage),
                        tok(avgReasoning, nUsage),
                        String.format("%.6f", estCost),
                        lastOutcome, lastAccount, lastFund, lastCurrency, lastMsg));
            }
        }
    }

    private static String tok(long v, int nUsage) {
        // If usage wasn't reported (nUsage==0), show empty.
        return nUsage == 0 ? "" : String.valueOf(v);
    }

    private static double estimateCostUsd(long promptTokens, long completionTokens, Price p) {
        // USD per 1M tokens
        double inCost = (promptTokens / 1_000_000.0) * p.inputUsdPer1M;
        double outCost = (completionTokens / 1_000_000.0) * p.outputUsdPer1M;
        return inCost + outCost;
    }

    private static Outcome classify(com.github.dimka9910.sheets.ai.dto.response.FinancialAgentResponse resp, UserEntity userCtx) {
        if (resp == null) return new Outcome("ERROR", "", "", "", "");

        boolean pending = resp.getPendingClarifications() != null && !resp.getPendingClarifications().isEmpty();
        List<FinancialAction> actions = resp.getFinancialActions() != null ? resp.getFinancialActions() : List.of();

        if (pending) {
            return new Outcome("PENDING", "", "", "", resp.getMessage());
        }

        if (actions.isEmpty()) {
            return new Outcome("EMPTY", "", "", "", resp.getMessage());
        }

        FinancialAction a = actions.get(0);
        if (a == null || a.getOperationType() != OperationType.EXPENSE) {
            return new Outcome("WRONG_TYPE", safe(a != null ? String.valueOf(a.getOperationType()) : null), "", "", resp.getMessage());
        }

        String account = safe(a.getAccount());
        String fund = safe(a.getFund());
        String currency = safe(a.getCurrency());

        // Expected fund: user's default fund
        String expectedFund = userCtx.getDefaultFund() != null ? userCtx.getDefaultFund().getFundId() : "";
        String expectedAccount = userCtx.getDefaultAccount() != null ? userCtx.getDefaultAccount().getAccountId() : "";

        boolean okFund = !expectedFund.isBlank() && expectedFund.equals(fund);
        boolean okAccount = !expectedAccount.isBlank() && expectedAccount.equals(account);
        boolean okAmount = a.getAmount() != null && Math.abs(a.getAmount() - 200.0) < 0.0001;
        boolean okCurrency = "RSD".equals(currency) || currency.isBlank(); // allow blank as "default" in some responses

        if (userCtx.getDefaultAccount() == null) {
            // If no default account, an EXPENSE is not acceptable; we expect PENDING.
            return new Outcome("WRONG_SHOULD_PENDING", account, fund, currency, resp.getMessage());
        }

        if (okFund && okAccount && okAmount && okCurrency) {
            return new Outcome("OK", account, fund, currency, resp.getMessage());
        }

        return new Outcome("WRONG_FIELDS", account, fund, currency, resp.getMessage());
    }

    private static UserEntity buildDimaLikeContext(boolean withDefaultAccount) {
        AccountEntry card = AccountEntry.builder()
                .accountId("CARD_DIMA_YETTEL")
                .displayName("Yettel Card")
                .aliases(List.of("yettel", "etell", "ettel", "yetel"))
                .build();
        AccountEntry cash = AccountEntry.builder()
                .accountId("CASH_DIMA")
                .displayName("Cash")
                .aliases(List.of("cash", "нал", "налик"))
                .build();

        FundEntry familyMonthly = FundEntry.builder()
                .fundId("FAMILY_MONTHLY_BUDGET")
                .displayName("Family Monthly Budget")
                .aliases(List.of("family", "monthly", "budget"))
                .build();
        FundEntry dimaMonthly = FundEntry.builder()
                .fundId("DIMA_MONTHLY_BUDGET")
                .displayName("Monthly Budget")
                .aliases(List.of("monthly budget"))
                .build();
        FundEntry food = FundEntry.builder().fundId("FOOD").displayName("Food").build();
        FundEntry travel = FundEntry.builder().fundId("TRAVEL").displayName("Travel").build();
        FundEntry credit = FundEntry.builder().fundId("CREDIT").displayName("Credit").build();

        return UserEntity.builder()
                .userName("ZZ_DIMA_LIKE")
                .displayName("Dima")
                .preferredLanguage("ru")
                .defaultCurrency("RSD")
                .defaultFund(familyMonthly)
                .defaultAccount(withDefaultAccount ? card : null)
                .accounts(List.of(card, cash))
                .funds(List.of(familyMonthly, dimaMonthly, food, travel, credit))
                .customInstructions(List.of())
                .conversationHistory(List.of())
                .pendingActions(List.of())
                .build();
    }

    private static int parseIntEnv(String key, int def) {
        try {
            String v = System.getenv(key);
            if (v == null || v.isBlank()) return def;
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String snip(String s, int max) {
        if (s == null) return "";
        String x = s.replace("\n", " ").replace("\r", " ");
        return x.length() <= max ? x : x.substring(0, max) + "...";
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String csv(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(parts[i] == null ? "" : parts[i].replace("\"", "\"\"")).append("\"");
        }
        return sb.toString();
    }

    private record Outcome(String label, String account, String fund, String currency, String message) {}
}


