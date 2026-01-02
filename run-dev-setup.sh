#!/bin/bash

# ═══════════════════════════════════════════════════════════════════════════
# DEV DATABASE SETUP SCRIPT
# Cleans and fills DIMA and KIKI users with fresh data
# ═══════════════════════════════════════════════════════════════════════════

set -e

echo "🔧 DEV Database Setup Script"
echo "═══════════════════════════════════════════════════════════════════════════"
echo ""

# Check if DATABASE_URL is set
if [ -z "$DATABASE_URL" ]; then
    echo "❌ ERROR: DATABASE_URL environment variable is not set"
    echo ""
    echo "Usage:"
    echo "  export DATABASE_URL='postgresql://user:password@host:port/database?sslmode=require'"
    echo "  ./run-dev-setup.sh"
    echo ""
    echo "Example:"
    echo "  export DATABASE_URL='postgresql://neondb_owner:pass@ep-xxx.neon.tech/neondb?sslmode=require'"
    exit 1
fi

echo "✅ DATABASE_URL is set"
echo "📊 Database: $(echo $DATABASE_URL | sed 's/password=[^&@]*/password=***/g')"
echo ""

# Confirm before proceeding
echo "⚠️  WARNING: This will DELETE all data for DIMA and KIKI users!"
echo "   - All accounts, funds, chat history, financial operations"
echo "   - All linked user relationships"
echo "   - All custom instructions and AI context"
echo ""
read -p "Continue? (yes/no): " confirm

if [ "$confirm" != "yes" ]; then
    echo "❌ Aborted."
    exit 1
fi

echo ""
echo "🚀 Executing cleanup and setup..."
echo ""

# Execute SQL script
psql "$DATABASE_URL" -f src/main/resources/db/dev-cleanup-and-setup.sql

echo ""
echo "✅ Done! Database cleaned and filled with fresh data."
echo ""
echo "📋 Summary:"
echo "   - DIMA: 5 accounts, 7 funds"
echo "   - KIKI: 3 accounts, 6 funds"
echo "   - Linked users: DIMA ↔ KIKI"
echo "   - All defaults: NULL (empty)"
echo ""

