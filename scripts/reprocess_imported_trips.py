#!/usr/bin/env python3
"""
Reprocess Imported Trips

This script recalculates trip statistics for existing imported CSV trips
that may have incorrect or missing data (distance, electric_miles, etc.).

Usage (run inside Docker container):
    docker compose exec receiver python scripts/reprocess_imported_trips.py

Or run directly if you have the environment set up:
    python scripts/reprocess_imported_trips.py --db postgresql://volt:volt@localhost:5432/volt_tracker
"""

import argparse
import sys
import os

# Add parent directory to path for imports when running from scripts/
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def reprocess_trips(db_url: str, dry_run: bool = False):
    """Reprocess all imported trips to fix missing/incorrect data."""
    from sqlalchemy import create_engine, text, func
    from sqlalchemy.orm import sessionmaker

    engine = create_engine(db_url)
    Session = sessionmaker(bind=engine)
    db = Session()

    try:
        # Import models after setting up path
        from models import Trip, TelemetryRaw
        from services.trip_service import finalize_trip

        # Find all imported trips
        imported_trips = db.query(Trip).filter(
            Trip.is_imported.is_(True)
        ).order_by(Trip.id).all()

        print(f"Found {len(imported_trips)} imported trips to reprocess")

        fixed_count = 0
        error_count = 0

        for trip in imported_trips:
            print(f"\n--- Trip {trip.id} (session: {trip.session_id}) ---")
            print(f"  Current: distance={trip.distance_miles}, electric={trip.electric_miles}, gas={trip.gas_miles}")

            try:
                # Query MIN/MAX odometer from telemetry
                odometer_range = db.query(
                    func.min(TelemetryRaw.odometer_miles),
                    func.max(TelemetryRaw.odometer_miles)
                ).filter(
                    TelemetryRaw.session_id == trip.session_id,
                    TelemetryRaw.odometer_miles.isnot(None)
                ).first()

                # Query MIN/MAX SOC from telemetry
                soc_range = db.query(
                    func.min(TelemetryRaw.state_of_charge),
                    func.max(TelemetryRaw.state_of_charge)
                ).filter(
                    TelemetryRaw.session_id == trip.session_id,
                    TelemetryRaw.state_of_charge.isnot(None)
                ).first()

                # Query time range from telemetry
                time_range = db.query(
                    func.min(TelemetryRaw.timestamp),
                    func.max(TelemetryRaw.timestamp)
                ).filter(
                    TelemetryRaw.session_id == trip.session_id
                ).first()

                new_start_odo = odometer_range[0] if odometer_range and odometer_range[0] is not None else None
                new_end_odo = odometer_range[1] if odometer_range and odometer_range[1] is not None else None
                new_start_soc = soc_range[1] if soc_range and soc_range[1] is not None else None  # MAX = start
                new_distance = abs(new_end_odo - new_start_odo) if new_start_odo is not None and new_end_odo is not None else None

                print(f"  Telemetry: odo={new_start_odo}->{new_end_odo} ({new_distance} mi), SOC start={new_start_soc}")

                if dry_run:
                    print(f"  [DRY RUN] Would update trip")
                    continue

                # Update trip fields
                if time_range and time_range[0]:
                    trip.start_time = time_range[0]
                if time_range and time_range[1]:
                    trip.end_time = time_range[1]
                trip.start_odometer = new_start_odo
                trip.end_odometer = new_end_odo
                trip.start_soc = new_start_soc
                trip.distance_miles = new_distance

                # Reset derived fields so finalize_trip recalculates them
                trip.electric_miles = None
                trip.gas_miles = None
                trip.gas_mpg = None
                trip.electric_kwh_used = None
                trip.kwh_per_mile = None
                trip.soc_at_gas_transition = None
                trip.gas_mode_entered = False
                trip.is_closed = False

                db.commit()

                # Call finalize_trip to calculate all derived fields
                try:
                    finalize_trip(db, trip)
                    print(f"  Updated: distance={trip.distance_miles}, electric={trip.electric_miles}, gas={trip.gas_miles}")
                    fixed_count += 1
                except Exception as e:
                    print(f"  Warning: finalize_trip failed: {e}")
                    trip.is_closed = True
                    db.commit()
                    fixed_count += 1

            except Exception as e:
                print(f"  ERROR: {e}")
                error_count += 1
                db.rollback()
                continue

        print(f"\n=== Summary ===")
        print(f"Fixed: {fixed_count}")
        print(f"Errors: {error_count}")
        print(f"Total: {len(imported_trips)}")

    finally:
        db.close()


def main():
    parser = argparse.ArgumentParser(description='Reprocess imported trips to fix missing data')
    parser.add_argument('--db', type=str,
                       default=os.environ.get('DATABASE_URL', 'postgresql://volt:volt@db:5432/volt_tracker'),
                       help='Database URL')
    parser.add_argument('--dry-run', action='store_true',
                       help='Show what would be done without making changes')

    args = parser.parse_args()

    print(f"Database: {args.db.split('@')[-1]}")  # Hide password in output
    print(f"Dry run: {args.dry_run}")
    print()

    reprocess_trips(args.db, dry_run=args.dry_run)


if __name__ == '__main__':
    main()
