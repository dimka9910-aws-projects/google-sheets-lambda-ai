-- ═══════════════════════════════════════════════════════════════════════════
-- TEST DATA: DIMA and KIKI users with their accounts, funds, and linked relationship
-- Run this manually in DEV environment after migration
-- ═══════════════════════════════════════════════════════════════════════════

-- Clean up existing data (if any)
DELETE FROM chat_messages WHERE user_id IN (SELECT id FROM users WHERE username IN ('DIMA', 'KIKI'));
DELETE FROM linked_users WHERE owner_user_id IN (SELECT id FROM users WHERE username IN ('DIMA', 'KIKI'));
DELETE FROM accounts WHERE user_id IN (SELECT id FROM users WHERE username IN ('DIMA', 'KIKI'));
DELETE FROM funds WHERE user_id IN (SELECT id FROM users WHERE username IN ('DIMA', 'KIKI'));
DELETE FROM users WHERE username IN ('DIMA', 'KIKI');

-- ═══════════════════════════════════════════════════════════════════════════
-- USER: DIMA
-- ═══════════════════════════════════════════════════════════════════════════

-- Insert DIMA user (save UUID for later use)
INSERT INTO users (id, username, telegram_id, display_name, default_currency, preferred_language, ai_context, created_at)
VALUES (
    '11111111-1111-1111-1111-111111111111'::uuid,
    'DIMA',
    '377662506',
    'Дима',
    'RSD',
    'ru',
    '{
        "customInstructions": [
            "Ксюша = KIKI (linked user alias)"
        ]
    }'::jsonb,
    NOW()
);

-- DIMA's accounts
INSERT INTO accounts (id, user_id, external_id, display_name, aliases, created_at) VALUES
    ('11111111-0001-0000-0000-000000000001'::uuid, '11111111-1111-1111-1111-111111111111'::uuid, 'CARD_DIMA_VISA_RAIF', 'Visa Raiffeisen', ARRAY['виза', 'райф', 'основная карта'], NOW()),
    ('11111111-0001-0000-0000-000000000002'::uuid, '11111111-1111-1111-1111-111111111111'::uuid, 'CASH_DIMA', 'Наличные', ARRAY['cash', 'наличка'], NOW());

-- DIMA's funds
INSERT INTO funds (id, user_id, external_id, display_name, aliases, created_at) VALUES
    ('11111111-0002-0000-0000-000000000001'::uuid, '11111111-1111-1111-1111-111111111111'::uuid, 'FOOD', 'Еда', ARRAY['еда', 'продукты', 'groceries'], NOW()),
    ('11111111-0002-0000-0000-000000000002'::uuid, '11111111-1111-1111-1111-111111111111'::uuid, 'TRANSPORT', 'Транспорт', ARRAY['транспорт', 'дорога'], NOW()),
    ('11111111-0002-0000-0000-000000000003'::uuid, '11111111-1111-1111-1111-111111111111'::uuid, 'ENTERTAINMENT', 'Развлечения', ARRAY['развлечения', 'отдых'], NOW());

-- Update DIMA's default account and fund
UPDATE users 
SET default_account_id = '11111111-0001-0000-0000-000000000001'::uuid,
    default_fund_id = '11111111-0002-0000-0000-000000000001'::uuid
WHERE username = 'DIMA';

-- ═══════════════════════════════════════════════════════════════════════════
-- USER: KIKI
-- ═══════════════════════════════════════════════════════════════════════════

-- Insert KIKI user
INSERT INTO users (id, username, telegram_id, display_name, default_currency, preferred_language, ai_context, created_at)
VALUES (
    '22222222-2222-2222-2222-222222222222'::uuid,
    'KIKI',
    '526913915',
    'Ксюша',
    'RSD',
    'ru',
    '{
        "customInstructions": [
            "Дима = DIMA (linked user alias)"
        ]
    }'::jsonb,
    NOW()
);

-- KIKI's accounts
INSERT INTO accounts (id, user_id, external_id, display_name, aliases, created_at) VALUES
    ('22222222-0001-0000-0000-000000000001'::uuid, '22222222-2222-2222-2222-222222222222'::uuid, 'CARD_KIKI_MASTER', 'MasterCard', ARRAY['мастер', 'карта'], NOW()),
    ('22222222-0001-0000-0000-000000000002'::uuid, '22222222-2222-2222-2222-222222222222'::uuid, 'CASH_KIKI', 'Наличные', ARRAY['cash', 'наличка'], NOW());

-- KIKI's funds
INSERT INTO funds (id, user_id, external_id, display_name, aliases, created_at) VALUES
    ('22222222-0002-0000-0000-000000000001'::uuid, '22222222-2222-2222-2222-222222222222'::uuid, 'FOOD', 'Еда', ARRAY['еда', 'продукты'], NOW()),
    ('22222222-0002-0000-0000-000000000002'::uuid, '22222222-2222-2222-2222-222222222222'::uuid, 'SHOPPING', 'Покупки', ARRAY['шопинг', 'покупки'], NOW());

-- Update KIKI's default account and fund
UPDATE users 
SET default_account_id = '22222222-0001-0000-0000-000000000001'::uuid,
    default_fund_id = '22222222-0002-0000-0000-000000000001'::uuid
WHERE username = 'KIKI';

-- ═══════════════════════════════════════════════════════════════════════════
-- LINKED USERS (bidirectional relationship)
-- ═══════════════════════════════════════════════════════════════════════════

-- DIMA → KIKI link (DIMA видит KIKI как "Ксюша")
INSERT INTO linked_users (id, owner_user_id, target_user_id, display_name, aliases, created_at)
VALUES (
    '33333333-0001-0000-0000-000000000001'::uuid,
    '11111111-1111-1111-1111-111111111111'::uuid,  -- DIMA
    '22222222-2222-2222-2222-222222222222'::uuid,  -- KIKI
    'Ксюша',
    ARRAY['Kiki', 'Ксюш', 'девушка', 'girlfriend'],
    NOW()
);

-- KIKI → DIMA link (KIKI видит DIMA как "Дима")
INSERT INTO linked_users (id, owner_user_id, target_user_id, display_name, aliases, created_at)
VALUES (
    '33333333-0002-0000-0000-000000000001'::uuid,
    '22222222-2222-2222-2222-222222222222'::uuid,  -- KIKI
    '11111111-1111-1111-1111-111111111111'::uuid,  -- DIMA
    'Дима',
    ARRAY['Dima', 'парень', 'boyfriend'],
    NOW()
);

-- ═══════════════════════════════════════════════════════════════════════════
-- SAMPLE CHAT MESSAGES (optional - for testing conversation history)
-- ═══════════════════════════════════════════════════════════════════════════

INSERT INTO chat_messages (user_id, role, content, was_clarification, created_at) VALUES
    ('11111111-1111-1111-1111-111111111111'::uuid, 'user', 'кофе 200', false, NOW() - INTERVAL '2 hours'),
    ('11111111-1111-1111-1111-111111111111'::uuid, 'assistant', 'Записал расход 200 RSD на FOOD (кофе) с CARD_DIMA_VISA_RAIF', false, NOW() - INTERVAL '2 hours'),
    ('11111111-1111-1111-1111-111111111111'::uuid, 'user', 'покажи настройки', false, NOW() - INTERVAL '1 hour'),
    ('11111111-1111-1111-1111-111111111111'::uuid, 'assistant', '👤 DIMA\n💱 Default currency: RSD\n💳 Default account: CARD_DIMA_VISA_RAIF', false, NOW() - INTERVAL '1 hour');

-- ═══════════════════════════════════════════════════════════════════════════
-- VERIFICATION QUERIES
-- ═══════════════════════════════════════════════════════════════════════════

-- Check users
SELECT username, telegram_id, display_name, default_currency FROM users WHERE username IN ('DIMA', 'KIKI');

-- Check DIMA's accounts
SELECT u.username, a.external_id, a.display_name, a.aliases 
FROM accounts a 
JOIN users u ON a.user_id = u.id 
WHERE u.username = 'DIMA';

-- Check DIMA's funds
SELECT u.username, f.external_id, f.display_name, f.aliases 
FROM funds f 
JOIN users u ON f.user_id = u.id 
WHERE u.username = 'DIMA';

-- Check linked users
SELECT 
    u_owner.username AS owner,
    u_target.username AS target,
    l.display_name,
    l.aliases
FROM linked_users l
JOIN users u_owner ON l.owner_user_id = u_owner.id
JOIN users u_target ON l.target_user_id = u_target.id
WHERE u_owner.username IN ('DIMA', 'KIKI');

-- Check chat messages
SELECT u.username, c.role, c.content, c.created_at 
FROM chat_messages c 
JOIN users u ON c.user_id = u.id 
WHERE u.username = 'DIMA'
ORDER BY c.created_at DESC;

-- ═══════════════════════════════════════════════════════════════════════════
-- DONE! ✅
-- ═══════════════════════════════════════════════════════════════════════════

