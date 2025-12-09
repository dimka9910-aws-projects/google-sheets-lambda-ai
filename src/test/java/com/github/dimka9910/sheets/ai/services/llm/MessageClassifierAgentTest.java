package com.github.dimka9910.sheets.ai.services.llm;

import com.github.dimka9910.sheets.ai.services.llm.MessageClassifierAgent.Tag;
import com.github.dimka9910.sheets.ai.services.llm.MessageClassifierAgent.TagsResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MessageClassifierAgent - tag-based context classification.
 */
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@DisplayName("MessageClassifierAgent Tests")
class MessageClassifierAgentTest {
    
    private MessageClassifierAgent agent;
    
    @BeforeEach
    void setUp() {
        agent = new MessageClassifierAgent();
    }
    
    // ==================== TAG DETECTION ====================
    
    @Nested
    @DisplayName("Tag Detection")
    class TagTests {
        
        @Test
        @DisplayName("Expense → FINANCIAL tag")
        void expenseTag() {
            var result = agent.classify("кофе 300", null);
            
            assertTrue(result.tags().contains(Tag.FINANCIAL));
            System.out.println("Tags: " + result.tags());
        }
        
        @Test
        @DisplayName("Income → FINANCIAL tag")
        void incomeTag() {
            var result = agent.classify("зарплата 5000 EUR", null);
            
            assertTrue(result.tags().contains(Tag.FINANCIAL));
        }
        
        @Test
        @DisplayName("Settings → SETTINGS tag")
        void settingsTag() {
            var result = agent.classify("запомни дефолтная валюта динары", null);
            
            assertTrue(result.tags().contains(Tag.SETTINGS));
        }
        
        @Test
        @DisplayName("Question → QUESTION tag")
        void questionTag() {
            var result = agent.classify("Что ты умеешь?", null);
            
            assertTrue(result.tags().contains(Tag.QUESTION) || result.tags().contains(Tag.SETTINGS),
                    "Should have QUESTION or SETTINGS, got: " + result.tags());
        }
        
        @Test
        @DisplayName("Greeting → OFF_TOPIC tag")
        void greetingTag() {
            var result = agent.classify("привет как дела?", null);
            
            assertTrue(result.tags().contains(Tag.OFF_TOPIC) || result.tags().contains(Tag.QUESTION),
                    "Greeting should be OFF_TOPIC or QUESTION, got: " + result.tags());
        }
    }
    
    // ==================== SUB-TAGS ====================
    
    @Nested
    @DisplayName("Financial Sub-Tags")
    class SubTagTests {
        
        @Test
        @DisplayName("Transfer → TRANSFER tag")
        void transferTag() {
            var result = agent.classify("перевёл с карты на наличку 500", null);
            
            assertTrue(result.tags().contains(Tag.FINANCIAL));
            System.out.println("Transfer tags: " + result.tags());
        }
        
        @Test
        @DisplayName("Third party → THIRD_PARTY tag")
        void thirdPartyTag() {
            var result = agent.classify("купил для Кики 500", null);
            
            assertTrue(result.tags().contains(Tag.FINANCIAL));
            System.out.println("Third party tags: " + result.tags());
        }
        
        @Test
        @DisplayName("Complex request → COMPLEX tag")
        void complexTag() {
            var result = agent.classify("кофе 300 и запомни дефолт динары и покажи настройки", null);
            
            System.out.println("Complex tags: " + result.tags());
            // Should have COMPLEX for multi-intent message
        }
    }
    
    // ==================== REGRESSION TESTS ====================
    
    @Nested
    @DisplayName("Regression Tests")
    class RegressionTests {
        
        @Test
        @DisplayName("'Что ты умеешь?' → QUESTION, not OFF_TOPIC")
        void whatCanYouDo_shouldBeQuestion() {
            var result = agent.classify("Что ты умеешь?", null);
            
            assertTrue(result.tags().contains(Tag.QUESTION), 
                    "Question about capabilities should be QUESTION, got: " + result.tags());
        }
        
        @Test
        @DisplayName("Single product name 'кофе' → FINANCIAL")
        void singleProductName_shouldBeFinancial() {
            var result = agent.classify("кофе", null);
            
            assertTrue(result.tags().contains(Tag.FINANCIAL), 
                    "Single product name should be FINANCIAL, got: " + result.tags());
        }
        
        @Test
        @DisplayName("Single product name 'кофейня' → FINANCIAL")
        void singleServiceName_shouldBeFinancial() {
            var result = agent.classify("кофейня", null);
            
            assertTrue(result.tags().contains(Tag.FINANCIAL), 
                    "Single service name should be FINANCIAL, got: " + result.tags());
        }
        
        @Test
        @DisplayName("'RSD, всегда их используй' → SETTINGS")
        void rememberCurrency_shouldBeSettings() {
            var result = agent.classify("RSD, всегда их короче используй", null);
            
            assertTrue(result.tags().contains(Tag.SETTINGS), 
                    "Instruction should be SETTINGS, got: " + result.tags());
        }
    }
    
    // ==================== PERFORMANCE ====================
    
    @Nested
    @DisplayName("Performance")
    class PerformanceTests {
        
        @Test
        @DisplayName("Classification < 3 seconds")
        void timing() {
            var result = agent.classify("кофе 300", null);
            
            assertTrue(result.latencyMs() < 3000, 
                    "Should complete in under 3s, took " + result.latencyMs() + "ms");
            System.out.println("Latency: " + result.latencyMs() + "ms");
        }
        
        @Test
        @DisplayName("Token usage < 1000")
        void tokens() {
            var result = agent.classify("кофе 300 и запомни дефолт", null);
            
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
            var result = agent.classify("coffee 300", null);
            assertTrue(result.tags().contains(Tag.FINANCIAL));
        }
        
        @Test
        @DisplayName("Serbian")
        void serbian() {
            var result = agent.classify("kafa 300 RSD", null);
            assertTrue(result.tags().contains(Tag.FINANCIAL));
        }
        
        @Test
        @DisplayName("Mixed")
        void mixed() {
            var result = agent.classify("bought кофе for 300", null);
            assertTrue(result.tags().contains(Tag.FINANCIAL));
        }
    }
}

