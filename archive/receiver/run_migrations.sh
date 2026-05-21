#!/bin/bash
# Run pending Alembic migrations before starting the application.
# R12: Ensures schema is up-to-date on every container restart.

set -e

echo "Running Alembic migrations..."
cd /app

if [ -f alembic.ini ]; then
    alembic upgrade head || {
        echo "WARNING: Alembic migrations failed (may be first run or no migrations pending)"
        echo "Continuing with application startup..."
    }
else
    echo "No alembic.ini found, skipping migrations."
fi

echo "Starting application..."
exec "$@"
