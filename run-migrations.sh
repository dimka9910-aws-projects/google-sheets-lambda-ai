#!/bin/bash

# Script to run database migrations locally using Liquibase Maven Plugin
# Usage: ./run-migrations.sh

set -e

# Check if DATABASE_URL is set
if [ -z "$DATABASE_URL" ]; then
    echo "❌ Error: DATABASE_URL environment variable is not set"
    echo "Usage: export DATABASE_URL='postgresql://user:password@host:port/database'"
    exit 1
fi

# Parse DATABASE_URL (format: postgresql://user:pass@host:port/db?params)
# Extract username and password
DB_USER=$(echo "$DATABASE_URL" | sed -n 's|postgresql://\([^:]*\):.*|\1|p')
DB_PASS=$(echo "$DATABASE_URL" | sed -n 's|postgresql://[^:]*:\([^@]*\)@.*|\1|p')
# Convert to JDBC URL (jdbc:postgresql://host:port/db?params)
JDBC_URL=$(echo "$DATABASE_URL" | sed 's|postgresql://[^@]*@|jdbc:postgresql://|')

echo "🚀 Running database migrations with Liquibase Maven Plugin..."
mvn liquibase:update \
  -Dliquibase.url="$JDBC_URL" \
  -Dliquibase.username="$DB_USER" \
  -Dliquibase.password="$DB_PASS"

echo "✅ Migrations completed successfully!"

