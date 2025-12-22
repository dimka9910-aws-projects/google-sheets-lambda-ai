package com.github.dimka9910.sheets.ai;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimka9910.sheets.ai.dto.telegram.ChatRequest;
import com.github.dimka9910.sheets.ai.services.SqsMessageProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * AWS Lambda handler for SQS messages from Telegram Bot.
 * 
 * Entry point for AWS Lambda with Spring Boot integration.
 * Processes SQS events by parsing messages and delegating to ChatCommandService.
 * 
 * Handler in template.yaml: com.github.dimka9910.sheets.ai.SQSHandler::handleRequest
 */
public class SQSHandler implements RequestHandler<SQSEvent, Void> {
    
    private static final Logger log = LoggerFactory.getLogger(SQSHandler.class);
    
    private static ConfigurableApplicationContext applicationContext;
    private static SqsMessageProcessor sqsMessageProcessor;
    private static ObjectMapper objectMapper;
    
    static {
        // Initialize Spring Boot context once (Lambda container reuse)
        applicationContext = SpringApplication.run(FinanceTrackerApplication.class);
        sqsMessageProcessor = applicationContext.getBean(SqsMessageProcessor.class);
        objectMapper = applicationContext.getBean(ObjectMapper.class);
        log.info("✅ SQSHandler initialized with Spring Boot context");
    }
    
    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        log.info("📥 Received {} SQS messages", event.getRecords().size());
        
        for (SQSEvent.SQSMessage message : event.getRecords()) {
            try {
                String body = message.getBody();
                log.debug("📨 Processing message: {}", body);
                
                ChatRequest request = objectMapper.readValue(body, ChatRequest.class);
                
                // Process command (response sent via SQS inside)
                sqsMessageProcessor.processCommand(request);
                
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


