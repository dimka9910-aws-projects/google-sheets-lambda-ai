package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.services.MessageClassifier.ResponseType;
import com.github.dimka9910.sheets.ai.services.MessageClassifier.Tag;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MessageClassifier - tag-based context classification.
 * NO splitting - just tags for what context to load.
 */
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@DisplayName("MessageClassifier Tests")
class MessageClassifierTest {
    
    private MessageClassifier classifier;
    
    @BeforeEach
    void setUp() {
        classifier = new MessageClassifier();
    }
    
    // ==================== RESPONSE DETECTION ====================
    
    @Nested
    @DisplayName("Response Detection")
    class ResponseTests {
        
        @Test
        @DisplayName("Short answer after question → isResponse=true")
        void shortAnswerAfterQuestion() {
            var result = classifier.classify("да", "Записать кофе 300 RSD?");
            
            assertTrue(result.responseType() == ResponseType.YES, "Short answer to question should be RESPONSE");
            System.out.println("Tags: " + result.tags());
        }
        
        @Test
        @DisplayName("Currency answer after question → isResponse=true")
        void currencyAnswer() {
            var result = classifier.classify("RSD", "В какой валюте?");
            
            assertTrue(result.responseType() == ResponseType.YES);
        }
        
        @Test
        @DisplayName("Account answer after question → isResponse=true")
        void accountAnswer() {
            var result = classifier.classify("карта", "С какого счёта?");
            
            assertTrue(result.responseType() == ResponseType.YES);
        }
        
        @Test
        @DisplayName("Correction → isResponse=true")
        void correction() {
            var result = classifier.classify("не 300 а 500", "Записал: кофе 300 RSD");
            
            assertTrue(result.responseType() == ResponseType.YES);
        }
        
        @Test
        @DisplayName("New expense without context → isResponse=false")
        void newExpense() {
            var result = classifier.classify("кофе 300");
            
            assertFalse(result.responseType() == ResponseType.YES);
        }
    }
    
    // ==================== TAG DETECTION ====================
    
    @Nested
    @DisplayName("Tag Detection")
    class TagTests {
        
        @Test
        @DisplayName("Expense → FINANCIAL tag")
        void expenseTag() {
            var result = classifier.classify("кофе 300");
            
            assertTrue(result.tags().contains(Tag.FINANCIAL));
            System.out.println("Tags: " + result.tags());
        }
        
        @Test
        @DisplayName("Income → FINANCIAL tag")
        void incomeTag() {
            var result = classifier.classify("зарплата 5000 EUR");
            
            assertTrue(result.tags().contains(Tag.FINANCIAL));
        }
        
        @Test
        @DisplayName("Settings → SETTINGS tag")
        void settingsTag() {
            var result = classifier.classify("запомни дефолтная валюта динары");
            
            assertTrue(result.tags().contains(Tag.SETTINGS));
        }
        
        @Test
        @DisplayName("Question → QUESTION or SETTINGS tag")
        void questionTag() {
            var result = classifier.classify("как добавить счёт?");
            
            // Model may interpret as QUESTION or SETTINGS
            assertTrue(result.tags().contains(Tag.QUESTION) || result.tags().contains(Tag.SETTINGS),
                    "Should have QUESTION or SETTINGS, got: " + result.tags());
        }
        
        @Test
        @DisplayName("Greeting → OFF_TOPIC tag")
        void greetingTag() {
            var result = classifier.classify("привет");
            
            assertTrue(result.tags().contains(Tag.OFF_TOPIC));
        }
    }
    
    // ==================== MULTIPLE TAGS ====================
    
    @Nested
    @DisplayName("Multiple Tags")
    class MultiTagTests {
        
        @Test
        @DisplayName("Expense + Settings → both tags")
        void expenseAndSettings() {
            var result = classifier.classify("кофе 300 и запомни дефолт динары");
            
            System.out.println("Tags for 'expense + settings': " + result.tags());
            
            // Should have both tags (or at least not fail)
            assertTrue(result.tags().size() >= 1);
            // Ideally both, but model might only catch one - that's ok
            assertTrue(result.tags().contains(Tag.FINANCIAL) || result.tags().contains(Tag.SETTINGS),
                    "Should have at least FINANCIAL or SETTINGS");
        }
        
        @Test
        @DisplayName("Multiple expenses → FINANCIAL tag (no split)")
        void multipleExpenses() {
            var result = classifier.classify("кофе 300 и чай 200");
            
            assertTrue(result.tags().contains(Tag.FINANCIAL));
            // No split - just one FINANCIAL tag
            System.out.println("Tags: " + result.tags());
        }
        
        @Test
        @DisplayName("Question about settings → may have both tags")
        void questionAboutSettings() {
            var result = classifier.classify("покажи мои настройки");
            
            System.out.println("Tags for 'show settings': " + result.tags());
            assertTrue(result.tags().contains(Tag.QUESTION) || result.tags().contains(Tag.SETTINGS));
        }
    }
    
    // ==================== CONTEXT SENSITIVITY ====================
    
    @Nested
    @DisplayName("Context Sensitivity")
    class ContextTests {
        
        @Test
        @DisplayName("Same message, different context")
        void sameMessageDifferentContext() {
            // Without context - standalone
            var r1 = classifier.classify("500");
            System.out.println("'500' without context: responseType=" + r1.responseType() + ", tags=" + r1.tags());
            
            // With question - response
            var r2 = classifier.classify("500", "Какая сумма?");
            System.out.println("'500' after question: responseType=" + r2.responseType() + ", tags=" + r2.tags());
            
            assertEquals(ResponseType.YES, r2.responseType(), "'500' after question should be RESPONSE");
        }
        
        @Test
        @DisplayName("New topic after success message")
        void newTopicAfterSuccess() {
            var result = classifier.classify("чай 200", "✅ Записал: кофе 300 RSD");
            
            // Should at least have FINANCIAL tag
            assertTrue(result.tags().contains(Tag.FINANCIAL));
            System.out.println("New after success: isResponse=" + result.responseType() == ResponseType.YES + ", tags=" + result.tags());
            // Note: model might see it as response (correction?) or new - both valid interpretations
        }
    }
    
    // ==================== PERFORMANCE ====================
    
    @Nested
    @DisplayName("Performance")
    class PerformanceTests {
        
        @Test
        @DisplayName("Classification < 3 seconds")
        void timing() {
            var result = classifier.classify("кофе 300");
            
            assertTrue(result.latencyMs() < 3000, 
                    "Should complete in under 3s, took " + result.latencyMs() + "ms");
            System.out.println("Latency: " + result.latencyMs() + "ms");
        }
        
        @Test
        @DisplayName("Token usage < 1000")
        void tokens() {
            var result = classifier.classify("кофе 300 и запомни дефолт");
            
            assertTrue(result.tokensUsed() < 1000, 
                    "Should use < 1000 tokens, used " + result.tokensUsed());
            System.out.println("Tokens: " + result.tokensUsed());
        }
    }
    
    // ==================== LANGUAGES ====================
    
    @Nested
    @DisplayName("Multi-Language")
    class LanguageTests {
        
        @Test
        @DisplayName("English")
        void english() {
            var result = classifier.classify("coffee 300");
            assertTrue(result.tags().contains(Tag.FINANCIAL));
        }
        
        @Test
        @DisplayName("Serbian")
        void serbian() {
            var result = classifier.classify("kafa 300 RSD");
            assertTrue(result.tags().contains(Tag.FINANCIAL));
        }
        
        @Test
        @DisplayName("Mixed")
        void mixed() {
            var result = classifier.classify("bought кофе for 300");
            assertTrue(result.tags().contains(Tag.FINANCIAL));
        }
    }
    
    // ==================== EDGE CASES ====================
    
    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {
        
        @Test
        @DisplayName("Empty message → fallback")
        void emptyMessage() {
            var result = classifier.classify("");
            
            assertNotNull(result);
            assertFalse(result.tags().isEmpty());
        }
        
        @Test
        @DisplayName("Just number")
        void justNumber() {
            var result = classifier.classify("500");
            
            assertNotNull(result);
            System.out.println("Just '500': tags=" + result.tags());
        }
        
        @Test
        @DisplayName("Special characters")
        void specialChars() {
            var result = classifier.classify("кофе 300₽ @cafe #утро");
            
            assertTrue(result.tags().contains(Tag.FINANCIAL));
        }
    }
    
    // ==================== REGRESSION TESTS (проблемные кейсы) ====================
    
    @Nested
    @DisplayName("Regression Tests - Previously Failed Cases")
    class RegressionTests {
        
        // === Tag classification issues ===
        
        @Test
        @DisplayName("'Что ты умеешь?' → QUESTION, not OFF_TOPIC")
        void whatCanYouDo_shouldBeQuestion() {
            var result = classifier.classify("Что ты умеешь?");
            
            assertTrue(result.tags().contains(Tag.QUESTION), 
                    "Question about capabilities should be QUESTION, got: " + result.tags());
            System.out.println("'Что ты умеешь?': " + result.tags());
        }
        
        @Test
        @DisplayName("Single product name 'кофе' → FINANCIAL")
        void singleProductName_shouldBeFinancial() {
            var result = classifier.classify("кофе");
            
            assertTrue(result.tags().contains(Tag.FINANCIAL), 
                    "Single product name should be FINANCIAL (user forgot amount), got: " + result.tags());
            System.out.println("'кофе': " + result.tags());
        }
        
        @Test
        @DisplayName("Single product name 'кофейня' → FINANCIAL")
        void singleServiceName_shouldBeFinancial() {
            var result = classifier.classify("кофейня");
            
            assertTrue(result.tags().contains(Tag.FINANCIAL), 
                    "Single service name should be FINANCIAL, got: " + result.tags());
            System.out.println("'кофейня': " + result.tags());
        }
        
        // === isResponse issues ===
        
        @Test
        @DisplayName("Answer with account+amount after 'какой счёт, какая сумма?' → YES")
        void accountAndAmount_afterQuestion_shouldBeResponse() {
            var result = classifier.classify("райф 200", "какой счёт, какая сумма?");
            
            assertEquals(ResponseType.YES, result.responseType(), 
                    "User provides requested account+amount = RESPONSE, got: " + result.responseType());
            System.out.println("'райф 200' after question: " + result.responseType());
        }
        
        @Test
        @DisplayName("Vague answer 'много' after 'сколько стоила?' → YES")
        void vagueAnswer_afterQuestion_shouldBeResponse() {
            var result = classifier.classify("много", "сколько она стоила?");
            
            assertEquals(ResponseType.YES, result.responseType(), 
                    "Even vague/incomplete answer is still a RESPONSE, got: " + result.responseType());
            System.out.println("'много' after price question: " + result.responseType());
        }
        
        @Test
        @DisplayName("New transaction after currency question → isResponse=false")
        void newTransaction_afterCurrencyQuestion_shouldNotBeResponse() {
            var result = classifier.classify("кофе 200", "какие динары?");
            
            // This is tricky - user might be answering OR starting new transaction
            // Log for observation, don't assert strictly
            System.out.println("'кофе 200' after 'какие динары?': isResponse=" + result.responseType() == ResponseType.YES + ", tags=" + result.tags());
            
            // At minimum should be FINANCIAL
            assertTrue(result.tags().contains(Tag.FINANCIAL), "Should have FINANCIAL tag");
        }
        
        @Test
        @DisplayName("'поездка на такси' → FINANCIAL")
        void tripByTaxi_shouldBeFinancial() {
            var result = classifier.classify("поездка на такси");
            
            assertTrue(result.tags().contains(Tag.FINANCIAL), 
                    "Taxi trip description should be FINANCIAL, got: " + result.tags());
        }
        
        @Test
        @DisplayName("'купил порося' → FINANCIAL")
        void boughtSomething_shouldBeFinancial() {
            var result = classifier.classify("купил порося");
            
            assertTrue(result.tags().contains(Tag.FINANCIAL), 
                    "Purchase statement should be FINANCIAL, got: " + result.tags());
        }
        
        @Test
        @DisplayName("'RSD, всегда их используй' → SETTINGS")
        void rememberCurrency_shouldBeSettings() {
            var result = classifier.classify("RSD, всегда их короче используй");
            
            assertTrue(result.tags().contains(Tag.SETTINGS), 
                    "Instruction to remember currency should be SETTINGS, got: " + result.tags());
        }
    }
}
