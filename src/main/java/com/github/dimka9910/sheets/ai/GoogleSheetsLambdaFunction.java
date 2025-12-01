package com.github.dimka9910.sheets.ai;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimka9910.sheets.ai.dto.ChatRequest;
import com.github.dimka9910.sheets.ai.dto.ChatResponse;
import com.github.dimka9910.sheets.ai.services.ChatCommandService;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class GoogleSheetsLambdaFunction implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Map<String, String> CORS_HEADERS = Map.of(
            "Content-Type", "application/json",
            "Access-Control-Allow-Origin", "*"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatCommandService chatCommandService;

    public GoogleSheetsLambdaFunction() {
        this.chatCommandService = new ChatCommandService();
    }

    public GoogleSheetsLambdaFunction(ChatCommandService chatCommandService) {
        this.chatCommandService = chatCommandService;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        log.info("Received request: {}", request.getBody());

        try {
            ChatRequest chatRequest = objectMapper.readValue(request.getBody(), ChatRequest.class);
            ChatResponse response = chatCommandService.processCommand(chatRequest);
            return createResponse(200, response);

        } catch (Exception e) {
            log.error("Error processing request: {}", e.getMessage(), e);
            return createErrorResponse(e.getMessage());
        }
    }

    private APIGatewayProxyResponseEvent createResponse(int statusCode, Object body) {
        try {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(statusCode)
                    .withHeaders(CORS_HEADERS)
                    .withBody(objectMapper.writeValueAsString(body));
        } catch (Exception e) {
            log.error("Failed to serialize response", e);
            return createErrorResponse("Internal server error");
        }
    }

    private APIGatewayProxyResponseEvent createErrorResponse(String message) {
        ChatResponse errorResponse = ChatResponse.builder()
                .success(false)
                .message("Произошла ошибка: " + message)
                .build();

        try {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withHeaders(CORS_HEADERS)
                    .withBody(objectMapper.writeValueAsString(errorResponse));
        } catch (Exception e) {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withHeaders(CORS_HEADERS)
                    .withBody("{\"success\": false, \"message\": \"Internal server error\"}");
        }
    }
}
