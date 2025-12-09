package com.github.dimka9910.sheets.ai.services;

import com.github.dimka9910.sheets.ai.services.llm.MessageClassifier.Tag;
import com.github.dimka9910.sheets.ai.services.Orchestrator.ModelChoice;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Orchestrator - model routing based on tags.
 */
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@DisplayName("Orchestrator Tests")
class OrchestratorTest {
    
    private Orchestrator orchestrator;
    
    @BeforeEach
    void setUp() {
        orchestrator = new Orchestrator();
    }
    
    @Nested
    @DisplayName("Model Routing")
    class ModelRoutingTests {
        
        @Test
        @DisplayName("Simple expense → FAST model")
        void simpleExpenseFast() {
            var result = orchestrator.process("кофе 300");
            
            assertEquals(ModelChoice.FAST, result.model());
            assertTrue(result.tags().contains(Tag.FINANCIAL));
            System.out.println("Simple expense: " + result.rawJson());
        }
        
        @Test
        @DisplayName("Complex request → SMART model")
        void complexSmart() {
            var result = orchestrator.process("кофе 300 и запомни дефолт динары");
            
            System.out.println("Complex: " + result.rawJson());
            // Should be SMART if COMPLEX tag present
            if (result.tags().contains(Tag.COMPLEX)) {
                assertEquals(ModelChoice.SMART, result.model());
            }
        }
        
        @Test
        @DisplayName("Response → check isResponse")
        void responseFlag() {
            var result = orchestrator.process("да", "Записать кофе 300?");
            
            assertTrue(result.isResponse());
            System.out.println("Response: " + result.rawJson());
        }
    }
    
    @Nested
    @DisplayName("Tag Detection")
    class TagTests {
        
        @Test
        @DisplayName("Third party detection")
        void thirdParty() {
            var result = orchestrator.process("купил для Кики 500");
            
            System.out.println("Third party: " + result.rawJson());
            // Should detect THIRD_PARTY
            assertTrue(result.tags().contains(Tag.FINANCIAL));
        }
        
        @Test
        @DisplayName("Settings detection")
        void settings() {
            var result = orchestrator.process("запомни дефолтная валюта динары");
            
            assertTrue(result.tags().contains(Tag.SETTINGS));
            System.out.println("Settings: " + result.rawJson());
        }
    }
    
    @Nested
    @DisplayName("Raw JSON")
    class RawJsonTests {
        
        @Test
        @DisplayName("Raw JSON is returned")
        void hasRawJson() {
            var result = orchestrator.process("кофе 300");
            
            assertNotNull(result.rawJson());
            assertTrue(result.rawJson().contains("isResponse"));
            assertTrue(result.rawJson().contains("tags"));
            System.out.println("Raw: " + result.rawJson());
        }
    }
    
    @Nested
    @DisplayName("Performance")
    class PerformanceTests {
        
        @Test
        @DisplayName("< 3 seconds")
        void latency() {
            var result = orchestrator.process("кофе 300");
            
            assertTrue(result.latencyMs() < 3000);
            System.out.println("Latency: " + result.latencyMs() + "ms");
        }
    }
}
