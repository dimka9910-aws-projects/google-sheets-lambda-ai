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

echo "🚀 Running database migrations with Liquibase Maven Plugin..."
mvn liquibase:update

echo "✅ Migrations completed successfully!"

