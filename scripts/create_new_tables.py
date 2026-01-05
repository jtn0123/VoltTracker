#!/usr/bin/env python3
"""
Database Migration: Create new analytics tables

Creates maintenance_records and routes tables for new features.

Usage:
    python scripts/create_new_tables.py

This script is idempotent - safe to run multiple times.
"""

import os
import sys

# Add receiver directory to path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "receiver"))

from config import Config  # noqa: E402
from models import MaintenanceRecord, Route, get_engine  # noqa: E402
from sqlalchemy import text  # noqa: E402


def check_table_exists(engine, table_name):
    """Check if a table exists."""
    with engine.connect() as conn:
        result = conn.execute(
            text(
                "SELECT EXISTS ("
                "   SELECT FROM information_schema.tables "
                "   WHERE table_schema = 'public' "
                f"   AND table_name = '{table_name}'"
                ")"
            )
        )
        return result.scalar()


def create_maintenance_table(engine):
    """Create maintenance_records table."""
    if check_table_exists(engine, "maintenance_records"):
        print("✓ maintenance_records table already exists")
        return False

    print("📝 Creating maintenance_records table...")
    MaintenanceRecord.__table__.create(engine, checkfirst=True)
    print("✅ Successfully created maintenance_records table!")
    return True


def create_routes_table(engine):
    """Create routes table."""
    if check_table_exists(engine, "routes"):
        print("✓ routes table already exists")
        return False

    print("📝 Creating routes table...")
    Route.__table__.create(engine, checkfirst=True)
    print("✅ Successfully created routes table!")
    return True


def verify_tables(engine):
    """Verify tables were created correctly."""
    tables = ["maintenance_records", "routes"]

    print("\n🔍 Verifying table structures...\n")

    for table_name in tables:
        with engine.connect() as conn:
            result = conn.execute(
                text(
                    "SELECT column_name, data_type "
                    "FROM information_schema.columns "
                    f"WHERE table_name = '{table_name}' "
                    "ORDER BY ordinal_position"
                )
            )

            columns = result.fetchall()

            if columns:
                print(f"✓ {table_name}:")
                for col_name, col_type in columns:
                    print(f"  - {col_name}: {col_type}")
                print()
            else:
                print(f"⚠️  Warning: Could not verify {table_name} structure\n")


if __name__ == "__main__":
    try:
        print("=" * 60)
        print("Database Migration: Create Analytics Tables")
        print("=" * 60)
        print()

        engine = get_engine(Config.DATABASE_URL)

        created_count = 0
        created_count += create_maintenance_table(engine)
        created_count += create_routes_table(engine)

        if created_count > 0:
            verify_tables(engine)
            print("\n" + "=" * 60)
            print(f"Migration complete! Created {created_count} new table(s)")
            print("=" * 60)
        else:
            print("\n" + "=" * 60)
            print("All tables already exist - nothing to do!")
            print("=" * 60)

    except Exception as e:
        print(f"\n❌ Error: {e}", file=sys.stderr)
        import traceback

        traceback.print_exc()
        sys.exit(1)
