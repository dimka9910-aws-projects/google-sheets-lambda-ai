package com.github.dimka9910.sheets.ai.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimka9910.sheets.ai.config.AppConfig;
import com.github.dimka9910.sheets.ai.dto.ChatResponse;
import com.github.dimka9910.sheets.ai.dto.SheetsRecordDTO;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

@Slf4j
public class SQSPublisher {

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final String sheetsQueueUrl;
    private final String responseQueueUrl;

    public SQSPublisher() {
        this.sqsClient = SqsClient.builder()
                .region(Region.of(AppConfig.getAwsRegion()))
                .build();
        this.objectMapper = new ObjectMapper();

        // URL очередей из конфига
        this.sheetsQueueUrl = AppConfig.getSheetsQueueUrl();
        this.responseQueueUrl = AppConfig.getResponseQueueUrl();

        if (sheetsQueueUrl == null || sheetsQueueUrl.isBlank()) {
            log.warn("SHEETS_QUEUE_URL is not set - messages won't be sent to sheets-lambda");
        }
        if (responseQueueUrl == null || responseQueueUrl.isBlank()) {
            log.warn("RESPONSE_QUEUE_URL is not set - responses won't be sent back");
        }
    }

    // Конструктор для тестирования
    public SQSPublisher(SqsClient sqsClient, String sheetsQueueUrl, String responseQueueUrl) {
        this.sqsClient = sqsClient;
        this.objectMapper = new ObjectMapper();
        this.sheetsQueueUrl = sheetsQueueUrl;
        this.responseQueueUrl = responseQueueUrl;
    }

    /**
     * Отправляет команду в очередь google-sheets-lambda
     * 
     * DRY_RUN=true — только логирование, без реальной отправки
     */
    public void sendToSheetsLambda(SheetsRecordDTO record) {
        try {
            String messageBody = objectMapper.writeValueAsString(record);
            
            // DRY_RUN режим — только логируем, не отправляем в SQS
            if (isDryRun()) {
                log.info("[DRY_RUN] Would send to sheets-lambda: {}", messageBody);
                return;
            }
            
            if (sheetsQueueUrl == null) {
                log.error("Cannot send to sheets-lambda: SHEETS_QUEUE_URL is not set");
                return;
            }

            log.info("Sending to sheets-lambda: {}", messageBody);

            SendMessageRequest request = SendMessageRequest.builder()
                    .queueUrl(sheetsQueueUrl)
                    .messageBody(messageBody)
                    .build();

            SendMessageResponse response = sqsClient.sendMessage(request);
            log.info("Message sent to sheets-lambda, messageId: {}", response.messageId());

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize record: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to serialize record", e);
        }
    }
    
    private boolean isDryRun() {
        String dryRun = System.getenv("DRY_RUN");
        return "true".equalsIgnoreCase(dryRun) || "1".equals(dryRun);
    }

    /**
     * Отправляет ответ в очередь для Telegram бота
     */
    public void sendResponse(ChatResponse response) {
        if (responseQueueUrl == null) {
            log.error("Cannot send response: RESPONSE_QUEUE_URL is not set");
            return;
        }

        try {
            String messageBody = objectMapper.writeValueAsString(response);
            log.info("Sending response: {}", messageBody);

            SendMessageRequest request = SendMessageRequest.builder()
                    .queueUrl(responseQueueUrl)
                    .messageBody(messageBody)
                    .build();

            SendMessageResponse sqsResponse = sqsClient.sendMessage(request);
            log.info("Response sent, messageId: {}", sqsResponse.messageId());

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize response: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to serialize response", e);
        }
    }
}

