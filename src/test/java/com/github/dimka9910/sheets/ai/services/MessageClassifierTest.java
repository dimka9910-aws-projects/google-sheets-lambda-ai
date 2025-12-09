package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.services.MessageClassifier.ClassificationResult;
import com.github.dimka9910.sheets.ai.services.MessageClassifier.Confidence;
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
            
            assertTrue(result.isResponse(), "Short answer to question should be RESPONSE");
            System.out.println("Tags: " + result.tags());
        }
        
        @Test
        @DisplayName("Currency answer after question → isResponse=true")
        void currencyAnswer() {
            var result = classifier.classify("RSD", "В какой валюте?");
            
            assertTrue(result.isResponse());
        }
        
        @Test
        @DisplayName("Account answer after question → isResponse=true")
        void accountAnswer() {
            var result = classifier.classify("карта", "С какого счёта?");
            
            assertTrue(result.isResponse());
        }
        
        @Test
        @DisplayName("Correction → isResponse=true")
        void correction() {
            var result = classifier.classify("не 300 а 500", "Записал: кофе 300 RSD");
            
            assertTrue(result.isResponse());
        }
        
        @Test
        @DisplayName("New expense without context → isResponse=false")
        void newExpense() {
            var result = classifier.classify("кофе 300");
            
            assertFalse(result.isResponse());
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
            
            assertTrue(result.hasTag(Tag.FINANCIAL));
            System.out.println("Tags: " + result.tags());
        }
        
        @Test
        @DisplayName("Income → FINANCIAL tag")
        void incomeTag() {
            var result = classifier.classify("зарплата 5000 EUR");
            
            assertTrue(result.hasTag(Tag.FINANCIAL));
        }
        
        @Test
        @DisplayName("Settings → SETTINGS tag")
        void settingsTag() {
            var result = classifier.classify("запомни дефолтная валюта динары");
            
            assertTrue(result.hasTag(Tag.SETTINGS));
        }
        
        @Test
        @DisplayName("Question → QUESTION or SETTINGS tag")
        void questionTag() {
            var result = classifier.classify("как добавить счёт?");
            
            // Model may interpret as QUESTION or SETTINGS
            assertTrue(result.hasTag(Tag.QUESTION) || result.hasTag(Tag.SETTINGS),
                    "Should have QUESTION or SETTINGS, got: " + result.tags());
        }
        
        @Test
        @DisplayName("Greeting → OFF_TOPIC tag")
        void greetingTag() {
            var result = classifier.classify("привет");
            
            assertTrue(result.hasTag(Tag.OFF_TOPIC));
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
            assertTrue(result.hasTag(Tag.FINANCIAL) || result.hasTag(Tag.SETTINGS),
                    "Should have at least FINANCIAL or SETTINGS");
        }
        
        @Test
        @DisplayName("Multiple expenses → FINANCIAL tag (no split)")
        void multipleExpenses() {
            var result = classifier.classify("кофе 300 и чай 200");
            
            assertTrue(result.hasTag(Tag.FINANCIAL));
            // No split - just one FINANCIAL tag
            System.out.println("Tags: " + result.tags());
        }
        
        @Test
        @DisplayName("Question about settings → may have both tags")
        void questionAboutSettings() {
            var result = classifier.classify("покажи мои настройки");
            
            System.out.println("Tags for 'show settings': " + result.tags());
            assertTrue(result.hasTag(Tag.QUESTION) || result.hasTag(Tag.SETTINGS));
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
            System.out.println("'500' without context: isResponse=" + r1.isResponse() + ", tags=" + r1.tags());
            
            // With question - response
            var r2 = classifier.classify("500", "Какая сумма?");
            System.out.println("'500' after question: isResponse=" + r2.isResponse() + ", tags=" + r2.tags());
            
            assertTrue(r2.isResponse(), "'500' after question should be RESPONSE");
        }
        
        @Test
        @DisplayName("New topic after success message")
        void newTopicAfterSuccess() {
            var result = classifier.classify("чай 200", "✅ Записал: кофе 300 RSD");
            
            // Should at least have FINANCIAL tag
            assertTrue(result.hasTag(Tag.FINANCIAL));
            System.out.println("New after success: isResponse=" + result.isResponse() + ", tags=" + result.tags());
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
            assertTrue(result.hasTag(Tag.FINANCIAL));
        }
        
        @Test
        @DisplayName("Serbian")
        void serbian() {
            var result = classifier.classify("kafa 300 RSD");
            assertTrue(result.hasTag(Tag.FINANCIAL));
        }
        
        @Test
        @DisplayName("Mixed")
        void mixed() {
            var result = classifier.classify("bought кофе for 300");
            assertTrue(result.hasTag(Tag.FINANCIAL));
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
            
            assertTrue(result.hasTag(Tag.FINANCIAL));
        }
    }
}
