package com.github.dimka9910.sheets.ai;

import com.github.dimka9910.sheets.ai.dto.telegram.TelegramChatRequest;
import com.github.dimka9910.sheets.ai.services.SqsMessageProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Focused test for MODIFY and DELETE operations.
 * Tests MainAgent → ExpenseEditAndDeletionAgent flow.
 */
public class ModifyDeleteTestRunner {
    
    public static void main(String[] args) throws Exception {
        System.out.println("🚀 Testing MODIFY/DELETE Flow...");
        
        SpringApplication app = new SpringApplication(FinanceTrackerApplication.class);
        app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        ConfigurableApplicationContext context = app.run(args);
        SqsMessageProcessor processor = context.getBean(SqsMessageProcessor.class);
        
        System.out.println("✅ Context loaded\n");
        
        String userId = "377662506";
        
        System.out.println("═══════════════════════════════════════════════════════════════════════════");
        System.out.println("TEST 1: Setup baseline");
        System.out.println("═══════════════════════════════════════════════════════════════════════════");
        test(processor, userId, "200 на кофе");
        sleep(3);
        
        System.out.println("\n═══════════════════════════════════════════════════════════════════════════");
        System.out.println("TEST 2: Simple amount modification");
        System.out.println("═══════════════════════════════════════════════════════════════════════════");
        test(processor, userId, "измени это на 250");
        sleep(4);
        
        System.out.println("\n═══════════════════════════════════════════════════════════════════════════");
        System.out.println("TEST 3: Another baseline");
        System.out.println("═══════════════════════════════════════════════════════════════════════════");
        test(processor, userId, "500 на такси");
        sleep(3);
        
        System.out.println("\n═══════════════════════════════════════════════════════════════════════════");
        System.out.println("TEST 4: Delete last");
        System.out.println("═══════════════════════════════════════════════════════════════════════════");
        test(processor, userId, "удали последнюю");
        sleep(4);
        
        System.out.println("\n═══════════════════════════════════════════════════════════════════════════");
        System.out.println("TEST 5: Multi-field modification");
        System.out.println("═══════════════════════════════════════════════════════════════════════════");
        test(processor, userId, "100 на обед");
        sleep(3);
        test(processor, userId, "нет, не обед а ресторан, сумма 150, категория развлечения");
        sleep(5);
        
        System.out.println("\n═══════════════════════════════════════════════════════════════════════════");
        System.out.println("✅ All focused tests completed!");
        System.out.println("📊 Check logs above for MODIFY/DELETE behavior");
        System.out.println("═══════════════════════════════════════════════════════════════════════════");
        
        System.out.println("\n⏸️  Press Enter to exit...");
        System.in.read();
        
        context.close();
    }
    
    private static void test(SqsMessageProcessor processor, String userId, String message) {
        System.out.println("📤 \"" + message + "\"");
        
        TelegramChatRequest request = TelegramChatRequest.builder()
                .telegramUserId(userId)
                .message(message)
                .build();
        
        try {
            processor.processCommand(request);
            System.out.println("   ✅ Processed");
        } catch (Exception e) {
            System.err.println("   ❌ Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void sleep(int seconds) throws InterruptedException {
        Thread.sleep(seconds * 1000L);
    }
}

