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

import pytest
from models import MaintenanceRecord, TelemetryRaw
from services.maintenance_service import (
    MAINTENANCE_INTERVALS,
    add_maintenance_record,
    calculate_engine_hours,
    get_maintenance_history,
    get_maintenance_summary,
    get_next_due_items,
)


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
                timestamp=now - timedelta(minutes=25 - i * 5),
                engine_rpm=400.0,
            )
            db_session.add(telemetry)

        # Just above 400 RPM - should count
        for i in range(5, 10):
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=now - timedelta(minutes=25 - i * 5),
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

        assert len(summary) == 8
        assert any(item["type"] == "oil_change" for item in summary)
        assert any(item["type"] == "tire_rotation" for item in summary)
        assert any(item["type"] == "cabin_air_filter" for item in summary)
        assert any(item["type"] == "engine_air_filter" for item in summary)
        assert any(item["type"] == "coolant_flush" for item in summary)
        assert any(item["type"] == "brake_fluid" for item in summary)
        assert any(item["type"] == "transmission_fluid" for item in summary)
        assert any(item["type"] == "spark_plugs" for item in summary)

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

        # Find oil change item
        oil_item = next(item for item in summary if item["type"] == "oil_change")

        # Should include engine hours
        assert "current_engine_hours" in oil_item
        assert oil_item["current_engine_hours"] > 0

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

        oil_item = next(item for item in summary if item["type"] == "oil_change")

        assert oil_item["last_service_date"] is not None
        assert "days_since_service" in oil_item
        assert oil_item["days_since_service"] > 190

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

        tire_item = next(item for item in summary if item["type"] == "tire_rotation")

        assert "next_due_date" in tire_item
        # Tire rotation every 12 months
        # Should be due in ~165 days (365 - 200)

    def test_no_maintenance_records(self, app, db_session):
        """Works with no maintenance records."""
        summary = get_maintenance_summary(db_session)

        assert len(summary) == 8
        # All items should have no last service date
        for item in summary:
            assert item["last_service_date"] is None


class TestGetNextDueItems:
    """Tests for get_next_due_items function."""

    def test_returns_overdue_items(self, app, db_session):
        """Returns items that are overdue."""
        now = datetime.now(timezone.utc)

        # Add overdue maintenance
        record = MaintenanceRecord(
            maintenance_type="tire_rotation",
            service_date=now - timedelta(days=400),  # 12 months = 365 days
            next_due_date=now - timedelta(days=35),  # Overdue by 35 days
        )
        db_session.add(record)
        db_session.commit()

        due_items = get_next_due_items(db_session, days_ahead=30)

        # Tire rotation should be in the list (overdue)
        assert any(item["type"] == "tire_rotation" for item in due_items)
        tire_item = next(item for item in due_items if item["type"] == "tire_rotation")
        assert tire_item["days_until_due"] < 0  # Negative means overdue

    def test_returns_upcoming_items(self, app, db_session):
        """Returns items due in next N days."""
        now = datetime.now(timezone.utc)

        # Add maintenance due soon
        record = MaintenanceRecord(
            maintenance_type="cabin_air_filter",
            service_date=now - timedelta(days=700),  # 24 months ago
            next_due_date=now + timedelta(days=20),  # Due in 20 days
        )
        db_session.add(record)
        db_session.commit()

        due_items = get_next_due_items(db_session, days_ahead=30)

        # Should include cabin air filter
        assert any(item["type"] == "cabin_air_filter" for item in due_items)

    def test_excludes_far_future_items(self, app, db_session):
        """Excludes items due beyond days_ahead."""
        now = datetime.now(timezone.utc)

        # Add maintenance due far in future
        record = MaintenanceRecord(
            maintenance_type="spark_plugs",
            service_date=now - timedelta(days=100),
            next_due_date=now + timedelta(days=200),  # Due in 200 days
        )
        db_session.add(record)
        db_session.commit()

        due_items = get_next_due_items(db_session, days_ahead=30)

        # Should not include spark plugs
        assert not any(item["type"] == "spark_plugs" for item in due_items)

    def test_sorted_by_urgency(self, app, db_session):
        """Items sorted by due date (most urgent first)."""
        now = datetime.now(timezone.utc)

        # Add multiple items with different due dates
        records = [
            MaintenanceRecord(
                maintenance_type="tire_rotation",
                service_date=now - timedelta(days=350),
                next_due_date=now + timedelta(days=15),
            ),
            MaintenanceRecord(
                maintenance_type="cabin_air_filter",
                service_date=now - timedelta(days=700),
                next_due_date=now + timedelta(days=5),  # Most urgent
            ),
            MaintenanceRecord(
                maintenance_type="oil_change",
                service_date=now - timedelta(days=600),
                next_due_date=now + timedelta(days=25),
            ),
        ]
        for record in records:
            db_session.add(record)
        db_session.commit()

        due_items = get_next_due_items(db_session, days_ahead=30)

        # First item should be most urgent
        assert due_items[0]["type"] == "cabin_air_filter"


class TestAddMaintenanceRecord:
    """Tests for add_maintenance_record function."""

    def test_adds_record_successfully(self, app, db_session):
        """Successfully adds maintenance record."""
        now = datetime.now(timezone.utc)

        record = add_maintenance_record(
            db_session,
            maintenance_type="oil_change",
            service_date=now,
            mileage=55000.0,
            notes="Synthetic oil",
        )

        assert record is not None
        assert record.maintenance_type == "oil_change"
        assert record.mileage == 55000.0
        assert record.notes == "Synthetic oil"

    def test_calculates_next_due_date(self, app, db_session):
        """Calculates next due date based on interval."""
        now = datetime.now(timezone.utc)

        record = add_maintenance_record(
            db_session,
            maintenance_type="tire_rotation",
            service_date=now,
        )

        # Tire rotation every 12 months
        expected_due = now + timedelta(days=365)

        assert record.next_due_date is not None
        # Allow 1 day tolerance
        assert abs((record.next_due_date - expected_due).days) <= 1

    def test_stores_engine_hours_for_oil_change(self, app, db_session):
        """Oil change records store current engine hours."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        # Add some engine time
        for i in range(12):
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=now - timedelta(hours=1) + timedelta(minutes=i * 5),
                engine_rpm=1500.0,
            )
            db_session.add(telemetry)
        db_session.commit()

        record = add_maintenance_record(
            db_session,
            maintenance_type="oil_change",
            service_date=now,
        )

        assert record.engine_hours is not None
        assert record.engine_hours > 0

    def test_other_maintenance_no_engine_hours(self, app, db_session):
        """Non-oil-change maintenance doesn't store engine hours."""
        now = datetime.now(timezone.utc)

        record = add_maintenance_record(
            db_session,
            maintenance_type="tire_rotation",
            service_date=now,
        )

        assert record.engine_hours is None

    def test_validates_maintenance_type(self, app, db_session):
        """Invalid maintenance type raises ValueError."""
        now = datetime.now(timezone.utc)

        with pytest.raises(ValueError, match="Unknown maintenance type"):
            add_maintenance_record(
                db_session,
                maintenance_type="invalid_type",
                service_date=now,
            )

    def test_stores_optional_fields(self, app, db_session):
        """Optional fields are stored correctly."""
        now = datetime.now(timezone.utc)

        record = add_maintenance_record(
            db_session,
            maintenance_type="brake_fluid",
            service_date=now,
            mileage=60000.0,
            cost=85.50,
            location="Chevy Dealer",
            notes="DOT 4 fluid",
        )

        assert record.mileage == 60000.0
        assert record.cost == 85.50
        assert record.location == "Chevy Dealer"
        assert record.notes == "DOT 4 fluid"


class TestGetMaintenanceHistory:
    """Tests for get_maintenance_history function."""

    def test_returns_all_records(self, app, db_session):
        """Returns all maintenance records."""
        now = datetime.now(timezone.utc)

        # Add multiple records
        for i in range(5):
            record = MaintenanceRecord(
                maintenance_type="oil_change",
                service_date=now - timedelta(days=i * 365),
            )
            db_session.add(record)
        db_session.commit()

        history = get_maintenance_history(db_session)

        assert len(history) == 5

    def test_sorted_by_date_descending(self, app, db_session):
        """Records sorted by date (newest first)."""
        now = datetime.now(timezone.utc)

        # Add records in random order
        dates = [
            now - timedelta(days=100),
            now - timedelta(days=10),  # Newest
            now - timedelta(days=50),
        ]

        for date in dates:
            record = MaintenanceRecord(
                maintenance_type="tire_rotation",
                service_date=date,
            )
            db_session.add(record)
        db_session.commit()

        history = get_maintenance_history(db_session)

        # First should be newest
        assert history[0]["service_date"] is not None
        # Compare timestamps
        for i in range(1, len(history)):
            prev_date = datetime.fromisoformat(history[i - 1]["service_date"].replace("Z", "+00:00"))
            curr_date = datetime.fromisoformat(history[i]["service_date"].replace("Z", "+00:00"))
            assert prev_date >= curr_date

    def test_filters_by_maintenance_type(self, app, db_session):
        """Filters by maintenance_type parameter."""
        now = datetime.now(timezone.utc)

        # Add different types
        MaintenanceRecord(
            maintenance_type="oil_change",
            service_date=now - timedelta(days=100),
        )
        MaintenanceRecord(
            maintenance_type="tire_rotation",
            service_date=now - timedelta(days=50),
        )
        MaintenanceRecord(
            maintenance_type="oil_change",
            service_date=now - timedelta(days=10),
        )

        for record in db_session.new:
            db_session.add(record)
        db_session.commit()

        history = get_maintenance_history(db_session, maintenance_type="oil_change")

        assert len(history) == 2
        assert all(item["maintenance_type"] == "oil_change" for item in history)

    def test_no_records_returns_empty(self, app, db_session):
        """No records returns empty list."""
        history = get_maintenance_history(db_session)
        assert history == []


class TestMaintenanceIntervals:
    """Tests for MAINTENANCE_INTERVALS constant."""

    def test_all_intervals_defined(self):
        """All expected maintenance types are defined."""
        expected_types = [
            "oil_change",
            "tire_rotation",
            "cabin_air_filter",
            "engine_air_filter",
            "coolant_flush",
            "brake_fluid",
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
            assert "interval_months" in interval
            assert "description" in interval
            assert isinstance(interval["interval_months"], int)


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

    def test_very_high_engine_hours(self, app, db_session):
        """Very high engine hours are handled."""
        now = datetime.now(timezone.utc)

        record = add_maintenance_record(
            db_session,
            maintenance_type="oil_change",
            service_date=now,
        )

        # Manually set very high engine hours
        record.engine_hours = 10000.0
        db_session.commit()

        summary = get_maintenance_summary(db_session)

        oil_item = next(item for item in summary if item["type"] == "oil_change")

        # Should handle large values
        assert oil_item is not None

    def test_future_service_date(self, app, db_session):
        """Future service dates are handled."""
        now = datetime.now(timezone.utc)
        future_date = now + timedelta(days=10)

        record = add_maintenance_record(
            db_session,
            maintenance_type="tire_rotation",
            service_date=future_date,
        )

        assert record is not None
        # Next due should be calculated from future date
        assert record.next_due_date > future_date
