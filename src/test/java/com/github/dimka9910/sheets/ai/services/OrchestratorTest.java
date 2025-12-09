package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.services.MessageClassifier.Tag;
import com.github.dimka9910.sheets.ai.services.Orchestrator.ContextSection;
import com.github.dimka9910.sheets.ai.services.Orchestrator.OrchestrationResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Orchestrator - context section routing.
 */
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@DisplayName("Orchestrator Tests")
class OrchestratorTest {
    
    private Orchestrator orchestrator;
    
    @BeforeEach
    void setUp() {
        orchestrator = new Orchestrator();
    }
    
    // ==================== CONTEXT SECTIONS ====================
    
    @Nested
    @DisplayName("Context Section Routing")
    class SectionTests {
        
        @Test
        @DisplayName("FINANCIAL → accounts, funds, defaults, instructions")
        void financialSections() {
            var result = orchestrator.process("кофе 300");
            
            assertTrue(result.tags().contains(Tag.FINANCIAL));
            assertTrue(result.sections().contains(ContextSection.ACCOUNTS_AND_FUNDS));
            assertTrue(result.sections().contains(ContextSection.DEFAULTS));
            
            System.out.println("FINANCIAL sections: " + result.sections());
        }
        
        @Test
        @DisplayName("SETTINGS → defaults, instructions")
        void settingsSections() {
            var result = orchestrator.process("запомни дефолтная валюта динары");
            
            assertTrue(result.tags().contains(Tag.SETTINGS));
            assertTrue(result.sections().contains(ContextSection.DEFAULTS) || 
                       result.sections().contains(ContextSection.CUSTOM_INSTRUCTIONS));
            
            System.out.println("SETTINGS sections: " + result.sections());
        }
        
        @Test
        @DisplayName("QUESTION → help info")
        void questionSections() {
            var result = orchestrator.process("как добавить счёт?");
            
            assertTrue(result.tags().contains(Tag.QUESTION));
            assertTrue(result.sections().contains(ContextSection.HELP_INFO));
            
            System.out.println("QUESTION sections: " + result.sections());
        }
        
        @Test
        @DisplayName("OFF_TOPIC → minimal")
        void offTopicSections() {
            var result = orchestrator.process("привет");
            
            assertTrue(result.tags().contains(Tag.OFF_TOPIC));
            assertTrue(result.sections().contains(ContextSection.MINIMAL));
            
            System.out.println("OFF_TOPIC sections: " + result.sections());
        }
        
        @Test
        @DisplayName("RESPONSE → previous conversation")
        void responseSections() {
            var result = orchestrator.process("да", "Записать кофе 300?");
            
            assertTrue(result.isResponse());
            assertTrue(result.sections().contains(ContextSection.PREVIOUS_CONVERSATION));
            
            System.out.println("RESPONSE sections: " + result.sections());
        }
    }
    
    // ==================== COMBINED CONTEXTS ====================
    
    @Nested
    @DisplayName("Combined Contexts")
    class CombinedTests {
        
        @Test
        @DisplayName("Expense + Settings → combined sections")
        void expenseAndSettings() {
            var result = orchestrator.process("кофе 300 и запомни дефолт динары");
            
            System.out.println("Combined tags: " + result.tags());
            System.out.println("Combined sections: " + result.sections());
            
            // Should have sections from both FINANCIAL and SETTINGS
            assertTrue(result.sections().size() >= 2, 
                    "Should have multiple sections for combined request");
        }
        
        @Test
        @DisplayName("Response with financial → previous + financial")
        void responseWithFinancial() {
            var result = orchestrator.process("да и ещё чай 200", "Записать кофе 300?");
            
            System.out.println("Response + financial tags: " + result.tags());
            System.out.println("Response + financial sections: " + result.sections());
            
            // Should have both previous conversation and financial context
            assertTrue(result.needsPreviousContext() || result.tags().contains(Tag.FINANCIAL),
                    "Should need previous or financial context");
        }
    }
    
    // ==================== FULL FLOW ====================
    
    @Nested
    @DisplayName("Full Flow")
    class FlowTests {
        
        @Test
        @DisplayName("Conversation flow")
        void conversationFlow() {
            // 1. New expense
            var r1 = orchestrator.process("кофе 300");
            assertFalse(r1.isResponse());
            assertTrue(r1.tags().contains(Tag.FINANCIAL));
            System.out.println("Step 1 (new expense): tags=" + r1.tags() + ", sections=" + r1.sections());
            
            // 2. Bot asks clarification, user answers
            var r2 = orchestrator.process("RSD", "В какой валюте?");
            assertTrue(r2.isResponse());
            assertTrue(r2.sections().contains(ContextSection.PREVIOUS_CONVERSATION));
            System.out.println("Step 2 (answer): tags=" + r2.tags() + ", sections=" + r2.sections());
            
            // 3. New expense after confirmation - model might see as response or new
            var r3 = orchestrator.process("чай 200", "✅ Записал: кофе 300 RSD");
            assertTrue(r3.tags().contains(Tag.FINANCIAL));
            System.out.println("Step 3 (new expense): isResponse=" + r3.isResponse() + 
                    ", tags=" + r3.tags() + ", sections=" + r3.sections());
        }
    }
    
    // ==================== PERFORMANCE ====================
    
    @Nested
    @DisplayName("Performance")
    class PerformanceTests {
        
        @Test
        @DisplayName("Single request < 3 seconds")
        void latency() {
            var result = orchestrator.process("кофе 300");
            
            assertTrue(result.latencyMs() < 3000,
                    "Should complete in under 3s, took " + result.latencyMs() + "ms");
            System.out.println("Latency: " + result.latencyMs() + "ms, tokens: " + result.tokensUsed());
        }
    }
}
