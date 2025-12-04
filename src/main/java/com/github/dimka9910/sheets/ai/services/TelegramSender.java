package com.github.dimka9910.sheets.ai.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Отправляет сообщения напрямую в Telegram API.
 * Используется для асинхронной архитектуры — AI Parser сам отправляет ответы.
 */
@Slf4j
public class TelegramSender {

    private static final String TELEGRAM_API = "https://api.telegram.org/bot";
    
    private final String botToken;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public TelegramSender() {
        this.botToken = System.getenv("TELEGRAM_BOT_TOKEN");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
        
        if (botToken == null || botToken.isBlank()) {
            log.warn("TELEGRAM_BOT_TOKEN not set - Telegram sending disabled");
        }
    }

    /**
     * Отправляет сообщение в Telegram чат
     */
    public void sendMessage(String chatId, String text) {
        if (botToken == null || botToken.isBlank()) {
            log.warn("Cannot send to Telegram: bot token not configured");
            return;
        }

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("chat_id", chatId);
            payload.put("text", text);

            String jsonPayload = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TELEGRAM_API + botToken + "/sendMessage"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                log.info("Telegram message sent to chat {}", chatId);
            } else {
                log.error("Telegram API error: {} - {}", response.statusCode(), response.body());
            }

        } catch (Exception e) {
            log.error("Error sending message to Telegram: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Проверяет настроен ли Telegram токен
     */
    public boolean isConfigured() {
        return botToken != null && !botToken.isBlank();
    }
}

