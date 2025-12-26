package com.github.dimka9910.sheets.ai;

import com.github.dimka9910.sheets.ai.dto.telegram.TelegramChatRequest;
import com.github.dimka9910.sheets.ai.services.SqsMessageProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Advanced test runner for edge cases:
 * - Contextual references ("change it", "not that", "the last one")
 * - Corrections and modifications
 * - Deletions with context
 * - Multi-turn conversations
 * - Complex ambiguous requests
 */
public class AdvancedTestRunner {
    
    public static void main(String[] args) throws Exception {
        System.out.println("🚀 Starting ADVANCED Edge Case Test Runner...");
        
        SpringApplication app = new SpringApplication(FinanceTrackerApplication.class);
        app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        ConfigurableApplicationContext context = app.run(args);
        SqsMessageProcessor processor = context.getBean(SqsMessageProcessor.class);
        
        System.out.println("✅ Spring Boot context loaded");
        System.out.println("📝 Running ADVANCED edge case tests...\n");
        
        String userId = "377662506"; // DIMA
        
        // ═══════════════════════════════════════════════════════════════════════════
        // PHASE 1: Setup baseline operations (for context)
        // ═══════════════════════════════════════════════════════════════════════════
        
        section("PHASE 1: Setup Operations");
        test(processor, userId, "200 на кофе");
        sleep(3);
        test(processor, userId, "500 на такси");
        sleep(3);
        test(processor, userId, "перевел 1000 с карты в кеш");
        sleep(3);
        
        // ═══════════════════════════════════════════════════════════════════════════
        // PHASE 2: Contextual references
        // ═══════════════════════════════════════════════════════════════════════════
        
        section("PHASE 2: Contextual References");
        
        test(processor, userId, "измени это на 250");
        sleep(3);
        
        test(processor, userId, "не 250 а 300");
        sleep(3);
        
        test(processor, userId, "удали последнюю операцию");
        sleep(3);
        
        test(processor, userId, "верни обратно");
        sleep(3);
        
        // ═══════════════════════════════════════════════════════════════════════════
        // PHASE 3: Field-specific corrections
        // ═══════════════════════════════════════════════════════════════════════════
        
        section("PHASE 3: Field-Specific Corrections");
        
        test(processor, userId, "100 на обед");
        sleep(3);
        
        test(processor, userId, "нет, комментарий должен быть 'ресторан'");
        sleep(3);
        
        test(processor, userId, "не на еду, а на развлечения");
        sleep(3);
        
        test(processor, userId, "не с карты, а с кеша");
        sleep(3);
        
        // ═══════════════════════════════════════════════════════════════════════════
        // PHASE 4: Ambiguous references
        // ═══════════════════════════════════════════════════════════════════════════
        
        section("PHASE 4: Ambiguous References");
        
        test(processor, userId, "300 на продукты");
        sleep(3);
        test(processor, userId, "150 на кофе");
        sleep(3);
        
        test(processor, userId, "измени кофе на 200");
        sleep(3);
        
        test(processor, userId, "удали продукты");
        sleep(3);
        
        // ═══════════════════════════════════════════════════════════════════════════
        // PHASE 5: Multi-step corrections
        // ═══════════════════════════════════════════════════════════════════════════
        
        section("PHASE 5: Multi-Step Corrections");
        
        test(processor, userId, "потратил 500 на рестораны");
        sleep(3);
        
        test(processor, userId, "нет, сумма 600, комментарий 'бизнес ланч', категория развлечения");
        sleep(3);
        
        // ═══════════════════════════════════════════════════════════════════════════
        // PHASE 6: Complex context chains
        // ═══════════════════════════════════════════════════════════════════════════
        
        section("PHASE 6: Complex Context Chains");
        
        test(processor, userId, "120 на завтрак");
        sleep(3);
        test(processor, userId, "исправь на 150");
        sleep(3);
        test(processor, userId, "еще раз исправь на 180");
        sleep(3);
        test(processor, userId, "нет, верни как было");
        sleep(3);
        
        // ═══════════════════════════════════════════════════════════════════════════
        // PHASE 7: Pronoun references
        // ═══════════════════════════════════════════════════════════════════════════
        
        section("PHASE 7: Pronoun References");
        
        test(processor, userId, "дал KIKI 300");
        sleep(3);
        test(processor, userId, "нет, не 300 а 400");
        sleep(3);
        test(processor, userId, "она вернула мне эти деньги");
        sleep(3);
        
        // ═══════════════════════════════════════════════════════════════════════════
        // PHASE 8: Same/duplicate operations
        // ═══════════════════════════════════════════════════════════════════════════
        
        section("PHASE 8: Same/Duplicate Operations");
        
        test(processor, userId, "250 на кофе");
        sleep(3);
        test(processor, userId, "еще раз то же самое");
        sleep(3);
        test(processor, userId, "то же самое но на 50 больше");
        sleep(3);
        
        // ═══════════════════════════════════════════════════════════════════════════
        // PHASE 9: Negative corrections
        // ═══════════════════════════════════════════════════════════════════════════
        
        section("PHASE 9: Negative Corrections");
        
        test(processor, userId, "100 на кино");
        sleep(3);
        test(processor, userId, "нет, отмени");
        sleep(3);
        test(processor, userId, "на самом деле было 150");
        sleep(3);
        
        // ═══════════════════════════════════════════════════════════════════════════
        // PHASE 10: Date/time references
        // ═══════════════════════════════════════════════════════════════════════════
        
        section("PHASE 10: Date/Time References");
        
        test(processor, userId, "вчера потратил 200 на обед");
        sleep(3);
        test(processor, userId, "нет, это было позавчера");
        sleep(3);
        
        // ═══════════════════════════════════════════════════════════════════════════
        // PHASE 11: Edge cases with "last"
        // ═══════════════════════════════════════════════════════════════════════════
        
        section("PHASE 11: Edge Cases with 'Last'");
        
        test(processor, userId, "50 на воду");
        sleep(2);
        test(processor, userId, "80 на сок");
        sleep(2);
        test(processor, userId, "удали последнюю");
        sleep(3);
        test(processor, userId, "удали предпоследнюю");
        sleep(3);
        
        // ═══════════════════════════════════════════════════════════════════════════
        // PHASE 12: Incomplete/vague corrections
        // ═══════════════════════════════════════════════════════════════════════════
        
        section("PHASE 12: Vague Corrections");
        
        test(processor, userId, "130 на метро");
        sleep(3);
        test(processor, userId, "исправь");
        sleep(3);
        test(processor, userId, "измени сумму");
        sleep(3);
        
        // ═══════════════════════════════════════════════════════════════════════════
        System.out.println("\n" + "═".repeat(70));
        System.out.println("✅ All ADVANCED edge case tests completed!");
        System.out.println("📊 Check logs and database for results");
        System.out.println("💡 Review how MainAgent handled complex context");
        System.out.println("═".repeat(70));
        
        System.out.println("\n⏸️  Press Enter to exit...");
        System.in.read();
        
        context.close();
    }
    
    private static void section(String title) {
        System.out.println("\n" + "═".repeat(70));
        System.out.println(title);
        System.out.println("═".repeat(70));
    }
    
    private static void test(SqsMessageProcessor processor, String userId, String message) {
        System.out.println("📤 \"" + message + "\"");
        
        TelegramChatRequest request = TelegramChatRequest.builder()
                .telegramUserId(userId)
                .message(message)
                .build();
        
        try {
            processor.processCommand(request);
            System.out.println("   ✅ Processed\n");
        } catch (Exception e) {
            System.err.println("   ❌ Error: " + e.getMessage() + "\n");
        }
    }
    
    private static void sleep(int seconds) throws InterruptedException {
        Thread.sleep(seconds * 1000L);
    }
}

