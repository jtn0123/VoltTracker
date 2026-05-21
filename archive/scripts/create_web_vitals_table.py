#!/usr/bin/env python3
"""
Database Migration: Create web_vitals table

Creates the web_vitals table for storing Web Vitals performance metrics.

Usage:
    python scripts/create_web_vitals_table.py

This script is idempotent - safe to run multiple times.
"""

import os
import sys

# Add receiver directory to path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "receiver"))

from config import Config  # noqa: E402
from models import WebVital, get_engine  # noqa: E402
from sqlalchemy import text  # noqa: E402


def create_web_vitals_table():
    """Create the web_vitals table if it doesn't exist."""
    engine = get_engine(Config.DATABASE_URL)

    print("🔍 Checking if web_vitals table exists...")

    # Check if table already exists
    with engine.connect() as conn:
        result = conn.execute(
            text(
                "SELECT EXISTS ("
                "   SELECT FROM information_schema.tables "
                "   WHERE table_schema = 'public' "
                "   AND table_name = 'web_vitals'"
                ")"
            )
        )
        table_exists = result.scalar()

    if table_exists:
        print("✓ web_vitals table already exists. Nothing to do.")
        return

    print("📝 Creating web_vitals table...")

    # Create only the WebVital table
    WebVital.__table__.create(engine, checkfirst=True)

    print("✅ Successfully created web_vitals table!")
    print("\nTable schema:")
    print("  - id: Primary key")
    print("  - timestamp: When the metric was recorded")
    print("  - name: Metric name (LCP, FID, INP, CLS, FCP, TTFB)")
    print("  - value: Metric value (milliseconds or score)")
    print("  - rating: Performance rating (good, needs-improvement, poor)")
    print("  - metric_id: Unique metric identifier")
    print("  - navigation_type: Type of navigation")
    print("  - url: Page URL where metric was recorded")
    print("  - user_agent: Browser user agent")
    print("  - created_at: Record creation timestamp")
    print("\nIndexes:")
    print("  - ix_web_vitals_name_timestamp (name, timestamp)")
    print("  - ix_web_vitals_rating (rating)")
    print("  - ix_web_vitals_timestamp (timestamp)")


def verify_table():
    """Verify the table was created correctly."""
    engine = get_engine(Config.DATABASE_URL)

    with engine.connect() as conn:
        # Check table exists
        result = conn.execute(
            text(
                "SELECT column_name, data_type "
                "FROM information_schema.columns "
                "WHERE table_name = 'web_vitals' "
                "ORDER BY ordinal_position"
            )
        )

        columns = result.fetchall()

        if not columns:
            print("\n⚠️  Warning: Could not verify table structure")
            return

        print("\n✓ Verification successful. Columns:")
        for col_name, col_type in columns:
            print(f"  - {col_name}: {col_type}")


if __name__ == "__main__":
    try:
        print("=" * 60)
        print("Database Migration: Create web_vitals table")
        print("=" * 60)
        print()

        create_web_vitals_table()
        verify_table()

        print("\n" + "=" * 60)
        print("Migration complete!")
        print("=" * 60)

    except Exception as e:
        print(f"\n❌ Error: {e}", file=sys.stderr)
        import traceback

        traceback.print_exc()
        sys.exit(1)
