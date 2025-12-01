package com.github.dimka9910.sheets.ai;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimka9910.sheets.ai.controller.UsersController;
import com.github.dimka9910.sheets.ai.dto.ChatRequest;
import com.github.dimka9910.sheets.ai.dto.ChatResponse;
import com.github.dimka9910.sheets.ai.services.ChatCommandService;
import com.github.dimka9910.sheets.ai.services.UserContextService;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class GoogleSheetsLambdaFunction implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Map<String, String> CORS_HEADERS = Map.of(
            "Content-Type", "application/json",
            "Access-Control-Allow-Origin", "*",
            "Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS",
            "Access-Control-Allow-Headers", "Content-Type,Authorization"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatCommandService chatCommandService;
    private final UsersController usersController;

    public GoogleSheetsLambdaFunction() {
        UserContextService userContextService = new UserContextService();
        this.chatCommandService = new ChatCommandService(userContextService);
        this.usersController = new UsersController(userContextService, objectMapper);
    }

    // Для тестирования
    public GoogleSheetsLambdaFunction(ChatCommandService chatCommandService, UsersController usersController) {
        this.chatCommandService = chatCommandService;
        this.usersController = usersController;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        String path = request.getPath();
        String method = request.getHttpMethod();
        
        log.info("Received {} {}", method, path);

        // CORS preflight
        if ("OPTIONS".equals(method)) {
            return createCorsResponse();
        }

        try {
            // Route to appropriate handler
            return route(request, path, method);
        } catch (Exception e) {
            log.error("Error processing request: {}", e.getMessage(), e);
            return createErrorResponse(500, "Internal server error: " + e.getMessage());
        }
    }

    private APIGatewayProxyResponseEvent route(APIGatewayProxyRequestEvent request, String path, String method) {
        Map<String, String> pathParams = request.getPathParameters();
        
        // POST /parse - парсинг команд
        if ("POST".equals(method) && "/parse".equals(path)) {
            return handleParseCommand(request);
        }
        
        // /users endpoints
        if (path != null && path.startsWith("/users")) {
            return routeUsersApi(request, path, method, pathParams);
        }
        
        // Unknown endpoint
        return createErrorResponse(404, "Not found: " + method + " " + path);
    }

    private APIGatewayProxyResponseEvent routeUsersApi(
            APIGatewayProxyRequestEvent request, 
            String path, 
            String method,
            Map<String, String> pathParams) {
        
        String userId = pathParams != null ? pathParams.get("userId") : null;
        
        if (userId == null) {
            return createErrorResponse(400, "Missing userId in path");
        }
        
        // GET /users/{userId}
        if ("GET".equals(method) && path.matches("/users/[^/]+$")) {
            return usersController.getUser(request, userId);
        }
        
        // PUT /users/{userId}
        if ("PUT".equals(method) && path.matches("/users/[^/]+$")) {
            return usersController.putUser(request, userId);
        }
        
        // PATCH /users/{userId}/defaults
        if ("PATCH".equals(method) && path.endsWith("/defaults")) {
            return usersController.patchDefaults(request, userId);
        }
        
        // PATCH /users/{userId}/telegram
        if ("PATCH".equals(method) && path.endsWith("/telegram")) {
            return usersController.patchTelegram(request, userId);
        }
        
        // POST /users/{userId}/instructions
        if ("POST".equals(method) && path.endsWith("/instructions")) {
            return usersController.addInstruction(request, userId);
        }
        
        // DELETE /users/{userId}/instructions/{index}
        if ("DELETE".equals(method) && path.contains("/instructions/")) {
            String index = pathParams.get("index");
            if (index != null) {
                try {
                    return usersController.deleteInstruction(request, userId, Integer.parseInt(index));
                } catch (NumberFormatException e) {
                    return createErrorResponse(400, "Invalid index: " + index);
                }
            }
        }
        
        // POST /users/{userId}/accounts
        if ("POST".equals(method) && path.endsWith("/accounts")) {
            return usersController.addAccount(request, userId);
        }
        
        // POST /users/{userId}/funds
        if ("POST".equals(method) && path.endsWith("/funds")) {
            return usersController.addFund(request, userId);
        }
        
        return createErrorResponse(404, "Unknown users endpoint: " + method + " " + path);
    }

    private APIGatewayProxyResponseEvent handleParseCommand(APIGatewayProxyRequestEvent request) {
        try {
            log.info("Parsing command from body: {}", request.getBody());
            ChatRequest chatRequest = objectMapper.readValue(request.getBody(), ChatRequest.class);
            ChatResponse response = chatCommandService.processCommand(chatRequest);
            return createResponse(200, response);
        } catch (Exception e) {
            log.error("Error parsing command: {}", e.getMessage(), e);
            return createErrorResponse(400, "Invalid request: " + e.getMessage());
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
            return createErrorResponse(500, "Internal server error");
        }
    }

    private APIGatewayProxyResponseEvent createErrorResponse(int statusCode, String message) {
        try {
            ChatResponse errorResponse = ChatResponse.builder()
                    .success(false)
                    .message(message)
                    .build();
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(statusCode)
                    .withHeaders(CORS_HEADERS)
                    .withBody(objectMapper.writeValueAsString(errorResponse));
        } catch (Exception e) {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withHeaders(CORS_HEADERS)
                    .withBody("{\"success\": false, \"message\": \"Internal server error\"}");
        }
    }

    private APIGatewayProxyResponseEvent createCorsResponse() {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withHeaders(CORS_HEADERS)
                .withBody("");
    }
}
