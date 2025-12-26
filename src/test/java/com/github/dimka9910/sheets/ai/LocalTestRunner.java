package com.github.dimka9910.sheets.ai;

import com.github.dimka9910.sheets.ai.dto.telegram.TelegramChatRequest;
import com.github.dimka9910.sheets.ai.services.SqsMessageProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Local test runner for manual testing.
 * 
 * Usage:
 * 1. Make sure DATABASE_URL is set (or use local PostgreSQL)
 * 2. Make sure OPENAI_API_KEY environment variable is set
 * 3. Run this main method
 * 4. It will process test messages and show results
 */
public class LocalTestRunner {
    
    public static void main(String[] args) throws Exception {
        System.out.println("🚀 Starting Local Test Runner...");
        
        // Initialize Spring Boot context (без web server)
        SpringApplication app = new SpringApplication(FinanceTrackerApplication.class);
        app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        ConfigurableApplicationContext context = app.run(args);
        SqsMessageProcessor processor = context.getBean(SqsMessageProcessor.class);
        
        System.out.println("✅ Spring Boot context loaded");
        System.out.println("📝 Running test scenarios...\n");
        
        // Test telegram user ID (DIMA from test data)
        String telegramUserId = "377662506";
        
        // Test 1: Simple expense
        System.out.println("═══════════════════════════════════════");
        System.out.println("TEST 1: Simple Expense");
        System.out.println("═══════════════════════════════════════");
        testMessage(processor, telegramUserId, "200 on coffee");
        Thread.sleep(2000);
        
        // Test 2: Expense without amount (should ask for clarification)
        System.out.println("\n═══════════════════════════════════════");
        System.out.println("TEST 2: Expense without amount (clarification)");
        System.out.println("═══════════════════════════════════════");
        testMessage(processor, telegramUserId, "bought groceries");
        Thread.sleep(2000);
        
        // Test 3: Transfer between accounts
        System.out.println("\n═══════════════════════════════════════");
        System.out.println("TEST 3: Internal Transfer");
        System.out.println("═══════════════════════════════════════");
        testMessage(processor, telegramUserId, "transfer 1000 from card to cash");
        Thread.sleep(2000);
        
        // Test 4: Correction (the new feature!)
        System.out.println("\n═══════════════════════════════════════");
        System.out.println("TEST 4: Correction - Change Amount");
        System.out.println("═══════════════════════════════════════");
        testMessage(processor, telegramUserId, "not 200 but 300");
        Thread.sleep(2000);
        
        // Test 5: Delete operation
        System.out.println("\n═══════════════════════════════════════");
        System.out.println("TEST 5: Delete Last Operation");
        System.out.println("═══════════════════════════════════════");
        testMessage(processor, telegramUserId, "delete last");
        Thread.sleep(2000);
        
        // Test 6: Third party transfer (to linked user KIKI)
        System.out.println("\n═══════════════════════════════════════");
        System.out.println("TEST 6: Third Party Transfer");
        System.out.println("═══════════════════════════════════════");
        testMessage(processor, telegramUserId, "sent 500 to KIKI");
        Thread.sleep(2000);
        
        // Test 7: Multi-step
        System.out.println("\n═══════════════════════════════════════");
        System.out.println("TEST 7: Multi-Step (Coffee + Taxi)");
        System.out.println("═══════════════════════════════════════");
        testMessage(processor, telegramUserId, "200 on coffee and 500 on taxi");
        Thread.sleep(2000);
        
        System.out.println("\n✅ All tests completed!");
        System.out.println("📊 Check your database for results");
        System.out.println("💡 Logs above show what happened");
        
        // Keep context open for inspection
        System.out.println("\n⏸️  Press Enter to exit...");
        System.in.read();
        
        context.close();
    }
    
    private static void testMessage(SqsMessageProcessor processor, String telegramUserId, String message) {
        System.out.println("📤 Sending message: \"" + message + "\"");
        
        TelegramChatRequest request = TelegramChatRequest.builder()
                .telegramUserId(telegramUserId)
                .message(message)
                .build();
        
        try {
            processor.processCommand(request);
            System.out.println("✅ Message processed successfully");
        } catch (Exception e) {
            System.err.println("❌ Error processing message: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

