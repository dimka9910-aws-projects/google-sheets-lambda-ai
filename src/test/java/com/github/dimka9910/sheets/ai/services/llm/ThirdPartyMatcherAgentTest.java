package com.github.dimka9910.sheets.ai.services.llm;

import com.github.dimka9910.sheets.ai.dto.LinkedUserEntry;
import com.github.dimka9910.sheets.ai.services.agents.ThirdPartyMatcherAgent;
import com.github.dimka9910.sheets.ai.services.agents.ThirdPartyMatcherAgent.MatchType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ThirdPartyMatcherAgent - determines if person mentioned is a linked user.
 */
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@DisplayName("ThirdPartyMatcherAgent Tests")
class ThirdPartyMatcherAgentTest {
    
    private ThirdPartyMatcherAgent agent;
    private List<LinkedUserEntry> linkedUsers;
    
    @BeforeEach
    void setUp() {
        agent = new ThirdPartyMatcherAgent();
        linkedUsers = List.of(
                LinkedUserEntry.builder().userName("KIKI").displayName("KIKI")
                        .aliases(List.of("girlfriend", "девушка", "она", "her", "ксюша")).build(),
                LinkedUserEntry.builder().userName("DIMA").displayName("DIMA")
                        .aliases(List.of("boyfriend", "парень", "он", "him", "дима")).build()
        );
    }
    
    // ==================== LINKED USER MATCHES ====================
    
    @Nested
    @DisplayName("Linked User Matches")
    class LinkedUserTests {
        
        @Test
        @DisplayName("Explicit name 'Kiki' → LINKED_USER")
        void explicitName() {
            var result = agent.match("bought for Kiki 500", linkedUsers);
            
            assertEquals(MatchType.LINKED_USER, result.matchType());
            assertEquals("KIKI", result.matchedUserName());
            System.out.println("Explicit name: " + result.reasoning());
        }
        
        @Test
        @DisplayName("Alias 'girlfriend' → LINKED_USER")
        void aliasGirlfriend() {
            var result = agent.match("transferred to girlfriend 500", linkedUsers);
            
            assertEquals(MatchType.LINKED_USER, result.matchType());
            assertEquals("KIKI", result.matchedUserName());
            System.out.println("Alias girlfriend: " + result.reasoning());
        }
        
        @Test
        @DisplayName("Russian alias 'девушке' → LINKED_USER")
        void russianAlias() {
            var result = agent.match("перевёл девушке 500", linkedUsers);
            
            assertEquals(MatchType.LINKED_USER, result.matchType());
            assertEquals("KIKI", result.matchedUserName());
            System.out.println("Russian alias: " + result.reasoning());
        }
        
        @Test
        @DisplayName("Pronoun 'her' in context → LINKED_USER")
        void pronounHer() {
            var result = agent.match("gave her 1000 for shopping", linkedUsers);
            
            assertEquals(MatchType.LINKED_USER, result.matchType());
            System.out.println("Pronoun her: " + result.reasoning());
        }
        
        @Test
        @DisplayName("'ей' in Russian → LINKED_USER")
        void russianPronoun() {
            var result = agent.match("отдал ей 500", linkedUsers);
            
            assertEquals(MatchType.LINKED_USER, result.matchType());
            System.out.println("Russian pronoun: " + result.reasoning());
        }
    }
    
    // ==================== COMMENT (NOT LINKED) ====================
    
    @Nested
    @DisplayName("Comment (Not Linked User)")
    class CommentTests {
        
        @Test
        @DisplayName("'mom' not in linked users → COMMENT")
        void momNotLinked() {
            var result = agent.match("transferred to mom for birthday", linkedUsers);
            
            assertEquals(MatchType.COMMENT, result.matchType());
            assertNull(result.matchedUserName());
            System.out.println("Mom not linked: " + result.reasoning());
        }
        
        @Test
        @DisplayName("'friend' not in linked users → COMMENT")
        void friendNotLinked() {
            var result = agent.match("coffee with friend", linkedUsers);
            
            assertEquals(MatchType.COMMENT, result.matchType());
            System.out.println("Friend: " + result.reasoning());
        }
        
        @Test
        @DisplayName("'colleague' not in linked users → COMMENT")
        void colleagueNotLinked() {
            var result = agent.match("lunch with colleague 500", linkedUsers);
            
            assertEquals(MatchType.COMMENT, result.matchType());
            System.out.println("Colleague: " + result.reasoning());
        }
        
        @Test
        @DisplayName("'маме на подарок' → COMMENT")
        void russianMomGift() {
            var result = agent.match("перевёл маме на подарок 2000", linkedUsers);
            
            assertEquals(MatchType.COMMENT, result.matchType());
            System.out.println("Mom gift: " + result.reasoning());
        }
        
        @Test
        @DisplayName("No person mentioned → COMMENT")
        void noPersonMentioned() {
            var result = agent.match("coffee 300", linkedUsers);
            
            assertEquals(MatchType.COMMENT, result.matchType());
            System.out.println("No person: " + result.reasoning());
        }
    }
    
    // ==================== EDGE CASES ====================
    
    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {
        
        @Test
        @DisplayName("Empty linked users → COMMENT")
        void emptyLinkedUsers() {
            var result = agent.match("transferred to girlfriend", List.of());
            
            assertEquals(MatchType.COMMENT, result.matchType());
        }
        
        @Test
        @DisplayName("Null linked users → COMMENT")
        void nullLinkedUsers() {
            var result = agent.match("transferred to girlfriend", null);
            
            assertEquals(MatchType.COMMENT, result.matchType());
        }
        
        @Test
        @DisplayName("Similar name but different person → COMMENT")
        void similarNameDifferent() {
            // KIKI is linked, but "Kira" is not
            var result = agent.match("gift for Kira", linkedUsers);
            
            assertEquals(MatchType.COMMENT, result.matchType());
            System.out.println("Similar name: " + result.reasoning());
        }
    }
    
    // ==================== PERFORMANCE ====================
    
    @Nested
    @DisplayName("Performance")
    class PerformanceTests {
        
        @Test
        @DisplayName("Matching < 2 seconds")
        void timing() {
            var result = agent.match("transferred to girlfriend", linkedUsers);
            
            assertTrue(result.latencyMs() < 2000, 
                    "Should complete in under 2s, took " + result.latencyMs() + "ms");
            System.out.println("Latency: " + result.latencyMs() + "ms");
        }
    }
}
