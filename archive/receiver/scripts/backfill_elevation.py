#!/usr/bin/env python3
"""
Backfill elevation data for historical trips.

This script fetches elevation data for trips that don't have it yet,
allowing historical data analysis.

Usage:
    python -m scripts.backfill_elevation [--limit N] [--dry-run]

Options:
    --limit N     Process only N trips (default: all)
    --dry-run     Show what would be done without making changes
    --batch-size  Number of trips to process per batch (default: 50)
"""

import argparse
import logging
import sys
import time
from pathlib import Path

# Add receiver directory to path for imports
sys.path.insert(0, str(Path(__file__).parent.parent))

from database import get_db  # noqa: E402
from models import TelemetryRaw, Trip  # noqa: E402
from utils.elevation import (  # noqa: E402
    calculate_elevation_profile,
    get_elevation_for_points,
    sample_coordinates,
)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(levelname)s - %(message)s",
)
logger = logging.getLogger(__name__)


def get_trips_needing_elevation(db, limit: int | None = None):
    """Get trips that need elevation data."""
    query = (
        db.query(Trip)
        .filter(
            Trip.is_closed == True,  # noqa: E712
            Trip.deleted_at.is_(None),
            Trip.elevation_gain_m.is_(None),
        )
        .order_by(Trip.start_time.desc())
    )

    if limit:
        query = query.limit(limit)

    return query.all()


def get_trip_gps_points(db, trip: Trip):
    """Get GPS coordinates for a trip's telemetry."""
    telemetry = (
        db.query(TelemetryRaw.latitude, TelemetryRaw.longitude)
        .filter(
            TelemetryRaw.session_id == trip.session_id,
            TelemetryRaw.latitude.isnot(None),
            TelemetryRaw.longitude.isnot(None),
        )
        .order_by(TelemetryRaw.timestamp)
        .all()
    )

    return [(t.latitude, t.longitude) for t in telemetry]


def backfill_trip_elevation(db, trip: Trip, dry_run: bool = False) -> bool:
    """
    Backfill elevation data for a single trip.

    Returns:
        True if elevation was fetched, False if skipped
    """
    gps_points = get_trip_gps_points(db, trip)

    if len(gps_points) < 2:
        logger.debug(f"Trip {trip.id}: Skipping - not enough GPS points ({len(gps_points)})")
        return False

    # Sample coordinates
    sampled = sample_coordinates(gps_points, max_samples=25)

    if dry_run:
        logger.info(f"Trip {trip.id}: Would fetch elevation for {len(sampled)} points")
        return True

    # Fetch elevations
    elevations = get_elevation_for_points(sampled)
    if not elevations:
        logger.warning(f"Trip {trip.id}: Elevation API returned no data")
        return False

    # Calculate profile
    profile = calculate_elevation_profile(elevations)

    # Update trip
    if elevations[0] is not None:
        trip.elevation_start_m = elevations[0]
    if elevations[-1] is not None:
        trip.elevation_end_m = elevations[-1]
    trip.elevation_gain_m = profile.get("total_gain_m")
    trip.elevation_loss_m = profile.get("total_loss_m")
    trip.elevation_net_change_m = profile.get("net_change_m")
    trip.elevation_max_m = profile.get("max_elevation_m")
    trip.elevation_min_m = profile.get("min_elevation_m")

    return True


def main():
    parser = argparse.ArgumentParser(description="Backfill elevation data for historical trips")
    parser.add_argument("--limit", type=int, help="Maximum number of trips to process")
    parser.add_argument("--dry-run", action="store_true", help="Show what would be done")
    parser.add_argument("--batch-size", type=int, default=50, help="Batch size for commits")
    args = parser.parse_args()

    logger.info("Starting elevation backfill...")
    if args.dry_run:
        logger.info("DRY RUN MODE - no changes will be made")

    db = get_db()

    try:
        # Get trips needing elevation
        trips = get_trips_needing_elevation(db, args.limit)
        total = len(trips)
        logger.info(f"Found {total} trips needing elevation data")

        if total == 0:
            logger.info("No trips to process")
            return

        processed = 0
        skipped = 0
        errors = 0

        for i, trip in enumerate(trips, 1):
            try:
                success = backfill_trip_elevation(db, trip, dry_run=args.dry_run)
                if success:
                    processed += 1
                else:
                    skipped += 1

                # Commit in batches
                if not args.dry_run and i % args.batch_size == 0:
                    db.commit()
                    logger.info(f"Progress: {i}/{total} trips processed")

                # Rate limiting to avoid overwhelming the API
                if not args.dry_run:
                    time.sleep(0.1)

            except Exception as e:
                errors += 1
                logger.error(f"Trip {trip.id}: Error - {e}")

        # Final commit
        if not args.dry_run:
            db.commit()

        logger.info(f"Backfill complete: {processed} processed, {skipped} skipped, {errors} errors")

    except Exception as e:
        logger.exception(f"Backfill failed: {e}")
        db.rollback()
        sys.exit(1)
    finally:
        db.close()


if __name__ == "__main__":
    main()
