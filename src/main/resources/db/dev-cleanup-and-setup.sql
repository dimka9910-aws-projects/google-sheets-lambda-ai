-- ═══════════════════════════════════════════════════════════════════════════
-- DEV DATABASE CLEANUP AND SETUP
-- Cleans all data for DIMA and KIKI users and fills with fresh data
-- ═══════════════════════════════════════════════════════════════════════════

BEGIN;

-- ═══════════════════════════════════════════════════════════════════════════
-- STEP 1: CLEANUP - Delete all data for DIMA and KIKI
-- ═══════════════════════════════════════════════════════════════════════════

-- Delete financial operations
DELETE FROM financial_operations 
WHERE user_id IN (SELECT id FROM users WHERE username IN ('DIMA', 'KIKI'));

-- Delete chat messages
DELETE FROM chat_messages 
WHERE user_id IN (SELECT id FROM users WHERE username IN ('DIMA', 'KIKI'));

-- Delete linked users relationships
DELETE FROM linked_users 
WHERE owner_user_id IN (SELECT id FROM users WHERE username IN ('DIMA', 'KIKI'))
   OR target_user_id IN (SELECT id FROM users WHERE username IN ('DIMA', 'KIKI'));

-- Delete accounts
DELETE FROM accounts 
WHERE user_id IN (SELECT id FROM users WHERE username IN ('DIMA', 'KIKI'));

-- Delete funds
DELETE FROM funds 
WHERE user_id IN (SELECT id FROM users WHERE username IN ('DIMA', 'KIKI'));

-- Delete users (this will cascade delete everything else due to FK constraints)
DELETE FROM users WHERE username IN ('DIMA', 'KIKI');

-- ═══════════════════════════════════════════════════════════════════════════
-- STEP 2: INSERT DIMA USER
-- ═══════════════════════════════════════════════════════════════════════════

INSERT INTO users (id, username, telegram_id, display_name, default_currency, preferred_language, ai_context, default_account_id, default_fund_id, created_at)
VALUES (
    '11111111-1111-1111-1111-111111111111'::uuid,
    'DIMA',
    '377662506',
    'Дима',
    NULL,  -- default currency пустой
    NULL,  -- preferred language пустой
    '{}'::jsonb,  -- пустой контекст
    NULL,  -- default account пустой
    NULL,  -- default fund пустой
    NOW()
);

-- DIMA's accounts
INSERT INTO accounts (id, user_id, external_id, display_name, aliases, created_at) VALUES
    ('11111111-0001-0000-0000-000000000001'::uuid, '11111111-1111-1111-1111-111111111111'::uuid, 'CARD_DIMA_VISA_RAIF', 'Visa Raiffeisen', ARRAY[]::text[], NOW()),
    ('11111111-0001-0000-0000-000000000002'::uuid, '11111111-1111-1111-1111-111111111111'::uuid, 'CASH_DIMA', 'Наличные', ARRAY[]::text[], NOW()),
    ('11111111-0001-0000-0000-000000000003'::uuid, '11111111-1111-1111-1111-111111111111'::uuid, 'DIMA_CASH_PERSONAL_RESERVE', 'Personal Reserve Cash', ARRAY[]::text[], NOW()),
    ('11111111-0001-0000-0000-000000000004'::uuid, '11111111-1111-1111-1111-111111111111'::uuid, 'CARD_DIMA_CREDIT', 'Credit Card', ARRAY[]::text[], NOW()),
    ('11111111-0001-0000-0000-000000000005'::uuid, '11111111-1111-1111-1111-111111111111'::uuid, 'CARD_DIMA_YETTEL', 'Yettel Card', ARRAY[]::text[], NOW());

-- DIMA's funds
INSERT INTO funds (id, user_id, external_id, display_name, aliases, created_at) VALUES
    ('11111111-0002-0000-0000-000000000001'::uuid, '11111111-1111-1111-1111-111111111111'::uuid, 'DIMA_MONTHLY_BUDGET', 'Monthly Budget', ARRAY[]::text[], NOW()),
    ('11111111-0002-0000-0000-000000000002'::uuid, '11111111-1111-1111-1111-111111111111'::uuid, 'PERSONAL_RESERVE_DIMA', 'Personal Reserve', ARRAY[]::text[], NOW()),
    ('11111111-0002-0000-0000-000000000003'::uuid, '11111111-1111-1111-1111-111111111111'::uuid, 'DIMA_CREDIT', 'Credit', ARRAY[]::text[], NOW()),
    ('11111111-0002-0000-0000-000000000004'::uuid, '11111111-1111-1111-1111-111111111111'::uuid, 'FAMILY_MONTHLY_BUDGET', 'Family Monthly Budget', ARRAY[]::text[], NOW()),
    ('11111111-0002-0000-0000-000000000005'::uuid, '11111111-1111-1111-1111-111111111111'::uuid, 'FAMILY_RESERVE', 'Family Reserve', ARRAY[]::text[], NOW()),
    ('11111111-0002-0000-0000-000000000006'::uuid, '11111111-1111-1111-1111-111111111111'::uuid, 'TRAVEL', 'Travel', ARRAY[]::text[], NOW()),
    ('11111111-0002-0000-0000-000000000007'::uuid, '11111111-1111-1111-1111-111111111111'::uuid, 'DEBT_RESOLVE', 'Debt Resolve', ARRAY[]::text[], NOW());

-- ═══════════════════════════════════════════════════════════════════════════
-- STEP 3: INSERT KIKI USER
-- ═══════════════════════════════════════════════════════════════════════════

INSERT INTO users (id, username, telegram_id, display_name, default_currency, preferred_language, ai_context, default_account_id, default_fund_id, created_at)
VALUES (
    '22222222-2222-2222-2222-222222222222'::uuid,
    'KIKI',
    '526913915',
    'Ксюша',
    NULL,  -- default currency пустой
    NULL,  -- preferred language пустой
    '{}'::jsonb,  -- пустой контекст
    NULL,  -- default account пустой
    NULL,  -- default fund пустой
    NOW()
);

-- KIKI's accounts
INSERT INTO accounts (id, user_id, external_id, display_name, aliases, created_at) VALUES
    ('22222222-0001-0000-0000-000000000001'::uuid, '22222222-2222-2222-2222-222222222222'::uuid, 'CARD_KIKI_RAIF', 'Raiffeisen Card', ARRAY[]::text[], NOW()),
    ('22222222-0001-0000-0000-000000000002'::uuid, '22222222-2222-2222-2222-222222222222'::uuid, 'CASH_KIKI', 'Наличные', ARRAY[]::text[], NOW()),
    ('22222222-0001-0000-0000-000000000003'::uuid, '22222222-2222-2222-2222-222222222222'::uuid, 'CARD_KIKI_CREDIT', 'Credit Card', ARRAY[]::text[], NOW());

-- KIKI's funds
INSERT INTO funds (id, user_id, external_id, display_name, aliases, created_at) VALUES
    ('22222222-0002-0000-0000-000000000001'::uuid, '22222222-2222-2222-2222-222222222222'::uuid, 'KIKI_MONTHLY_BUDGET', 'Monthly Budget', ARRAY[]::text[], NOW()),
    ('22222222-0002-0000-0000-000000000002'::uuid, '22222222-2222-2222-2222-222222222222'::uuid, 'PERSONAL_RESERVE_KIKI', 'Personal Reserve', ARRAY[]::text[], NOW()),
    ('22222222-0002-0000-0000-000000000003'::uuid, '22222222-2222-2222-2222-222222222222'::uuid, 'FAMILY_MONTHLY_BUDGET', 'Family Monthly Budget', ARRAY[]::text[], NOW()),
    ('22222222-0002-0000-0000-000000000004'::uuid, '22222222-2222-2222-2222-222222222222'::uuid, 'FAMILY_RESERVE', 'Family Reserve', ARRAY[]::text[], NOW()),
    ('22222222-0002-0000-0000-000000000005'::uuid, '22222222-2222-2222-2222-222222222222'::uuid, 'TRAVEL', 'Travel', ARRAY[]::text[], NOW()),
    ('22222222-0002-0000-0000-000000000006'::uuid, '22222222-2222-2222-2222-222222222222'::uuid, 'DEBT_RESOLVE', 'Debt Resolve', ARRAY[]::text[], NOW());

-- ═══════════════════════════════════════════════════════════════════════════
-- STEP 4: LINKED USERS (bidirectional relationship)
-- ═══════════════════════════════════════════════════════════════════════════

-- DIMA → KIKI link
INSERT INTO linked_users (id, owner_user_id, target_user_id, display_name, aliases, created_at)
VALUES (
    '33333333-0001-0000-0000-000000000001'::uuid,
    '11111111-1111-1111-1111-111111111111'::uuid,  -- DIMA
    '22222222-2222-2222-2222-222222222222'::uuid,  -- KIKI
    'Ксюша',
    ARRAY[]::text[],  -- пустые aliases
    NOW()
);

-- KIKI → DIMA link
INSERT INTO linked_users (id, owner_user_id, target_user_id, display_name, aliases, created_at)
VALUES (
    '33333333-0002-0000-0000-000000000001'::uuid,
    '22222222-2222-2222-2222-222222222222'::uuid,  -- KIKI
    '11111111-1111-1111-1111-111111111111'::uuid,  -- DIMA
    'Дима',
    ARRAY[]::text[],  -- пустые aliases
    NOW()
);

COMMIT;

-- ═══════════════════════════════════════════════════════════════════════════
-- VERIFICATION QUERIES
-- ═══════════════════════════════════════════════════════════════════════════

-- Check users
SELECT username, telegram_id, display_name, default_currency, default_account_id, default_fund_id 
FROM users 
WHERE username IN ('DIMA', 'KIKI');

-- Check DIMA's accounts
SELECT u.username, a.external_id, a.display_name, a.aliases 
FROM accounts a 
JOIN users u ON a.user_id = u.id 
WHERE u.username = 'DIMA'
ORDER BY a.external_id;

-- Check DIMA's funds
SELECT u.username, f.external_id, f.display_name, f.aliases 
FROM funds f 
JOIN users u ON f.user_id = u.id 
WHERE u.username = 'DIMA'
ORDER BY f.external_id;

-- Check KIKI's accounts
SELECT u.username, a.external_id, a.display_name, a.aliases 
FROM accounts a 
JOIN users u ON a.user_id = u.id 
WHERE u.username = 'KIKI'
ORDER BY a.external_id;

-- Check KIKI's funds
SELECT u.username, f.external_id, f.display_name, f.aliases 
FROM funds f 
JOIN users u ON f.user_id = u.id 
WHERE u.username = 'KIKI'
ORDER BY f.external_id;

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

-- ═══════════════════════════════════════════════════════════════════════════
-- DONE! ✅
-- ═══════════════════════════════════════════════════════════════════════════

