#!/bin/bash

# Script to run database migrations locally using Liquibase Maven Plugin
# Usage: ./run-migrations.sh

set -euo pipefail

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

MAVEN_REPO_LOCAL="${MAVEN_REPO_LOCAL:-${MAVEN_USER_HOME:-$HOME/.m2}/repository}"

# If a previous Maven Central failure was cached (.lastUpdated), force refresh and remove cached failures.
# This helps when the repository outage/403 was transient.
rm -f "$MAVEN_REPO_LOCAL"/org/liquibase/liquibase-maven-plugin/*/*.lastUpdated 2>/dev/null || true
rm -f "$MAVEN_REPO_LOCAL"/org/apache/maven/plugins/*/*/*.lastUpdated 2>/dev/null || true
rm -f "$MAVEN_REPO_LOCAL"/org/codehaus/mojo/*/*/*.lastUpdated 2>/dev/null || true

echo "🚀 Running database migrations with Liquibase Maven Plugin..."
echo "   URL: $(echo "$JDBC_URL" | sed 's/password=[^&]*/password=***/g')"

# Use fully-qualified plugin invocation (avoids prefix resolution) + force update (-U) for CI resilience.
set +e
mvn -U -B org.liquibase:liquibase-maven-plugin:4.29.2:update \
  -Dliquibase.url="$JDBC_URL" \
  -Dliquibase.username="$DB_USER" \
  -Dliquibase.password="$DB_PASS"
rc=$?
set -e

if [ $rc -ne 0 ]; then
  echo ""
  echo "❌ Liquibase migrations failed (exit=$rc)."
  echo "If you see HTTP 403 from repo.maven.apache.org, your CI runner cannot access Maven Central."
  echo "Fix: configure a Maven proxy/mirror (Nexus/Artifactory) or allow-list Maven Central for the runner."
  exit $rc
fi

echo "✅ Migrations completed successfully!"

