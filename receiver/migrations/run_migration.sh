#!/bin/bash
# Migration runner script for VoltTracker
# Usage: ./run_migration.sh [migration_file]

set -e  # Exit on error

# Get database URL from environment or use default
DATABASE_URL="${DATABASE_URL:-postgresql://volt:changeme@localhost:5432/volt_tracker}"

# Get migration file (default to latest)
MIGRATION_FILE="${1:-receiver/migrations/001_add_weather_cache_and_extreme_weather.sql}"

echo "========================================="
echo "VoltTracker Database Migration Runner"
echo "========================================="
echo ""
echo "Database: $DATABASE_URL"
echo "Migration: $MIGRATION_FILE"
echo ""

# Check if migration file exists
if [ ! -f "$MIGRATION_FILE" ]; then
    echo "❌ Error: Migration file not found: $MIGRATION_FILE"
    exit 1
fi

echo "Running migration..."
echo ""

# Run the migration
if psql "$DATABASE_URL" -f "$MIGRATION_FILE"; then
    echo ""
    echo "✅ Migration completed successfully!"
    echo ""

    # Show weather_cache table structure
    echo "Weather Cache Table:"
    psql "$DATABASE_URL" -c "\d weather_cache" || true

    echo ""
    echo "Trip Table (showing extreme_weather column):"
    psql "$DATABASE_URL" -c "\d trips" | grep -A 1 "extreme_weather" || true

    echo ""
    echo "Migration applied successfully. New features:"
    echo "  ✓ Persistent weather cache (weather_cache table)"
    echo "  ✓ Extreme weather flagging (trips.extreme_weather column)"
else
    echo ""
    echo "❌ Migration failed. Check error messages above."
    exit 1
fi
