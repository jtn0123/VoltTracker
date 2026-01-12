"""
Tests for maintenance service module.

Tests the Volt maintenance tracking including:
- Engine hours calculation from telemetry
- Maintenance intervals and due dates
- Maintenance record management
- Volt-specific maintenance schedules
- Edge cases and validation
"""

import uuid
from datetime import datetime, timedelta, timezone

from models import MaintenanceRecord, TelemetryRaw
from services.maintenance_service import MAINTENANCE_INTERVALS, calculate_engine_hours, get_maintenance_summary


class TestCalculateEngineHours:
    """Tests for calculate_engine_hours function."""

    def test_calculates_hours_from_engine_rpm(self, app, db_session):
        """Calculate hours when engine_rpm > 400."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        # Add telemetry with engine running for 1 hour
        # 12 readings, 5 minutes apart = 1 hour total
        for i in range(12):
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=now - timedelta(minutes=60 - i * 5),
                engine_rpm=1500.0,  # Engine running
            )
            db_session.add(telemetry)
        db_session.commit()

        hours = calculate_engine_hours(db_session)

        # Should be approximately 1 hour (55 minutes of intervals)
        assert hours > 0.8
        assert hours < 1.2

    def test_excludes_electric_mode_rpm(self, app, db_session):
        """RPM <= 400 is not counted as engine hours."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        # Mix of engine on and off
        for i in range(20):
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=now - timedelta(minutes=100 - i * 5),
                engine_rpm=1500.0 if i < 10 else 0.0,  # Half on, half off
            )
            db_session.add(telemetry)
        db_session.commit()

        hours = calculate_engine_hours(db_session)

        # Should be approximately 0.75 hours (45 minutes of engine time)
        # First 10 readings have engine on, with 9 intervals between them
        assert hours > 0.6
        assert hours < 1.0

    def test_filters_unreasonable_durations(self, app, db_session):
        """Intervals > 10 minutes are excluded."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        # Create readings with large gap
        telemetry1 = TelemetryRaw(
            session_id=session_id,
            timestamp=now - timedelta(hours=2),
            engine_rpm=1500.0,
        )
        db_session.add(telemetry1)

        # 1 hour gap - should be excluded
        telemetry2 = TelemetryRaw(
            session_id=session_id,
            timestamp=now - timedelta(hours=1),
            engine_rpm=1500.0,
        )
        db_session.add(telemetry2)

        # Normal gap
        telemetry3 = TelemetryRaw(
            session_id=session_id,
            timestamp=now - timedelta(hours=1) + timedelta(minutes=5),
            engine_rpm=1500.0,
        )
        db_session.add(telemetry3)

        db_session.commit()

        hours = calculate_engine_hours(db_session)

        # Should only count the 5-minute interval
        assert hours < 0.2
        assert hours > 0.05

    def test_since_date_filters_correctly(self, app, db_session):
        """since_date parameter filters telemetry."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)
        cutoff = now - timedelta(days=15)

        # Old telemetry (before cutoff)
        for i in range(5):
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=cutoff - timedelta(days=5) + timedelta(minutes=i * 5),
                engine_rpm=1500.0,
            )
            db_session.add(telemetry)

        # Recent telemetry (after cutoff)
        for i in range(5):
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=cutoff + timedelta(days=5) + timedelta(minutes=i * 5),
                engine_rpm=1500.0,
            )
            db_session.add(telemetry)

        db_session.commit()

        # Calculate only recent hours
        recent_hours = calculate_engine_hours(db_session, since_date=cutoff)

        # Should only include recent 5 readings (4 intervals)
        assert recent_hours < 0.5
        assert recent_hours > 0.2

    def test_no_telemetry_returns_zero(self, app, db_session):
        """No telemetry returns 0 hours."""
        hours = calculate_engine_hours(db_session)
        assert hours == 0.0

    def test_handles_null_engine_rpm(self, app, db_session):
        """Null engine_rpm is handled gracefully."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        for i in range(5):
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=now - timedelta(minutes=25 - i * 5),
                engine_rpm=None,  # Null
            )
            db_session.add(telemetry)
        db_session.commit()

        hours = calculate_engine_hours(db_session)

        # Should return 0 since no valid engine RPM
        assert hours == 0.0

    def test_boundary_engine_rpm_threshold(self, app, db_session):
        """Test 400 RPM threshold boundary."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        # Exactly 400 RPM - should not count
        for i in range(5):
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=now - timedelta(minutes=25 + i * 5),
                engine_rpm=400.0,
            )
            db_session.add(telemetry)

        # Just above 400 RPM - should count
        for i in range(5, 10):
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=now - timedelta(minutes=(10 - i) * 5),
                engine_rpm=401.0,
            )
            db_session.add(telemetry)

        db_session.commit()

        hours = calculate_engine_hours(db_session)

        # Should only count the 401 RPM readings
        assert hours > 0


class TestGetMaintenanceSummary:
    """Tests for get_maintenance_summary function."""

    def test_returns_all_maintenance_items(self, app, db_session):
        """Returns summary for all 8 maintenance items."""
        summary = get_maintenance_summary(db_session)

        assert len(summary["maintenance_items"]) == 8
        items = summary["maintenance_items"]
        assert any(item["type"] == "oil_change" for item in items)
        assert any(item["type"] == "tire_rotation" for item in items)
        assert any(item["type"] == "cabin_filter" for item in items)
        assert any(item["type"] == "brake_fluid" for item in items)
        assert any(item["type"] == "coolant_engine" for item in items)
        assert any(item["type"] == "coolant_battery" for item in items)
        assert any(item["type"] == "transmission_fluid" for item in items)
        assert any(item["type"] == "spark_plugs" for item in items)

    def test_calculates_engine_hours(self, app, db_session):
        """Summary includes calculated engine hours."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        # Add some engine running time
        for i in range(12):
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=now - timedelta(minutes=60 - i * 5),
                engine_rpm=1500.0,
            )
            db_session.add(telemetry)
        db_session.commit()

        summary = get_maintenance_summary(db_session)

        # Summary should include total engine hours
        assert "total_engine_hours" in summary
        assert summary["total_engine_hours"] > 0

    def test_includes_last_service_date(self, app, db_session):
        """Summary includes last service date for items."""
        now = datetime.now(timezone.utc)

        # Add maintenance record
        record = MaintenanceRecord(
            maintenance_type="oil_change",
            service_date=now - timedelta(days=200),
            notes="Test service",
        )
        db_session.add(record)
        db_session.commit()

        summary = get_maintenance_summary(db_session)

        oil_item = next(item for item in summary["maintenance_items"] if item["type"] == "oil_change")

        assert oil_item["last_service_date"] is not None
        assert oil_item["has_history"] is True
        # Next due should be calculated
        assert "next_due" in oil_item
        assert "days_remaining" in oil_item["next_due"]

    def test_calculates_next_due_date(self, app, db_session):
        """Summary calculates next due date."""
        now = datetime.now(timezone.utc)

        # Add maintenance record
        record = MaintenanceRecord(
            maintenance_type="tire_rotation",
            service_date=now - timedelta(days=200),
        )
        db_session.add(record)
        db_session.commit()

        summary = get_maintenance_summary(db_session)

        tire_item = next(item for item in summary["maintenance_items"] if item["type"] == "tire_rotation")

        # Tire rotation is mileage-based, so should have miles_remaining
        assert "next_due" in tire_item
        assert "next_due_miles" in tire_item["next_due"]

    def test_no_maintenance_records(self, app, db_session):
        """Works with no maintenance records."""
        summary = get_maintenance_summary(db_session)

        assert len(summary["maintenance_items"]) == 8
        # All items should have no last service date
        for item in summary["maintenance_items"]:
            assert item["last_service_date"] is None


class TestMaintenanceIntervals:
    """Tests for MAINTENANCE_INTERVALS constant."""

    def test_all_intervals_defined(self):
        """All expected maintenance types are defined."""
        expected_types = [
            "oil_change",
            "tire_rotation",
            "cabin_filter",
            "brake_fluid",
            "coolant_engine",
            "coolant_battery",
            "transmission_fluid",
            "spark_plugs",
        ]

        for mtype in expected_types:
            assert mtype in MAINTENANCE_INTERVALS

    def test_oil_change_interval(self):
        """Oil change is 2 years OR 24 engine hours."""
        oil = MAINTENANCE_INTERVALS["oil_change"]

        assert oil["interval_months"] == 24
        assert oil["interval_engine_hours"] == 24
        assert "description" in oil

    def test_all_have_required_fields(self):
        """All intervals have required fields."""
        for mtype, interval in MAINTENANCE_INTERVALS.items():
            # Each interval must have at least one interval type and a description
            assert "description" in interval
            assert "name" in interval
            # Must have at least one interval type
            has_interval = (
                "interval_months" in interval or "interval_miles" in interval or "interval_engine_hours" in interval
            )
            assert has_interval, f"{mtype} has no interval defined"


class TestMaintenanceValidation:
    """Tests for validation and edge cases."""

    def test_negative_engine_hours(self, app, db_session):
        """Negative engine hours should not occur."""
        # This shouldn't happen, but test robustness
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        # Create backwards time sequence (should be filtered)
        telemetry1 = TelemetryRaw(
            session_id=session_id,
            timestamp=now,
            engine_rpm=1500.0,
        )
        telemetry2 = TelemetryRaw(
            session_id=session_id,
            timestamp=now - timedelta(minutes=10),  # Earlier time
            engine_rpm=1500.0,
        )
        db_session.add(telemetry1)
        db_session.add(telemetry2)
        db_session.commit()

        hours = calculate_engine_hours(db_session)

        # Should not count negative duration
        assert hours >= 0
