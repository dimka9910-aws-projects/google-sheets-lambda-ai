package com.github.dimka9910.sheets.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/**
 * Spring Boot Application for Finance Tracker AI Lambda.
 * 
 * This application uses Spring Cloud Function to handle SQS events in AWS Lambda.
 * The handler is configured in application.yml and points to the SqsMessageHandler bean.
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.github.dimka9910.sheets.ai")
public class FinanceTrackerApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinanceTrackerApplication.class, args);
    }
}

