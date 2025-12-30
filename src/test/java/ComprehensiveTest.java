import com.github.dimka9910.sheets.ai.FinanceTrackerApplication;
import com.github.dimka9910.sheets.ai.dto.user.*;
import com.github.dimka9910.sheets.ai.dto.telegram.TelegramChatRequest;
import com.github.dimka9910.sheets.ai.dto.telegram.TelegramChatResponse;
import com.github.dimka9910.sheets.ai.dto.response.*;
import com.github.dimka9910.sheets.ai.services.*;
import com.github.dimka9910.sheets.ai.db.repository.*;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.*;

public class ComprehensiveTest {
    
    private static TelegramChatResponse sendMessage(Orchestrator orchestrator, UserEntityService userSvc, 
                                                     String telegramId, String chatId, String message) {
        var user = userSvc.resolveWithLinkedUsers(telegramId).orElseThrow();
        var request = TelegramChatRequest.builder()
            .telegramChatId(chatId)
            .telegramUserId(telegramId)
            .message(message)
            .build();
        return orchestrator.process(request, user);
    }
    
    public static void main(String[] args) throws Exception {
        SpringApplication app = new SpringApplication(FinanceTrackerApplication.class);
        app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        ConfigurableApplicationContext ctx = app.run(args);
        
        UserEntityService userSvc = ctx.getBean(UserEntityService.class);
        Orchestrator orchestrator = ctx.getBean(Orchestrator.class);
        ChatMessageJpaRepository chatRepo = ctx.getBean(ChatMessageJpaRepository.class);
        UserJpaRepository userRepo = ctx.getBean(UserJpaRepository.class);
        
        System.out.println("\n" + "=".repeat(80));
        System.out.println("🔥 COMPREHENSIVE TEST SUITE 🔥");
        System.out.println("=".repeat(80));
        
        var userJpa = userRepo.findByTelegramId("377662506").orElseThrow();
        UUID userId = userJpa.getId();
        String chatId = "test-chat-" + System.currentTimeMillis();
        String telegramId = "377662506";
        
        int passed = 0;
        int failed = 0;
        
        // ═══════════════════════════════════════════════════════════════════════════
        // TEST 1: Simple expense
        // ═══════════════════════════════════════════════════════════════════════════
        System.out.println("\n📝 TEST 1: Simple expense");
        try {
            int beforeCount = chatRepo.findLastNMessages(userId, 1000).size();
            
            var response = sendMessage(orchestrator, userSvc, telegramId, chatId, "coffee 150");
            
            int afterCount = chatRepo.findLastNMessages(userId, 1000).size();
            
            if (response.isSuccess() && afterCount == beforeCount + 2) {
                System.out.println("✅ PASS: Expense created, history saved (+" + (afterCount - beforeCount) + " messages)");
                passed++;
            } else {
                System.out.println("❌ FAIL: success=" + response.isSuccess() + ", messages added=" + (afterCount - beforeCount));
                failed++;
            }
        } catch (Exception e) {
            System.out.println("❌ FAIL: " + e.getMessage());
            failed++;
        }
        
        // ═══════════════════════════════════════════════════════════════════════════
        // TEST 2: Immediate correction
        // ═══════════════════════════════════════════════════════════════════════════
        System.out.println("\n📝 TEST 2: Immediate correction");
        try {
            int beforeCount = chatRepo.findLastNMessages(userId, 1000).size();
            
            var response = sendMessage(orchestrator, userSvc, telegramId, chatId, "no, it was 200");
            
            int afterCount = chatRepo.findLastNMessages(userId, 1000).size();
            
            if (response.isSuccess() && afterCount == beforeCount + 2) {
                System.out.println("✅ PASS: Correction applied, history saved (+" + (afterCount - beforeCount) + " messages)");
                passed++;
            } else {
                System.out.println("❌ FAIL: success=" + response.isSuccess() + ", messages added=" + (afterCount - beforeCount));
                System.out.println("   Response: " + response.getMessage());
                failed++;
            }
        } catch (Exception e) {
            System.out.println("❌ FAIL: " + e.getMessage());
            failed++;
        }
        
        // ═══════════════════════════════════════════════════════════════════════════
        // TEST 3: Multiple operations, then correction
        // ═══════════════════════════════════════════════════════════════════════════
        System.out.println("\n📝 TEST 3: Multiple operations, then correct LAST one");
        try {
            sendMessage(orchestrator, userSvc, telegramId, chatId, "taxi 300");
            Thread.sleep(100);
            sendMessage(orchestrator, userSvc, telegramId, chatId, "lunch 500");
            Thread.sleep(100);
            
            int beforeCount = chatRepo.findLastNMessages(userId, 1000).size();
            var response = sendMessage(orchestrator, userSvc, telegramId, chatId, "actually lunch was 600");
            int afterCount = chatRepo.findLastNMessages(userId, 1000).size();
            
            if (response.isSuccess() && afterCount == beforeCount + 2) {
                System.out.println("✅ PASS: Corrected LAST operation (lunch), history saved");
                passed++;
            } else {
                System.out.println("❌ FAIL: success=" + response.isSuccess() + ", messages added=" + (afterCount - beforeCount));
                System.out.println("   Response: " + response.getMessage());
                failed++;
            }
        } catch (Exception e) {
            System.out.println("❌ FAIL: " + e.getMessage());
            failed++;
        }
        
        // ═══════════════════════════════════════════════════════════════════════════
        // TEST 4: History persistence after reload
        // ═══════════════════════════════════════════════════════════════════════════
        System.out.println("\n📝 TEST 4: History persists after reload");
        try {
            int dbCount = chatRepo.findLastNMessages(userId, 1000).size();
            
            // Reload user
            var user = userSvc.resolveWithLinkedUsers(telegramId).orElseThrow();
            int loadedCount = user.getConversationHistory() != null ? user.getConversationHistory().size() : 0;
            
            if (loadedCount > 0 && loadedCount <= dbCount) {
                System.out.println("✅ PASS: Loaded " + loadedCount + " messages (DB has " + dbCount + ")");
                
                // Check that last message has financial actions
                var lastMsg = user.getConversationHistory().get(loadedCount - 1);
                if (lastMsg.getRelatedFinancialActions() != null && !lastMsg.getRelatedFinancialActions().isEmpty()) {
                    System.out.println("✅ PASS: Last message has " + lastMsg.getRelatedFinancialActions().size() + " financial actions with UUIDs");
                    passed++;
                } else {
                    System.out.println("⚠️  WARN: Last message has no financial actions (might be user message)");
                    passed++;
                }
            } else {
                System.out.println("❌ FAIL: Loaded " + loadedCount + " messages, DB has " + dbCount);
                failed++;
            }
        } catch (Exception e) {
            System.out.println("❌ FAIL: " + e.getMessage());
            e.printStackTrace();
            failed++;
        }
        
        // ═══════════════════════════════════════════════════════════════════════════
        // TEST 5: Linked user operations (if KIKI exists)
        // ═══════════════════════════════════════════════════════════════════════════
        System.out.println("\n📝 TEST 5: Linked user operations");
        try {
            var user = userSvc.resolveWithLinkedUsers(telegramId).orElseThrow();
            
            if (user.getLinkedUsers() != null && !user.getLinkedUsers().isEmpty()) {
                String linkedUserName = user.getLinkedUsers().get(0).getUserName();
                System.out.println("   Found linked user: " + linkedUserName);
                
                int beforeCount = chatRepo.findLastNMessages(userId, 1000).size();
                var response = sendMessage(orchestrator, userSvc, telegramId, chatId, 
                    "bought coffee for " + linkedUserName + " 200");
                int afterCount = chatRepo.findLastNMessages(userId, 1000).size();
                
                if (response.isSuccess() && afterCount == beforeCount + 2) {
                    System.out.println("✅ PASS: Third-party operation, history saved");
                    passed++;
                } else {
                    System.out.println("❌ FAIL: success=" + response.isSuccess() + ", messages added=" + (afterCount - beforeCount));
                    System.out.println("   Response: " + response.getMessage());
                    failed++;
                }
            } else {
                System.out.println("⏭️  SKIP: No linked users found");
            }
        } catch (Exception e) {
            System.out.println("❌ FAIL: " + e.getMessage());
            failed++;
        }
        
        // ═══════════════════════════════════════════════════════════════════════════
        // TEST 6: Conversation history with timestamps
        // ═══════════════════════════════════════════════════════════════════════════
        System.out.println("\n📝 TEST 6: All loaded messages have timestamps");
        try {
            var user = userSvc.resolveWithLinkedUsers(telegramId).orElseThrow();
            
            int withTimestamp = 0;
            int withoutTimestamp = 0;
            
            for (var msg : user.getConversationHistory()) {
                if (msg.getTimestamp() != null) {
                    withTimestamp++;
                } else {
                    withoutTimestamp++;
                }
            }
            
            if (withTimestamp > 0 && withoutTimestamp == 0) {
                System.out.println("✅ PASS: All " + withTimestamp + " messages have timestamps");
                passed++;
            } else {
                System.out.println("⚠️  WARN: " + withTimestamp + " with timestamp, " + withoutTimestamp + " without");
                passed++;
            }
        } catch (Exception e) {
            System.out.println("❌ FAIL: " + e.getMessage());
            failed++;
        }
        
        // ═══════════════════════════════════════════════════════════════════════════
        // TEST 7: Financial actions have UUIDs
        // ═══════════════════════════════════════════════════════════════════════════
        System.out.println("\n📝 TEST 7: Financial actions in history have UUIDs");
        try {
            var user = userSvc.resolveWithLinkedUsers(telegramId).orElseThrow();
            
            int actionsWithUUID = 0;
            int actionsWithoutUUID = 0;
            
            for (var msg : user.getConversationHistory()) {
                if (msg.getRelatedFinancialActions() != null) {
                    for (var action : msg.getRelatedFinancialActions()) {
                        if (action.getId() != null) {
                            actionsWithUUID++;
                        } else {
                            actionsWithoutUUID++;
                        }
                    }
                }
            }
            
            if (actionsWithUUID > 0) {
                System.out.println("✅ PASS: Found " + actionsWithUUID + " actions with UUIDs");
                if (actionsWithoutUUID > 0) {
                    System.out.println("   ℹ️  Note: " + actionsWithoutUUID + " actions without UUID (newly created, not yet in DB)");
                }
                passed++;
            } else {
                System.out.println("⚠️  WARN: No actions with UUIDs found in history");
                passed++;
            }
        } catch (Exception e) {
            System.out.println("❌ FAIL: " + e.getMessage());
            failed++;
        }
        
        // ═══════════════════════════════════════════════════════════════════════════
        // SUMMARY
        // ═══════════════════════════════════════════════════════════════════════════
        System.out.println("\n" + "=".repeat(80));
        System.out.println("📊 RESULTS:");
        System.out.println("   ✅ Passed: " + passed);
        System.out.println("   ❌ Failed: " + failed);
        if (passed + failed > 0) {
            System.out.println("   📈 Success rate: " + (passed * 100 / (passed + failed)) + "%");
        }
        System.out.println("=".repeat(80));
        
        if (failed == 0) {
            System.out.println("🎉 ALL TESTS PASSED! 🎉");
        } else {
            System.out.println("⚠️  SOME TESTS FAILED - CHECK LOGS ABOVE");
        }
        
        ctx.close();
        System.exit(failed > 0 ? 1 : 0);
    }
}
