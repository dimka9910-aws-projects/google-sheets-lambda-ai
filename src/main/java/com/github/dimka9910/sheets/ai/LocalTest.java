package com.github.dimka9910.sheets.ai;

import com.github.dimka9910.sheets.ai.dto.ParsedCommand;
import com.github.dimka9910.sheets.ai.dto.UserContext;
import com.github.dimka9910.sheets.ai.services.AICommandParser;

import java.util.List;

/**
 * Класс для локального тестирования AI парсера.
 * 
 * Перед запуском:
 * 1. Заполни openai.api.key в src/main/resources/application.properties
 * 2. Запусти: mvn compile exec:java -Dexec.mainClass="com.github.dimka9910.sheets.ai.LocalTest"
 */
public class LocalTest {

    public static void main(String[] args) {
        AICommandParser parser = new AICommandParser();

        System.out.println("=== Тест 1: Базовый парсинг (без контекста) ===\n");
        testBasicParsing(parser);

        System.out.println("\n=== Тест 2: Парсинг с контекстом (путешествие, EUR) ===\n");
        testWithTravelContext(parser);

        System.out.println("\n=== Тест 3: Парсинг с кастомными указаниями ===\n");
        testWithCustomInstructions(parser);
    }

    private static void testBasicParsing(AICommandParser parser) {
        String[] messages = {
                "Потратил 500 рублей на кофе с Тинькофф",
                "Получил зарплату 150000р на Сбер",
                "Купил продукты 2500р"
        };

        for (String message : messages) {
            parseAndPrint(parser, message, null);
        }
    }

    private static void testWithTravelContext(AICommandParser parser) {
        // Контекст: пользователь в путешествии, валюта по умолчанию EUR
        UserContext travelContext = UserContext.builder()
                .userId("test-user")
                .defaultCurrency("EUR")
                .defaultAccount("Тинькофф")
                .accounts(List.of("Тинькофф", "Revolut", "Наличные EUR"))
                .customInstructions(List.of(
                        "Я сейчас в путешествии по Европе",
                        "Все траты без указания валюты считай в евро",
                        "Категория 'кафе' и 'ресторан' = Путешествия"
                ))
                .build();

        String[] messages = {
                "Потратил 25 на обед",           // Должен понять как 25 EUR
                "Кофе 4.50",                      // 4.50 EUR
                "Такси 15 евро",                  // Явно EUR
                "Перевёл 100$ на Revolut"         // USD явно указан
        };

        for (String message : messages) {
            parseAndPrint(parser, message, travelContext);
        }
    }

    private static void testWithCustomInstructions(AICommandParser parser) {
        // Контекст: кастомные категории и счета
        UserContext customContext = UserContext.builder()
                .userId("test-user-2")
                .defaultCurrency("RUB")
                .defaultAccount("Альфа")
                .accounts(List.of("Альфа", "Тинькофф", "Крипто-кошелёк"))
                .customInstructions(List.of(
                        "Яндекс.Еда и Деливери = категория 'Доставка еды'",
                        "Spotify и YouTube Premium = категория 'Подписки'",
                        "Все покупки в Steam = категория 'Игры'"
                ))
                .funds(List.of("Доставка еды", "Подписки", "Игры", "Крипта"))
                .build();

        String[] messages = {
                "Заказал еду в Яндекс.Еде 1200р",
                "Списался Spotify 199р",
                "Купил игру в Steam 2500"
        };

        for (String message : messages) {
            parseAndPrint(parser, message, customContext);
        }
    }

    private static void parseAndPrint(AICommandParser parser, String message, UserContext context) {
        System.out.println("📝 Input: " + message);

        try {
            ParsedCommand result = context != null
                    ? parser.parse(message, context)
                    : parser.parse(message);

            if (result.isUnderstood()) {
                System.out.println("✅ Parsed:");
                System.out.println("   Type: " + result.getOperationType());
                System.out.println("   Amount: " + result.getAmount() + " " + result.getCurrency());
                System.out.println("   Account: " + result.getAccountName());
                System.out.println("   Category: " + result.getFundName());
                if (result.getComment() != null) {
                    System.out.println("   Comment: " + result.getComment());
                }
            } else {
                System.out.println("❌ Not understood: " + 
                        (result.getClarification() != null ? result.getClarification() : result.getErrorMessage()));
            }

        } catch (Exception e) {
            System.out.println("❌ Error: " + e.getMessage());
        }

        System.out.println();
    }
}
