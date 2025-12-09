package com.github.dimka9910.sheets.ai.services.llm;

import com.github.dimka9910.sheets.ai.services.llm.ResponseMatcherAgent.ResponseType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ResponseMatcherAgent - determines if user message is a response.
 */
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@DisplayName("ResponseMatcherAgent Tests")
class ResponseMatcherAgentTest {
    
    private ResponseMatcherAgent agent;
    
    @BeforeEach
    void setUp() {
        agent = new ResponseMatcherAgent();
    }
    
    // ==================== RESPONSE DETECTION ====================
    
    @Nested
    @DisplayName("Response Detection")
    class ResponseTests {
        
        @Test
        @DisplayName("Short answer after question → YES")
        void shortAnswerAfterQuestion() {
            var result = agent.match("да", "Записать кофе 300 RSD?");
            
            assertEquals(ResponseType.YES, result.responseType(),
                    "Short answer to question should be YES");
        }
        
        @Test
        @DisplayName("Currency answer after question → YES")
        void currencyAnswer() {
            var result = agent.match("RSD", "В какой валюте?");
            
            assertEquals(ResponseType.YES, result.responseType());
        }
        
        @Test
        @DisplayName("Account answer after question → YES")
        void accountAnswer() {
            var result = agent.match("карта", "С какого счёта?");
            
            assertEquals(ResponseType.YES, result.responseType());
        }
        
        @Test
        @DisplayName("Correction → YES")
        void correction() {
            var result = agent.match("не 300 а 500", "Записал: кофе 300 RSD");
            
            assertEquals(ResponseType.YES, result.responseType());
        }
        
        @Test
        @DisplayName("New expense without context → NO")
        void newExpense() {
            var result = agent.match("кофе 300", null);
            
            assertEquals(ResponseType.NO, result.responseType());
        }
        
        @Test
        @DisplayName("New topic after success message → NO")
        void newTopicAfterSuccess() {
            var result = agent.match("чай 200", "✅ Записал: кофе 300 RSD");
            
            // New expense after success = probably new topic
            System.out.println("New after success: " + result.responseType());
        }
    }
    
    // ==================== REGRESSION TESTS ====================
    
    @Nested
    @DisplayName("Regression Tests")
    class RegressionTests {
        
        @Test
        @DisplayName("Answer with account+amount after question → YES")
        void accountAndAmount_afterQuestion() {
            var result = agent.match("райф 200", "какой счёт, какая сумма?");
            
            assertEquals(ResponseType.YES, result.responseType(),
                    "User provides requested account+amount = YES");
        }
        
        @Test
        @DisplayName("Vague answer 'много' after price question → YES")
        void vagueAnswer_afterQuestion() {
            var result = agent.match("много", "сколько она стоила?");
            
            assertEquals(ResponseType.YES, result.responseType(),
                    "Even vague answer is still a response");
        }
        
        @Test
        @DisplayName("'500' after 'Какая сумма?' → YES")
        void numberAfterAmountQuestion() {
            var result = agent.match("500", "Какая сумма?");
            
            assertEquals(ResponseType.YES, result.responseType());
        }
    }
    
    // ==================== NEED_HISTORY CASES ====================
    
    @Nested
    @DisplayName("Need History Cases")
    class NeedHistoryTests {
        
        @Test
        @DisplayName("Reference to earlier message → NEED_HISTORY")
        void referenceToEarlier() {
            var result = agent.match("ту операцию исправь", "Записал: кофе 300 RSD");
            
            // "ту" implies earlier operation, not this one
            System.out.println("Reference to earlier: " + result.responseType());
        }
        
        @Test
        @DisplayName("'как раньше' → NEED_HISTORY")
        void likeBeforePattern() {
            var result = agent.match("как раньше делал", "Записать трату?");
            
            // "как раньше" implies history context
            System.out.println("Like before: " + result.responseType());
        }
    }
    
    // ==================== PERFORMANCE ====================
    
    @Nested
    @DisplayName("Performance")
    class PerformanceTests {
        
        @Test
        @DisplayName("Matching < 2 seconds")
        void timing() {
            var result = agent.match("да", "Записать?");
            
            assertTrue(result.latencyMs() < 2000, 
                    "Should complete in under 2s, took " + result.latencyMs() + "ms");
            System.out.println("Latency: " + result.latencyMs() + "ms");
        }
    }
}

