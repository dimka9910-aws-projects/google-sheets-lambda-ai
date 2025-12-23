package com.github.dimka9910.sheets.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * AWS clients configuration for Spring Boot Lambda.
 * 
 * All AWS clients are managed by Spring DI.
 */
@Slf4j
@Configuration
public class AwsConfig {

    @Value("${AWS_REGION:eu-central-1}")
    private String awsRegion;

    @Bean
    public ObjectMapper objectMapper() {
        log.info("🔌 Initializing ObjectMapper");
        return new ObjectMapper();
    }

    @Bean
    public HttpClient httpClient() {
        log.info("🔌 Initializing HttpClient for LLM API calls");
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(60))
                .build();
    }

    @Bean
    public SqsClient sqsClient() {
        log.info("🔌 Initializing SQS client for region: {}", awsRegion);
        return SqsClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}

