package com.github.dimka9910.sheets.ai.controller;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimka9910.sheets.ai.dto.UserContext;
import com.github.dimka9910.sheets.ai.services.UserContextService;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Контроллер для /users/* API endpoints
 */
@Slf4j
public class UsersController {

    private final UserContextService userContextService;
    private final ObjectMapper objectMapper;

    public UsersController(UserContextService userContextService, ObjectMapper objectMapper) {
        this.userContextService = userContextService;
        this.objectMapper = objectMapper;
    }

    /**
     * GET /users/{userId} - получить контекст пользователя
     */
    public APIGatewayProxyResponseEvent getUser(APIGatewayProxyRequestEvent request, String userId) {
        log.info("GET user context for userId: {}", userId);
        
        UserContext context = userContextService.getContext(userId);
        return createJsonResponse(200, context);
    }

    /**
     * PUT /users/{userId} - создать/обновить полный контекст
     */
    public APIGatewayProxyResponseEvent putUser(APIGatewayProxyRequestEvent request, String userId) {
        log.info("PUT user context for userId: {}", userId);
        
        try {
            UserContext context = objectMapper.readValue(request.getBody(), UserContext.class);
            context.setUserId(userId); // Убедимся что userId совпадает
            userContextService.saveContext(context);
            return createJsonResponse(200, Map.of(
                    "success", true,
                    "message", "User context saved"
            ));
        } catch (Exception e) {
            log.error("Error saving user context: {}", e.getMessage(), e);
            return createErrorResponse(400, "Invalid request body: " + e.getMessage());
        }
    }

    /**
     * PATCH /users/{userId}/defaults - обновить дефолтные значения
     */
    public APIGatewayProxyResponseEvent patchDefaults(APIGatewayProxyRequestEvent request, String userId) {
        log.info("PATCH defaults for userId: {}", userId);
        
        try {
            JsonNode body = objectMapper.readTree(request.getBody());
            
            if (body.has("defaultCurrency")) {
                userContextService.setDefaultCurrency(userId, body.get("defaultCurrency").asText());
            }
            if (body.has("defaultAccount")) {
                userContextService.setDefaultAccount(userId, body.get("defaultAccount").asText());
            }
            if (body.has("defaultPersonalFund")) {
                userContextService.setDefaultPersonalFund(userId, body.get("defaultPersonalFund").asText());
            }
            if (body.has("defaultSharedFund")) {
                userContextService.setDefaultSharedFund(userId, body.get("defaultSharedFund").asText());
            }
            
            return createJsonResponse(200, Map.of(
                    "success", true,
                    "message", "Defaults updated"
            ));
        } catch (Exception e) {
            log.error("Error updating defaults: {}", e.getMessage(), e);
            return createErrorResponse(400, "Invalid request body: " + e.getMessage());
        }
    }

    /**
     * PATCH /users/{userId}/telegram - привязать Telegram ID
     */
    public APIGatewayProxyResponseEvent patchTelegram(APIGatewayProxyRequestEvent request, String userId) {
        log.info("PATCH telegram for userId: {}", userId);
        
        try {
            JsonNode body = objectMapper.readTree(request.getBody());
            String telegramId = body.get("telegramId").asText();
            
            userContextService.linkTelegram(userId, telegramId);
            
            return createJsonResponse(200, Map.of(
                    "success", true,
                    "message", "Telegram linked"
            ));
        } catch (Exception e) {
            log.error("Error linking telegram: {}", e.getMessage(), e);
            return createErrorResponse(400, "Invalid request body: " + e.getMessage());
        }
    }

    /**
     * POST /users/{userId}/instructions - добавить инструкцию
     */
    public APIGatewayProxyResponseEvent addInstruction(APIGatewayProxyRequestEvent request, String userId) {
        log.info("POST instruction for userId: {}", userId);
        
        try {
            JsonNode body = objectMapper.readTree(request.getBody());
            String instruction = body.get("instruction").asText();
            
            userContextService.addInstruction(userId, instruction);
            
            return createJsonResponse(201, Map.of(
                    "success", true,
                    "message", "Instruction added"
            ));
        } catch (Exception e) {
            log.error("Error adding instruction: {}", e.getMessage(), e);
            return createErrorResponse(400, "Invalid request body: " + e.getMessage());
        }
    }

    /**
     * DELETE /users/{userId}/instructions/{index} - удалить инструкцию
     */
    public APIGatewayProxyResponseEvent deleteInstruction(APIGatewayProxyRequestEvent request, String userId, int index) {
        log.info("DELETE instruction {} for userId: {}", index, userId);
        
        try {
            userContextService.removeInstruction(userId, index);
            
            return createJsonResponse(200, Map.of(
                    "success", true,
                    "message", "Instruction removed"
            ));
        } catch (Exception e) {
            log.error("Error deleting instruction: {}", e.getMessage(), e);
            return createErrorResponse(400, "Error: " + e.getMessage());
        }
    }

    /**
     * POST /users/{userId}/accounts - добавить счёт
     */
    public APIGatewayProxyResponseEvent addAccount(APIGatewayProxyRequestEvent request, String userId) {
        log.info("POST account for userId: {}", userId);
        
        try {
            JsonNode body = objectMapper.readTree(request.getBody());
            String account = body.get("account").asText();
            
            userContextService.addAccount(userId, account);
            
            return createJsonResponse(201, Map.of(
                    "success", true,
                    "message", "Account added"
            ));
        } catch (Exception e) {
            log.error("Error adding account: {}", e.getMessage(), e);
            return createErrorResponse(400, "Invalid request body: " + e.getMessage());
        }
    }

    /**
     * POST /users/{userId}/funds - добавить фонд
     */
    public APIGatewayProxyResponseEvent addFund(APIGatewayProxyRequestEvent request, String userId) {
        log.info("POST fund for userId: {}", userId);
        
        try {
            JsonNode body = objectMapper.readTree(request.getBody());
            String fund = body.get("fund").asText();
            
            userContextService.addFund(userId, fund);
            
            return createJsonResponse(201, Map.of(
                    "success", true,
                    "message", "Fund added"
            ));
        } catch (Exception e) {
            log.error("Error adding fund: {}", e.getMessage(), e);
            return createErrorResponse(400, "Invalid request body: " + e.getMessage());
        }
    }

    // ========== Helpers ==========

    private APIGatewayProxyResponseEvent createJsonResponse(int statusCode, Object body) {
        try {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(statusCode)
                    .withHeaders(Map.of(
                            "Content-Type", "application/json",
                            "Access-Control-Allow-Origin", "*"
                    ))
                    .withBody(objectMapper.writeValueAsString(body));
        } catch (Exception e) {
            log.error("Error serializing response: {}", e.getMessage());
            return createErrorResponse(500, "Internal error");
        }
    }

    private APIGatewayProxyResponseEvent createErrorResponse(int statusCode, String message) {
        try {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(statusCode)
                    .withHeaders(Map.of(
                            "Content-Type", "application/json",
                            "Access-Control-Allow-Origin", "*"
                    ))
                    .withBody(objectMapper.writeValueAsString(Map.of(
                            "success", false,
                            "message", message
                    )));
        } catch (Exception e) {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withBody("{\"success\":false,\"message\":\"Internal error\"}");
        }
    }
}

