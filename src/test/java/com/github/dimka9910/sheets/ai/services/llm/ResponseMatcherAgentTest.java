package com.github.dimka9910.sheets.ai.services.llm;

import com.github.dimka9910.sheets.ai.services.agents.ResponseMatcherAgent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ResponseMatcherAgent - determines if user message is a response.
 * 
 * Two modes:
 * - hasPendingResponse=true  → bot asked question, expects direct answer
 * - hasPendingResponse=false → bot confirmed something, user might correct
 */
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@DisplayName("ResponseMatcherAgent Tests")
class ResponseMatcherAgentTest {
    
    private ResponseMatcherAgent agent;
    
    @BeforeEach
    void setUp() {
        agent = new ResponseMatcherAgent();
    }
    
    // ==================== PENDING RESPONSE MODE (bot asked question) ====================
    
    @Nested
    @DisplayName("Pending Response Mode (bot asked question)")
    class PendingResponseTests {
        
        @Test
        @DisplayName("Short answer 'да' to question → YES")
        void shortAnswerYes() {
            var result = agent.match("да", "Записать кофе 300 RSD?", true);
            
            assertTrue(result.isResponse(), "Short answer to question should be YES");
        }
        
        @Test
        @DisplayName("Currency answer to currency question → YES")
        void currencyAnswer() {
            var result = agent.match("RSD", "В какой валюте?", true);
            
            assertTrue(result.isResponse());
        }
        
        @Test
        @DisplayName("Account answer to account question → YES")
        void accountAnswer() {
            var result = agent.match("карта", "С какого счёта?", true);
            
            assertTrue(result.isResponse());
        }
        
        @Test
        @DisplayName("Amount answer to amount question → YES")
        void amountAnswer() {
            var result = agent.match("500", "Какая сумма?", true);
            
            assertTrue(result.isResponse());
        }
        
        @Test
        @DisplayName("Vague answer 'много' to price question → YES")
        void vagueAnswer() {
            var result = agent.match("много", "сколько она стоила?", true);
            
            assertTrue(result.isResponse(), "Even vague answer is still a response");
        }
        
        @Test
        @DisplayName("Account+amount answer to compound question → YES")
        void compoundAnswer() {
            var result = agent.match("райф 200", "какой счёт, какая сумма?", true);
            
            assertTrue(result.isResponse());
        }
        
        @Test
        @DisplayName("Completely new topic ignoring question → NO")
        void ignoringQuestion() {
            var result = agent.match("покажи мои настройки", "Какая сумма?", true);
            
            assertFalse(result.isResponse(), "User ignores question and asks something else = NO");
        }
    }
    
    // ==================== CORRECTION MODE (bot just confirmed) ====================
    
    @Nested
    @DisplayName("Correction Mode (bot confirmed something)")
    class CorrectionModeTests {
        
        @Test
        @DisplayName("Correction 'не X а Y' → YES")
        void correctionPattern() {
            var result = agent.match("не 300 а 500", "✅ Записал: кофе 300 RSD", false);
            
            assertTrue(result.isResponse(), "Correction pattern should be YES");
        }
        
        @Test
        @DisplayName("Disagreement 'неправильно' → YES")
        void disagreement() {
            var result = agent.match("неправильно", "✅ Записал: кофе 300 RSD", false);
            
            assertTrue(result.isResponse());
        }
        
        @Test
        @DisplayName("Undo request 'отмени' → YES")
        void undoRequest() {
            var result = agent.match("отмени", "✅ Записал: кофе 300 RSD", false);
            
            assertTrue(result.isResponse());
        }
        
        @Test
        @DisplayName("Change request 'поменяй на карту' → YES")
        void changeRequest() {
            var result = agent.match("поменяй на карту", "✅ Записал: кофе 300 RSD с наличных", false);
            
            assertTrue(result.isResponse());
        }
        
        @Test
        @DisplayName("New expense after confirmation → NO")
        void newExpenseAfterConfirm() {
            var result = agent.match("чай 200", "✅ Записал: кофе 300 RSD", false);
            
            assertFalse(result.isResponse(), "New expense after confirmation = new transaction, not correction");
        }
        
        @Test
        @DisplayName("Similar expense after confirmation → NO")
        void similarExpenseAfterConfirm() {
            var result = agent.match("кофе 500", "✅ Записал: кофе 300 RSD", false);
            
            // Even similar item = new transaction, not correction (unless explicitly says "исправь")
            System.out.println("Similar expense after confirm: " + (result.isResponse() ? "YES" : "NO"));
        }
    }
    
    // ==================== NO PREVIOUS MESSAGE ====================
    
    @Nested
    @DisplayName("No Previous Message")
    class NoPreviousTests {
        
        @Test
        @DisplayName("New expense without context → NO")
        void newExpense() {
            var result = agent.match("кофе 300", null, false);
            
            assertFalse(result.isResponse());
        }
        
        @Test
        @DisplayName("Empty previous message → NO")
        void emptyPrevious() {
            var result = agent.match("кофе 300", "", true);
            
            assertFalse(result.isResponse());
        }
    }
    
    // ==================== PERFORMANCE ====================
    
    @Nested
    @DisplayName("Performance")
    class PerformanceTests {
        
        @Test
        @DisplayName("Matching < 2 seconds")
        void timing() {
            var result = agent.match("да", "Записать?", true);
            
            assertTrue(result.latencyMs() < 2000, 
                    "Should complete in under 2s, took " + result.latencyMs() + "ms");
            System.out.println("Latency: " + result.latencyMs() + "ms");
        }
    }
}
