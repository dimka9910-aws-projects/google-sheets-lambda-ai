package com.github.dimka9910.sheets.ai.handler;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimka9910.sheets.ai.dto.telegram.ChatRequest;
import com.github.dimka9910.sheets.ai.services.ChatCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * Spring Cloud Function handler for processing SQS messages in AWS Lambda.
 * 
 * This function is automatically invoked by Spring Cloud Function AWS Adapter
 * when SQS events arrive at the Lambda function.
 */
@Slf4j
@Component("sqsMessageHandler")
@RequiredArgsConstructor
public class SqsMessageHandler implements Function<SQSEvent, Void> {

    private final ChatCommandService chatCommandService;
    private final ObjectMapper objectMapper;

    @Override
    public Void apply(SQSEvent sqsEvent) {
        log.info("📥 Received {} SQS messages", sqsEvent.getRecords().size());
        
        for (SQSEvent.SQSMessage message : sqsEvent.getRecords()) {
            try {
                String body = message.getBody();
                log.debug("📨 Processing message: {}", body);
                
                ChatRequest request = objectMapper.readValue(body, ChatRequest.class);
                
                // Process command (response sent via SQS inside)
                chatCommandService.processCommand(request);
                
                log.info("✅ Successfully processed message: {}", message.getMessageId());
            } catch (Exception e) {
                log.error("❌ Error processing SQS message: {}", message.getMessageId(), e);
                // Lambda will retry the message automatically if we throw an exception
                throw new RuntimeException("Failed to process SQS message", e);
            }
        }
        
        return null;
    }
}

