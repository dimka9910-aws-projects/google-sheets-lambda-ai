import com.github.dimka9910.sheets.ai.SQSHandler;
import com.github.dimka9910.sheets.ai.dto.telegram.TelegramChatRequest;
import com.github.dimka9910.sheets.ai.dto.telegram.TelegramChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LOCAL EDGE CASE TESTS - Прогоняем все жёсткие сценарии
 */
@SpringBootTest
public class LocalEdgeCaseTest {

    @Autowired
    private SQSHandler handler;

    private TelegramChatRequest createRequest(String message, String chatId, String userId) {
        return TelegramChatRequest.builder()
                .chatId(chatId)
                .message(message)
                .userId(userId)
                .userName("DIMA")
                .build();
    }

    @Test
    public void test_simpleExpense() {
        var request = createRequest("coffee 200", "377662506", "377662506");
        TelegramChatResponse response = handler.processMessage(request);
        
        assertNotNull(response, "Response должен быть не null");
        assertTrue(response.isSuccess(), "Должно быть успешно");
        assertNotNull(response.getMessage(), "Message должен быть не null");
        System.out.println("✅ SIMPLE EXPENSE: " + response.getMessage());
    }

    @Test
    public void test_expenseWithLinkedUser() {
        var request = createRequest("bought coffee for Kiki 200", "377662506", "377662506");
        TelegramChatResponse response = handler.processMessage(request);
        
        assertNotNull(response, "Response должен быть не null");
        assertTrue(response.isSuccess(), "Должно быть успешно");
        System.out.println("✅ EXPENSE FOR LINKED USER: " + response.getMessage());
    }

    @Test
    public void test_transferToLinkedUser() {
        var request = createRequest("sent 500 to Kiki", "377662506", "377662506");
        TelegramChatResponse response = handler.processMessage(request);
        
        assertNotNull(response, "Response должен быть не null");
        assertTrue(response.isSuccess(), "Должно быть успешно");
        System.out.println("✅ TRANSFER TO LINKED USER: " + response.getMessage());
    }

    @Test
    public void test_internalTransfer() {
        var request = createRequest("transfer 1000 from card to cash", "377662506", "377662506");
        TelegramChatResponse response = handler.processMessage(request);
        
        assertNotNull(response, "Response должен быть не null");
        assertTrue(response.isSuccess(), "Должно быть успешно");
        System.out.println("✅ INTERNAL TRANSFER: " + response.getMessage());
    }

    @Test
    public void test_crossUserExpense() {
        var request = createRequest("paid for Kiki's fuel 2000 on her transport budget", "377662506", "377662506");
        TelegramChatResponse response = handler.processMessage(request);
        
        assertNotNull(response, "Response должен быть не null");
        assertTrue(response.isSuccess(), "Должно быть успешно");
        System.out.println("✅ CROSS-USER EXPENSE: " + response.getMessage());
    }

    @Test
    public void test_clarificationNeeded() {
        var request = createRequest("coffee", "377662506", "377662506");
        TelegramChatResponse response = handler.processMessage(request);
        
        assertNotNull(response, "Response должен быть не null");
        assertTrue(response.isSuccess(), "Должно быть успешно");
        assertTrue(response.getMessage().contains("?") || response.getMessage().toLowerCase().contains("how much"),
                "Должен запросить уточнение");
        System.out.println("✅ CLARIFICATION: " + response.getMessage());
    }

    @Test
    public void test_correction() {
        // Сначала создаём операцию
        var request1 = createRequest("coffee 200", "377662506", "377662506");
        handler.processMessage(request1);
        
        // Теперь исправляем
        var request2 = createRequest("no, it was 300", "377662506", "377662506");
        TelegramChatResponse response = handler.processMessage(request2);
        
        assertNotNull(response, "Response должен быть не null");
        assertTrue(response.isSuccess(), "Должно быть успешно");
        System.out.println("✅ CORRECTION: " + response.getMessage());
    }

    @Test
    public void test_emptyMessage() {
        var request = createRequest("", "377662506", "377662506");
        TelegramChatResponse response = handler.processMessage(request);
        
        assertNotNull(response, "Response должен быть не null");
        assertFalse(response.isSuccess(), "Должно быть неуспешно");
        System.out.println("✅ EMPTY MESSAGE: " + response.getMessage());
    }

    @Test
    public void test_nullMessage() {
        var request = createRequest(null, "377662506", "377662506");
        TelegramChatResponse response = handler.processMessage(request);
        
        assertNotNull(response, "Response должен быть не null");
        assertFalse(response.isSuccess(), "Должно быть неуспешно");
        System.out.println("✅ NULL MESSAGE: " + response.getMessage());
    }

    @Test
    public void test_customInstruction() {
        var request = createRequest("set default currency to EUR", "377662506", "377662506");
        TelegramChatResponse response = handler.processMessage(request);
        
        assertNotNull(response, "Response должен быть не null");
        assertTrue(response.isSuccess(), "Должно быть успешно");
        System.out.println("✅ CUSTOM INSTRUCTION: " + response.getMessage());
    }
}

