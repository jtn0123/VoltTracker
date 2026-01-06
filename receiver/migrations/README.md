# VoltTracker Database Migrations

This directory contains SQL migration scripts for schema changes.

## Quick Start

### Run Latest Migration

```bash
# Using the helper script (recommended)
./receiver/migrations/run_migration.sh

# Or manually with psql
psql $DATABASE_URL -f receiver/migrations/001_add_weather_cache_and_extreme_weather.sql
```

### Run Specific Migration

```bash
./receiver/migrations/run_migration.sh receiver/migrations/001_add_weather_cache_and_extreme_weather.sql
```

## Available Migrations

### 001_add_weather_cache_and_extreme_weather.sql

**Created:** 2026-01-06
**Description:** Adds persistent weather cache and extreme weather flagging

**Changes:**
- Creates `weather_cache` table for persistent API response caching
- Adds `trips.extreme_weather` boolean column for flagging extreme conditions
- Creates indexes for fast cache lookups
- Adds table/column comments

**New Features Enabled:**
- ✅ Persistent weather cache (survives app restarts)
- ✅ 90%+ reduction in weather API calls
- ✅ Extreme weather trip detection (freezing, very hot, heavy rain, strong wind)
- ✅ Shared cache across multiple app instances

**Rollback (if needed):**
```sql
DROP TABLE IF EXISTS weather_cache;
ALTER TABLE trips DROP COLUMN IF EXISTS extreme_weather;
```

## Migration Guidelines

### Creating New Migrations

1. **Naming Convention:** `NNN_description.sql` where NNN is a 3-digit number
2. **Always Include:**
   - Header comment with date and description
   - `IF NOT EXISTS` / `IF EXISTS` for idempotency
   - Indexes for new tables
   - Comments explaining purpose
3. **Test Locally:** Run against dev database first
4. **Rollback Plan:** Include rollback SQL in comments

### Example Migration Template

```sql
-- Migration: [Brief description]
-- Created: YYYY-MM-DD
-- Description: [Detailed description]

-- Add new table
CREATE TABLE IF NOT EXISTS new_table (
    id SERIAL PRIMARY KEY,
    -- ... columns
);

-- Add indexes
CREATE INDEX IF NOT EXISTS ix_new_table_lookup ON new_table (column);

-- Add comments
COMMENT ON TABLE new_table IS 'Description of table purpose';

-- Rollback (if needed):
-- DROP TABLE IF EXISTS new_table;
```

## Checking Migration Status

### Verify Weather Cache Table

```bash
psql $DATABASE_URL -c "\d weather_cache"
```

Expected output:
```
                        Table "public.weather_cache"
     Column      |            Type             | Modifiers
-----------------+-----------------------------+-----------
 id              | integer                     | not null
 latitude_key    | double precision            | not null
 longitude_key   | double precision            | not null
 timestamp_hour  | character varying(16)       | not null
 temperature_f   | double precision            |
 ...
```

### Check Cache Performance

```sql
-- View cache statistics
SELECT
    COUNT(*) as total_entries,
    COUNT(DISTINCT latitude_key || ',' || longitude_key) as unique_locations,
    MAX(fetched_at) as last_cached,
    MIN(fetched_at) as oldest_entry
FROM weather_cache;

-- Sample recent cache entries
SELECT latitude_key, longitude_key, timestamp_hour, temperature_f, conditions, fetched_at
FROM weather_cache
ORDER BY fetched_at DESC
LIMIT 10;
```

### Check Extreme Weather Trips

```sql
-- Count trips by weather type
SELECT
    extreme_weather,
    COUNT(*) as trip_count,
    AVG(kwh_per_mile) as avg_efficiency
FROM trips
WHERE kwh_per_mile IS NOT NULL
GROUP BY extreme_weather;

-- Find recent extreme weather trips
SELECT id, start_time, weather_temp_f, weather_conditions, extreme_weather
FROM trips
WHERE extreme_weather = true
ORDER BY start_time DESC
LIMIT 10;
```

## Maintenance

### Clean Up Old Cache Entries

Weather cache entries older than 7 days can be safely deleted:

```sql
-- Dry run: See how many entries would be deleted
SELECT COUNT(*) FROM weather_cache WHERE fetched_at < NOW() - INTERVAL '7 days';

-- Delete old entries
DELETE FROM weather_cache WHERE fetched_at < NOW() - INTERVAL '7 days';
```

**Recommended Schedule:** Run weekly via cron or scheduled job

### Monitor Cache Size

```sql
-- Check cache table size
SELECT
    pg_size_pretty(pg_total_relation_size('weather_cache')) as total_size,
    pg_size_pretty(pg_relation_size('weather_cache')) as table_size,
    pg_size_pretty(pg_indexes_size('weather_cache')) as indexes_size;

-- Count entries
SELECT COUNT(*) as total_entries FROM weather_cache;
```

## Troubleshooting

### Migration Fails: "relation already exists"

**Cause:** Migration was partially applied or table exists from previous attempt.

**Solution:** The migration uses `IF NOT EXISTS`, so it's safe to re-run. If issues persist:

```sql
-- Check existing tables
\dt weather_cache

-- Check existing columns
\d trips

-- If needed, manually verify and complete migration
```

### Database Connection Refused

**Cause:** Database is not running or connection string is incorrect.

**Solution:**

1. Check if database is running: `docker ps` or `systemctl status postgresql`
2. Verify `DATABASE_URL` environment variable
3. Test connection: `psql $DATABASE_URL -c "SELECT 1"`

### Permission Errors

**Cause:** Database user lacks permissions to create tables.

**Solution:**

```sql
-- Grant necessary permissions (as superuser)
GRANT CREATE ON DATABASE volt_tracker TO volt;
GRANT ALL ON SCHEMA public TO volt;
```

## Docker Deployment

### Run Migration in Docker

```bash
# Start database
docker-compose up -d postgres

# Wait for database to be ready
sleep 5

# Run migration
docker-compose exec postgres psql -U volt -d volt_tracker -f /migrations/001_add_weather_cache_and_extreme_weather.sql
```

### Include in Docker Build

Add to `docker-compose.yml`:

```yaml
volumes:
  - ./receiver/migrations:/migrations:ro
```

## Production Deployment

### Pre-Deployment Checklist

- [ ] Test migration on staging database
- [ ] Backup production database
- [ ] Verify rollback procedure
- [ ] Plan maintenance window (if needed)
- [ ] Notify team of schema changes

### Run Migration (Production)

```bash
# 1. Backup database
pg_dump $DATABASE_URL > backup_$(date +%Y%m%d_%H%M%S).sql

# 2. Run migration
./receiver/migrations/run_migration.sh

# 3. Verify
psql $DATABASE_URL -c "\d weather_cache"
psql $DATABASE_URL -c "SELECT COUNT(*) FROM weather_cache"

# 4. Monitor logs for errors
tail -f logs/volttracker.log
```

### Rollback (if needed)

```sql
-- Remove new features (only if migration causes issues)
DROP TABLE IF EXISTS weather_cache;
ALTER TABLE trips DROP COLUMN IF EXISTS extreme_weather;

-- Restore from backup (if needed)
psql $DATABASE_URL < backup_YYYYMMDD_HHMMSS.sql
```

## Questions?

See the main [CLAUDE.md](../../CLAUDE.md) for development guidelines or check the commit message for this migration for detailed implementation notes.
