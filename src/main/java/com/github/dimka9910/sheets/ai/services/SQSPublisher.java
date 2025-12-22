package com.github.dimka9910.sheets.ai.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimka9910.sheets.ai.dto.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

/**
 * Spring Service for sending messages to SQS queues.
 * Uses Spring DI for SqsClient and configuration values.
 */
@Slf4j
@Service
public class SQSPublisher {

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final String responseQueueUrl;

    public SQSPublisher(
            SqsClient sqsClient,
            ObjectMapper objectMapper,
            @Value("${RESPONSE_QUEUE_URL:}") String responseQueueUrl) {
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
        this.responseQueueUrl = responseQueueUrl;

        if (responseQueueUrl == null || responseQueueUrl.isBlank()) {
            log.warn("⚠️ RESPONSE_QUEUE_URL is not set - responses won't be sent back");
        }
        
        log.info("✅ SQSPublisher initialized");
    }

    /**
     * Send response to Telegram bot via SQS response queue.
     */
    public void sendResponse(ChatResponse response) {
        if (responseQueueUrl == null || responseQueueUrl.isBlank()) {
            log.error("❌ Cannot send response: RESPONSE_QUEUE_URL is not set");
            return;
        }

        try {
            String messageBody = objectMapper.writeValueAsString(response);
            
            log.info("📤 Sending response to Telegram bot");

            SendMessageRequest request = SendMessageRequest.builder()
                    .queueUrl(responseQueueUrl)
                    .messageBody(messageBody)
                    .build();

            SendMessageResponse sqsResponse = sqsClient.sendMessage(request);
            log.info("✅ Response sent, messageId: {}", sqsResponse.messageId());

        } catch (JsonProcessingException e) {
            log.error("❌ Failed to serialize response: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to serialize response", e);
        }
    }
}

