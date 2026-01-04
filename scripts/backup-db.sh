#!/bin/bash
# Database backup script for VoltTracker
# Creates timestamped PostgreSQL dumps in backups/db/

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
BACKUP_DIR="$PROJECT_DIR/backups/db"

# Create backup directory if needed
mkdir -p "$BACKUP_DIR"

# Generate timestamp
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_FILE="$BACKUP_DIR/volttracker_${TIMESTAMP}.sql.gz"

echo "Creating database backup..."

# Run pg_dump inside the database container and compress
docker exec volt-tracker-db pg_dump -U volt volt_tracker | gzip > "$BACKUP_FILE"

# Get file size
SIZE=$(ls -lh "$BACKUP_FILE" | awk '{print $5}')

echo "Backup complete: $BACKUP_FILE ($SIZE)"

# Keep only last 10 backups (optional cleanup)
cd "$BACKUP_DIR"
ls -t volttracker_*.sql.gz 2>/dev/null | tail -n +11 | xargs -r rm -f

# Show recent backups
echo ""
echo "Recent backups:"
ls -lh "$BACKUP_DIR"/volttracker_*.sql.gz 2>/dev/null | tail -5
