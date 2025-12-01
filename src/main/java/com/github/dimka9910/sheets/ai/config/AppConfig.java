package com.github.dimka9910.sheets.ai.config;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Конфигурация приложения.
 * Читает из application.properties, но переменные окружения имеют приоритет.
 */
@Slf4j
public class AppConfig {

    private static final Properties properties = new Properties();
    private static boolean loaded = false;

    static {
        loadProperties();
    }

    private static void loadProperties() {
        if (loaded) return;

        try (InputStream input = AppConfig.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (input != null) {
                properties.load(input);
                log.info("Loaded application.properties");
            } else {
                log.warn("application.properties not found, using environment variables only");
            }
        } catch (IOException e) {
            log.warn("Failed to load application.properties: {}", e.getMessage());
        }
        loaded = true;
    }

    /**
     * Получает значение конфига. Приоритет:
     * 1. Переменная окружения (ENV_VAR_NAME)
     * 2. Значение из application.properties
     */
    public static String get(String propertyName, String envVarName) {
        // Сначала проверяем переменную окружения
        String envValue = System.getenv(envVarName);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }

        // Затем из properties файла
        String propValue = properties.getProperty(propertyName);
        if (propValue != null && !propValue.isBlank() && !propValue.contains("your-key")) {
            return propValue;
        }

        return null;
    }

    public static String getOpenAiApiKey() {
        return get("openai.api.key", "OPENAI_API_KEY");
    }

    public static String getSheetsQueueUrl() {
        return get("sqs.sheets.queue.url", "SHEETS_QUEUE_URL");
    }

    public static String getResponseQueueUrl() {
        return get("sqs.response.queue.url", "RESPONSE_QUEUE_URL");
    }

    public static String getAwsRegion() {
        String region = get("aws.region", "AWS_REGION");
        return region != null ? region : "eu-central-1";
    }
}

