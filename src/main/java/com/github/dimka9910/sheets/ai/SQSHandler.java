package com.github.dimka9910.sheets.ai;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.github.dimka9910.sheets.ai.handler.SqsMessageHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * AWS Lambda handler for SQS messages from Telegram Bot.
 * 
 * This class serves as the entry point for AWS Lambda and delegates
 * to Spring Cloud Function for actual processing.
 * 
 * Handler in template.yaml should point to this class:
 * Handler: com.github.dimka9910.sheets.ai.SQSHandler::handleRequest
 */
public class SQSHandler implements RequestHandler<SQSEvent, Void> {
    
    private static ConfigurableApplicationContext applicationContext;
    private static SqsMessageHandler sqsMessageHandler;
    
    static {
        // Initialize Spring Boot context once (Lambda container reuse)
        applicationContext = SpringApplication.run(FinanceTrackerApplication.class);
        sqsMessageHandler = applicationContext.getBean(SqsMessageHandler.class);
    }
    
    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        // Delegate to Spring Cloud Function handler
        return sqsMessageHandler.apply(event);
    }
}


