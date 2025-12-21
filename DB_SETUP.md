# Database Setup for Finance Tracker

## 📁 Structure Created

```
google-sheets-lambda-ai/
├── src/main/java/com/github/dimka9910/sheets/ai/db/
│   ├── entity/
│   │   └── FinancialOperation.java           # Entity class
│   ├── repository/
│   │   └── FinancialOperationRepository.java # Data access layer
│   ├── service/
│   │   └── FinancialOperationService.java    # Business logic
│   └── DatabaseConfig.java                   # DB config & initialization
├── src/main/resources/db/
│   ├── changelog/
│   │   ├── db.changelog-master.yaml          # Master changelog
│   │   └── migrations/
│   │       └── 001-create-financial-operations.yaml
│   └── README.md                             # Database documentation
├── pom.xml                                    # Added dependencies
├── template.yaml                              # Added DATABASE_URL parameter
└── run-migrations.sh                          # Migration script
```

## 🗄️ Database Schema

### `financial_operations` table

| Column | Type | Description |
|--------|------|-------------|
| **id** | UUID | Primary key (auto-generated) |
| **user_id** | VARCHAR(100) | User name (from UserEntity) |
| **operation_type** | VARCHAR(20) | EXPENSE, INCOME, INTERNAL_TRANSFER, TRANSFER, EXCHANGE |
| **account** | VARCHAR(100) | Account name |
| **fund** | VARCHAR(100) | Fund/category name |
| **amount** | DECIMAL(32, 18) | Amount (negative for debits, positive for credits) |
| **currency** | VARCHAR(10) | Currency code (USD, EUR, BTC, ETH, etc.) |
| **transaction_date** | TIMESTAMP | Transaction date/time |
| **description** | TEXT | Optional description |
| **link_id** | UUID | Foreign key to related transaction (for transfers) |
| **created_at** | TIMESTAMP | Auto-generated |
| **updated_at** | TIMESTAMP | Auto-updated |
| **deleted_at** | TIMESTAMP | Soft delete (NULL if active) |

**Indexes:**
- `user_id` - for filtering by user
- `operation_type` - for filtering by type
- `transaction_date` - for date range queries
- `deleted_at` - for excluding deleted records
- `link_id` - for finding related transfers

## 📦 Dependencies Added to `pom.xml`

- **PostgreSQL Driver** (42.7.1) - Database connectivity
- **HikariCP** (5.1.0) - Connection pooling
- **Liquibase Core** (4.25.1) - Database migrations
- **Spring JDBC** (6.1.3) - Lightweight data access

## 🚀 How to Use

### 1. **Set DATABASE_URL environment variable:**

```bash
export DATABASE_URL='postgresql://neondb_owner:npg_MZI1Fp0xgrOe@ep-patient-lake-agnq6ymk-pooler.c-2.eu-central-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require'
```

### 2. **Run migrations locally:**

**Option 1: Using provided script**
```bash
chmod +x run-migrations.sh
./run-migrations.sh
```

**Option 2: Using Maven directly**
```bash
mvn liquibase:update
```

**Option 3: Connect directly to DB (for verification)**
```bash
export PATH="/opt/homebrew/opt/postgresql@17/bin:$PATH"
psql "$DATABASE_URL" -c "SELECT version();"
```

### 3. **In Lambda code:**

```java
// Initialize database (connection pool only, NO migrations)
DatabaseConfig.initialize();

// Get service
FinancialOperationService service = DatabaseConfig.getService();

// Save simple operation
FinancialOperation operation = service.saveSimpleOperation(action, userId);

// Save transfer (creates two linked records)
List<FinancialOperation> transfer = service.saveTransferOperation(action, userId);

// Query operations
List<FinancialOperation> ops = service.getUserOperations(userId, 100, 0);
```

## 💡 Key Features

### **1. Transfer Support**
Transfers are stored as **two linked records** with the same `link_id`:

**Example: Internal transfer (between own accounts)**
```
Record 1: user_id=USER, amount=-100, account=CARD, link_id=abc123
Record 2: user_id=USER, amount=+100, account=CASH, link_id=abc123
```

**Example: Transfer between users**
```
Record 1: user_id=USER, amount=-500, account=CARD_USER, link_id=def456
Record 2: user_id=ALICE, amount=+500, account=CARD_ALICE, link_id=def456
```

### **2. Cryptocurrency Support**
`DECIMAL(32, 18)` supports up to 18 decimal places for crypto:
- Bitcoin: 8 decimals (satoshi)
- Ethereum: 18 decimals (wei)
- Most ERC-20 tokens: 6-18 decimals

### **3. Soft Delete**
Records are never physically deleted. Instead, `deleted_at` is set to current timestamp.

### **4. Connection Pooling**
HikariCP configured for AWS Lambda:
- Max pool size: 2 connections
- Min idle: 0 (no idle connections)
- Connection timeout: 10s
- Idle timeout: 1min
- Max lifetime: 5min

## 🔄 Next Steps

1. **Deploy to DEV:**
   - Add `DatabaseUrl` parameter to GitHub Actions workflow
   - Deploy Lambda with DATABASE_URL env var

2. **Integrate with ResultHandler:**
   - Call `FinancialOperationService.saveSimpleOperation()` or `saveTransferOperation()`
   - Store operations in PostgreSQL alongside Google Sheets

3. **Migration from DynamoDB:**
   - Write script to copy existing data
   - Or start fresh with PostgreSQL as primary storage

4. **Add more features:**
   - Analytics queries
   - Category summaries
   - Monthly reports
   - Currency conversion history

## 📊 Deployment

### Migrations run in GitHub Actions pipeline:

**Workflow sequence:**
1. ✅ Build project
2. ✅ **Run database migrations** ← Before Lambda deploy!
3. ✅ Deploy Lambda (without migrations)

**Migration step in `.github/workflows/deploy.yml`:**
```yaml
- name: Run Database Migrations
  env:
    DATABASE_URL: ${{ secrets.DATABASE_URL_DEV }}
  run: |
    echo "🚀 Running database migrations to DEV..."
    mvn liquibase:update

- name: SAM Deploy to DEV
  run: |
    sam deploy \
      --parameter-overrides \
        DatabaseUrl="${{ secrets.DATABASE_URL_DEV }}" \
        ...
```

**Using Liquibase Maven Plugin:**
- ✅ Standard industry approach
- ✅ No need to build full JAR for migrations
- ✅ Faster execution (doesn't load Spring Boot)
- ✅ Better error messages
- ✅ Can run migrations separately: `mvn liquibase:update`

### Add secrets to GitHub:
```bash
gh secret set DATABASE_URL_DEV --body "postgresql://..."
gh secret set DATABASE_URL_PROD --body "postgresql://..."
```

**Why migrations in pipeline, not Lambda:**
- ✅ Faster cold starts (no migration overhead)
- ✅ No concurrency issues (single pipeline execution)
- ✅ Lambda doesn't need DDL permissions
- ✅ Explicit control over migrations
- ✅ Easy rollback

## 🎉 Summary

✅ Database structure created  
✅ Liquibase migrations configured  
✅ Entity, Repository, Service layers implemented  
✅ Connection pooling optimized for Lambda  
✅ Soft delete support  
✅ Cryptocurrency support (DECIMAL 32,18)  
✅ Transfer operations with linked records  
✅ Documentation complete  

**Ready to run migrations and start using PostgreSQL!** 🚀

