package com.github.dimka9910.sheets.ai.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.lang.NonNull;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;
import java.io.IOException;

/**
 * Workaround для исправления проблемы extra_body в Spring AI 1.1.x.
 * 
 * Spring AI 1.1.x добавляет поле "extra_body" в JSON-запросы к OpenAI API,
 * которое API отвергает с ошибкой 400 "Unrecognized request argument supplied: extra_body".
 * 
 * Этот конфигурационный класс регистрирует HTTP-перехватчик, который:
 * 1. Перехватывает запрос ПОСЛЕ сериализации Jackson
 * 2. Парсит JSON и удаляет поле "extra_body"
 * 3. Если extra_body содержал данные, переносит их в корень JSON
 * 4. Отправляет исправленный JSON в OpenAI API
 * 
 * Этот workaround можно безопасно удалить после обновления на Spring AI 2.0+,
 * где проблема исправлена на уровне архитектуры.
 * 
 * @see <a href="https://github.com/spring-projects/spring-ai/issues/XXX">Spring AI Issue</a>
 */
@Slf4j
@Configuration
public class SpringAiFixConfiguration {

    /**
     * Регистрируем кастомайзер для RestClient, который используется Spring AI.
     * Этот бин автоматически применится к билдеру, создающему клиент для OpenAI.
     */
    @Bean
    @SuppressWarnings("null")
    public RestClientCustomizer openaiExtraBodyFixCustomizer() {
        log.info("🔧 Registering extra_body workaround interceptor for Spring AI 1.1.x");
        return restClientBuilder -> {
            // GPT-5.x reasoning models can take >10s. Default client read timeout is too aggressive.
            // Keep connect timeout small, but allow longer read timeouts.
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
            requestFactory.setReadTimeout(Duration.ofSeconds(45));
            restClientBuilder.requestFactory(requestFactory);

            restClientBuilder.requestInterceptors(interceptors -> 
                interceptors.add(new ExtraBodyFixInterceptor())
            );
        };
    }

    /**
     * Перехватчик, исправляющий JSON-пейлоад.
     */
    @Slf4j
    static class ExtraBodyFixInterceptor implements ClientHttpRequestInterceptor {
        // Используем свой маппер для операций с деревом JSON
        private static final ObjectMapper mapper = new ObjectMapper();

        @Override
        @NonNull
        public ClientHttpResponse intercept(
                @NonNull HttpRequest request, 
                @NonNull byte[] body,
                @NonNull ClientHttpRequestExecution execution
        ) throws IOException {
            // Применяем логику только к запросам чат-комплишенов, чтобы не задеть embeddings или image generation
            // Путь может варьироваться, поэтому проверяем наличие сегмента
            if (request.getURI().getPath().contains("/chat/completions")) {
                try {
                    // 1. Читаем текущее (ошибочное) тело запроса
                    JsonNode root = mapper.readTree(body);

                    // 2. Проверяем наличие ошибочного ключа extra_body
                    if (root.isObject() && root.has("extra_body")) {
                        log.debug("🔧 Found extra_body in request, removing it...");
                        ObjectNode objectRoot = (ObjectNode) root;
                        JsonNode extraBodyNode = objectRoot.get("extra_body");
                        
                        // 3. Удаляем неверный ключ
                        objectRoot.remove("extra_body");
                        
                        // 4. Если extra_body был объектом, переносим все его поля в корень
                        if (extraBodyNode.isObject()) {
                            extraBodyNode.fields().forEachRemaining(entry -> {
                                log.debug("🔧 Moving field from extra_body to root: {}", entry.getKey());
                                objectRoot.set(entry.getKey(), entry.getValue());
                            });
                        }
                        
                        // 5. Сериализуем исправленный JSON обратно в байты
                        byte[] newBody = mapper.writeValueAsBytes(objectRoot);
                        
                        log.debug("✅ Successfully patched request, removed extra_body");
                        
                        // 6. Передаем выполнение дальше с новым телом
                        return execution.execute(request, Objects.requireNonNull(newBody));
                    }
                } catch (Exception e) {
                    // В случае ошибки парсинга логируем и отправляем оригинальное тело, 
                    // чтобы не маскировать другие проблемы сети/формата
                    log.error("⚠️ Spring AI Workaround: Failed to patch extra_body JSON: {}", e.getMessage());
                }
            }
            // Если условия не совпали, отправляем запрос без изменений
            // The framework already guarantees non-null inputs, but static nullness analysis is conservative here.
            return execution.execute(request, Objects.requireNonNull(body));
        }
    }
}

