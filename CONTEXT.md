# 🧠 Project Context (для AI ассистента)

> **Последнее обновление: 2025-12-04 14:30 CET**
> 
> ⚠️ **AI: ЧИТАЙ ЭТОТ ФАЙЛ И ROADMAP.md В НАЧАЛЕ КАЖДОЙ СЕССИИ!**
> Это твои заметки для себя. Context window не бесконечен — здесь всё важное.

## Что это за проект

**Finance Tracker** — система учёта личных финансов с AI-интерфейсом.

**Владелец:** Дима (@dimka9910)
**Второй пользователь:** Настя (девушка, совместные финансы)

## Архитектура

```
Telegram/Shortcut → AI Parser Lambda → SQS → Sheets Lambda → Google Sheets
                         ↓
                    DynamoDB (user context + conversation history)
```

### Репозитории:

| Репо | Назначение | Ветки |
|------|------------|-------|
| `google-sheets-lambda` | Пишет в Google Sheets | develop → dev, main → prod |
| `google-sheets-lambda-ai` | AI парсер команд (GPT-4o-mini) | develop → dev, main → prod |

### AWS ресурсы (eu-central-1):

**API Endpoint:** `https://3kfpcxra5m.execute-api.eu-central-1.amazonaws.com/Prod`

**Lambda/SQS/DynamoDB:**
- `google-sheets-ai-parser-dev` — AI парсер Lambda ✅
- `finance-tracker-users-dev` — DynamoDB ✅
- `telegram-finance-bot-dev` — Telegram bot Lambda ✅

## ⚠️ КРИТИЧНО ДЛЯ AI — НЕ ЗАБЫВАТЬ!

### 🔴 1. КОММИТИТЬ И ПУШИТЬ ИЗМЕНЕНИЯ!
- ❌ **НЕ ЗАБЫВАТЬ** делать `git add`, `git commit`, `git push` после изменений!
- ✅ CI/CD в GitHub Actions автоматически деплоит при пуше в develop
- ✅ После локального `sam deploy` всё равно закоммитить!
- Telegram бот (`telegram-bot-lambda`) — отдельный проект, НЕ в git

### 🔴 2. НИКОГДА НЕ ХАРДКОДИТЬ СООБЩЕНИЯ!
- ❌ **ЗАПРЕЩЕНО** писать `.message("Какой-то текст")` на любом языке
- ❌ **ЗАПРЕЩЕНО** делать `if (lang == "ru") ... else ...`
- ✅ ВСЕ сообщения генерирует AI модель
- ✅ AI сам определяет язык и отвечает на нём

### 🔴 3. НИКАКОГО REGEX ДЛЯ КОМАНД!
- ❌ **ЗАПРЕЩЕНО** `Pattern.compile("(запомни|remember)")`
- ✅ AI возвращает `metaCommand` в JSON
- ✅ Промпт-инструкции для понимания команд на ЛЮБОМ языке

### 🔴 4. МОДЕЛЬ: GPT-4O-MINI!
- ❌ НЕ использовать gpt-5-mini (это reasoning модель, тратит много токенов на thinking)
- ✅ gpt-4o-mini — оптимально для парсинга

### 🔴 5. OpenAI API ключ
- **Локально:** `google-sheets-lambda-ai/src/main/resources/application.properties`
- **AWS:** через GitHub Secrets → SAM parameter

## Текущий статус (2025-12-04)

### ✅ ВСЁ РАБОТАЕТ:
- Telegram бот (@FinTrackSheets_bot)
- AI парсинг команд (multi-command, multi-language)
- Онбординг новых пользователей
- Custom instructions (добавление, удаление, применение)
- Meta commands (SHOW_SETTINGS, ADD_ACCOUNT, ADD_FUND, REMOVE_INSTRUCTION, etc.)
- Admin mode (/debug, /reset, /note, /info)
- Undo/Edit операций

### ✅ Исправлено сегодня (2025-12-04):
- Дефолты больше не ставятся автоматически — AI спрашивает
- Регистр счетов/фондов: всё в UPPER_CASE
- Фонды транслитерируются в английский (еда → FOOD)
- REMOVE_INSTRUCTION для удаления противоречащих инструкций
- Custom instructions применяются при парсинге (рубли=BYN, умножай на 2)
- Telegram бот: убран Markdown (plain text) — фикс ошибки с underscore

## Ключевые файлы

```
google-sheets-lambda-ai/src/main/java/com/github/dimka9910/sheets/ai/
├── services/
│   ├── ChatCommandService.java   ← главный + Admin Mode + metaCommand handling
│   ├── OnboardingService.java    ← AI-driven онбординг
│   ├── PromptBuilder.java        ← промпт с контекстом + инструкции AI
│   ├── AICommandParser.java      ← вызов OpenAI (gpt-4o-mini)
│   └── UserContextService.java   ← DynamoDB
├── dto/
│   ├── UserContext.java          ← контекст + customInstructions + debugMode
│   ├── ParsedCommandList.java    ← команды + metaCommand
│   └── OnboardingState.java      ← enum состояний

telegram-bot-lambda/
└── TelegramBotHandler.java       ← webhook → AI Parser → Telegram (plain text!)
```

## Деплой

```bash
# AI Parser Lambda (из google-sheets-lambda-ai/)
cd google-sheets-lambda-ai
sam build && sam deploy --stack-name google-sheets-ai-parser-dev ...

# Telegram Bot (из telegram-bot-lambda/)
cd telegram-bot-lambda  
sam build && sam deploy --stack-name telegram-finance-bot-dev ...

# ПОСЛЕ ЛЮБОГО ДЕПЛОЯ — КОММИТ!
git add -A && git commit -m "description" && git push origin develop
```

## Meta Commands (AI возвращает в JSON)

| Type | Описание |
|------|----------|
| SHOW_SETTINGS | показать настройки |
| ADD_ACCOUNT | добавить счёт |
| ADD_FUND | добавить фонд |
| ADD_INSTRUCTION | добавить инструкцию |
| REMOVE_INSTRUCTION | удалить инструкцию (по индексу) |
| SET_DEFAULT_CURRENCY | установить валюту |
| CLEAR_INSTRUCTIONS | очистить все инструкции |
| UNDO | отменить последнее |
| HELP | помощь |

## Admin Commands (hardcoded в TelegramBotHandler)

- `/info` — список команд
- `/debug on|off` — показать внутренние данные
- `/reset` — удалить юзера
- `/note TEXT` — заметка в логи

## Следующие шаги

См. `ROADMAP.md` — там тикеты F-UX-xx и F-OPS-xx

---

*⚠️ AI: Обновляй этот файл после значимых изменений. НЕ ЗАБЫВАЙ КОММИТИТЬ!*
