# Database Migrations

This directory contains Liquibase changelogs for database schema management.

## Structure

```
db/
├── changelog/
│   ├── db.changelog-master.yaml    # Master changelog (includes all migrations)
│   └── migrations/
│       └── 001-create-financial-operations.yaml
└── README.md
```

## Running Migrations

Migrations run automatically on Lambda startup via `DatabaseConfig.initialize()`.

### Manual Migration (local development)

```bash
# Set DATABASE_URL environment variable
export DATABASE_URL="postgresql://user:pass@host/db?sslmode=require"

# Run Liquibase
mvn liquibase:update
```

## Creating New Migrations

1. Create new file in `migrations/` directory:
   ```
   002-your-migration-name.yaml
   ```

2. Add include to `db.changelog-master.yaml`:
   ```yaml
   - include:
       file: db/changelog/migrations/002-your-migration-name.yaml
   ```

3. Write changeset in new file:
   ```yaml
   databaseChangeLog:
     - changeSet:
         id: 002-your-description
         author: your-name
         changes:
           - createTable:
               tableName: your_table
               columns:
                 - column:
                     name: id
                     type: UUID
         rollback:
           - dropTable:
               tableName: your_table
   ```

## Database Schema

### financial_operations

Stores all financial operations (expenses, income, transfers).

| Column | Type | Description |
|--------|------|-------------|
| `id` | UUID | Primary key |
| `user_id` | VARCHAR(100) | User identifier (userName) |
| `operation_type` | VARCHAR(20) | EXPENSE, INCOME, INTERNAL_TRANSFER, TRANSFER, EXCHANGE |
| `account` | VARCHAR(100) | Account name |
| `fund` | VARCHAR(100) | Fund/category name |
| `amount` | DECIMAL(32, 18) | Amount (negative for debits, positive for credits) |
| `currency` | VARCHAR(10) | Currency code (USD, EUR, BTC, etc.) |
| `transaction_date` | TIMESTAMP | Transaction date/time |
| `description` | TEXT | Optional description |
| `link_id` | UUID | Link to related transaction (for transfers) |
| `created_at` | TIMESTAMP | Creation timestamp |
| `updated_at` | TIMESTAMP | Last update timestamp |
| `deleted_at` | TIMESTAMP | Soft delete timestamp |

**Indexes:**
- `idx_financial_operations_user_id` on `user_id`
- `idx_financial_operations_operation_type` on `operation_type`
- `idx_financial_operations_transaction_date` on `transaction_date`
- `idx_financial_operations_deleted_at` on `deleted_at`
- `idx_financial_operations_link_id` on `link_id`

## Transfer Operations

Transfers are stored as **two linked records**:

**Example: INTERNAL_TRANSFER (between own accounts)**
```
Record 1: amount=-100, account=CARD_USER, link_id=abc123
Record 2: amount=+100, account=CASH_USER, link_id=abc123
```

**Example: TRANSFER (between users)**
```
Record 1: user_id=USER, amount=-500, account=CARD_USER, link_id=def456
Record 2: user_id=ALICE, amount=+500, account=CARD_ALICE, link_id=def456
```

## Rollback

Each migration includes rollback instructions:

```bash
# Rollback last changeset
mvn liquibase:rollback -Dliquibase.rollbackCount=1

# Rollback to specific tag
mvn liquibase:rollback -Dliquibase.rollbackTag=1.0.0
```

## Connection Pool

Using **HikariCP** optimized for AWS Lambda:
- Max pool size: 2
- Min idle: 0
- Connection timeout: 10s
- Idle timeout: 1min
- Max lifetime: 5min


