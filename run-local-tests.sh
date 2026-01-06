#!/bin/bash

echo "🚀 Finance Tracker - Local Test Runner"
echo ""

# Check if DATABASE_URL is set
if [ -z "$DATABASE_URL" ]; then
    echo "❌ ERROR: DATABASE_URL environment variable is not set"
    echo ""
    echo "Please set it to your DEV PostgreSQL database:"
    echo "  export DATABASE_URL='jdbc:postgresql://your-db-host:5432/your-db?user=your-user&password=your-password'"
    echo ""
    echo "Example for local PostgreSQL:"
    echo "  export DATABASE_URL='jdbc:postgresql://localhost:5432/finance_tracker?user=postgres&password=postgres'"
    echo ""
    exit 1
fi

echo "✅ DATABASE_URL is set"
echo "📊 Database: $(echo $DATABASE_URL | sed 's/password=[^&]*/password=***/g')"
echo ""

echo "ℹ️ OpenAI key is expected to be provided via environment (OPENAI_API_KEY) or your local application.properties (not committed)."
echo ""
echo "🏗️  Building project..."
mvn clean compile -DskipTests -q

if [ $? -ne 0 ]; then
    echo "❌ Build failed"
    exit 1
fi

echo "✅ Build successful"
echo ""
echo "🧪 Running tests..."
echo ""

# Run the test
mvn exec:java -Dexec.mainClass="com.github.dimka9910.sheets.ai.LocalTestRunner" -Dexec.classpathScope=test -q

