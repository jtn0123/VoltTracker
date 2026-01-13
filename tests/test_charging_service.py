"""
Tests for charging service module.

Tests the charging session detection and finalization including:
- Session finalization with kWh calculation
- Zero SOC edge cases
- Charging type detection
- Power tracking
"""

import uuid
from datetime import datetime, timedelta, timezone

import pytest
from config import Config
from models import ChargingSession, TelemetryRaw
from services.charging_service import (
    detect_and_finalize_charging_session,
    start_charging_session,
    update_charging_session,
)


class TestDetectAndFinalizeChargingSession:
    """Tests for detect_and_finalize_charging_session function."""

    def test_sets_end_time_with_telemetry(self, app, db_session):
        """End time should be set from latest telemetry when provided."""
        now = datetime.now(timezone.utc)

        session = ChargingSession(
            start_time=now - timedelta(hours=2),
            start_soc=20.0,
            is_complete=False,
        )
        db_session.add(session)
        db_session.flush()

        telemetry = TelemetryRaw(
            session_id=uuid.uuid4(),
            timestamp=now,
            state_of_charge=80.0,
        )
        db_session.add(telemetry)
        db_session.flush()

        detect_and_finalize_charging_session(db_session, session, telemetry)

        # End time should match telemetry timestamp (may be normalized to naive UTC)
        assert session.end_time is not None
        # Compare without timezone info since session.end_time may be normalized
        if session.end_time.tzinfo is None:
            assert session.end_time == now.replace(tzinfo=None)
        else:
            assert session.end_time == now
        assert session.end_soc == 80.0
        assert session.is_complete is True

    def test_sets_end_time_to_now_without_telemetry(self, app, db_session):
        """End time should be current time when no telemetry provided."""
        now = datetime.now(timezone.utc)

        session = ChargingSession(
            start_time=now - timedelta(hours=2),
            start_soc=20.0,
            end_soc=80.0,
            is_complete=False,
        )
        db_session.add(session)
        db_session.flush()

        detect_and_finalize_charging_session(db_session, session, None)

        assert session.end_time is not None
        assert session.is_complete is True

    def test_calculates_kwh_added_from_soc(self, app, db_session):
        """kWh added should be calculated from SOC change."""
        now = datetime.now(timezone.utc)

        session = ChargingSession(
            start_time=now - timedelta(hours=2),
            start_soc=20.0,
            end_soc=80.0,  # 60% gain
            is_complete=False,
        )
        db_session.add(session)
        db_session.flush()

        detect_and_finalize_charging_session(db_session, session, None)

        # 60% of battery capacity
        expected_kwh = (60.0 / 100) * Config.BATTERY_CAPACITY_KWH
        assert session.kwh_added == pytest.approx(expected_kwh, rel=0.01)

    def test_calculates_cost_with_electricity_rate(self, app, db_session, monkeypatch):
        """Cost should be calculated when electricity rate is configured."""
        monkeypatch.setattr("config.Config.ELECTRICITY_COST_PER_KWH", 0.15)

        now = datetime.now(timezone.utc)

        session = ChargingSession(
            start_time=now - timedelta(hours=2),
            start_soc=20.0,
            end_soc=80.0,
            is_complete=False,
        )
        db_session.add(session)
        db_session.flush()

        detect_and_finalize_charging_session(db_session, session, None)

        assert session.cost is not None
        assert session.cost_per_kwh == 0.15

    def test_handles_none_active_session(self, app, db_session):
        """Should handle None session gracefully."""
        # Should not raise
        detect_and_finalize_charging_session(db_session, None, None)

    def test_handles_start_soc_zero(self, app, db_session):
        """Session starting at 0% SOC should still calculate kWh correctly."""
        now = datetime.now(timezone.utc)

        session = ChargingSession(
            start_time=now - timedelta(hours=4),
            start_soc=0.0,  # Zero start SOC - tests truthiness bug fix
            end_soc=50.0,
            is_complete=False,
        )
        db_session.add(session)
        db_session.flush()

        detect_and_finalize_charging_session(db_session, session, None)

        # 50% of battery capacity
        expected_kwh = (50.0 / 100) * Config.BATTERY_CAPACITY_KWH
        assert session.kwh_added == pytest.approx(expected_kwh, rel=0.01)

    def test_handles_end_soc_zero(self, app, db_session):
        """Session with 0% end SOC (discharge) should not add kWh."""
        now = datetime.now(timezone.utc)

        session = ChargingSession(
            start_time=now - timedelta(hours=2),
            start_soc=50.0,
            end_soc=0.0,  # Discharged - negative SOC change
            is_complete=False,
        )
        db_session.add(session)
        db_session.flush()

        detect_and_finalize_charging_session(db_session, session, None)

        # Negative SOC change should not set kWh_added
        assert session.kwh_added is None


class TestStartChargingSession:
    """Tests for start_charging_session function."""

    def test_creates_session_with_correct_fields(self, app, db_session):
        """New session should have correct initial values."""
        now = datetime.now(timezone.utc)

        telemetry = TelemetryRaw(
            session_id=uuid.uuid4(),
            timestamp=now,
            state_of_charge=25.0,
            latitude=37.7749,
            longitude=-122.4194,
            charger_power_kw=6.6,
        )
        db_session.add(telemetry)
        db_session.flush()

        session = start_charging_session(db_session, telemetry)

        assert session.start_time == now
        assert session.start_soc == 25.0
        assert session.latitude == 37.7749
        assert session.longitude == -122.4194
        assert session.is_complete is False

    def test_detects_l1_charging(self, app, db_session):
        """Low power charging should be detected as L1."""
        telemetry = TelemetryRaw(
            session_id=uuid.uuid4(),
            timestamp=datetime.now(timezone.utc),
            state_of_charge=25.0,
            charger_power_kw=1.4,  # L1 power
        )
        db_session.add(telemetry)
        db_session.flush()

        session = start_charging_session(db_session, telemetry)

        assert session.charge_type == "L1"

    def test_detects_l2_charging(self, app, db_session):
        """Medium power charging should be detected as L2."""
        telemetry = TelemetryRaw(
            session_id=uuid.uuid4(),
            timestamp=datetime.now(timezone.utc),
            state_of_charge=25.0,
            charger_power_kw=6.6,  # L2 power
        )
        db_session.add(telemetry)
        db_session.flush()

        session = start_charging_session(db_session, telemetry)

        assert session.charge_type == "L2"

    def test_detects_dcfc_charging(self, app, db_session):
        """High power charging should be detected as DCFC."""
        telemetry = TelemetryRaw(
            session_id=uuid.uuid4(),
            timestamp=datetime.now(timezone.utc),
            state_of_charge=25.0,
            charger_power_kw=50.0,  # DCFC power
        )
        db_session.add(telemetry)
        db_session.flush()

        session = start_charging_session(db_session, telemetry)

        assert session.charge_type == "DCFC"

    def test_handles_none_power_values(self, app, db_session):
        """Should handle missing power values gracefully."""
        telemetry = TelemetryRaw(
            session_id=uuid.uuid4(),
            timestamp=datetime.now(timezone.utc),
            state_of_charge=25.0,
            charger_power_kw=None,
            charger_ac_power_kw=None,
        )
        db_session.add(telemetry)
        db_session.flush()

        session = start_charging_session(db_session, telemetry)

        # Should default to L1 when power is None/0
        assert session.charge_type == "L1"


class TestUpdateChargingSession:
    """Tests for update_charging_session function."""

    def test_updates_end_soc(self, app, db_session):
        """End SOC should be updated from telemetry."""
        session = ChargingSession(
            start_time=datetime.now(timezone.utc) - timedelta(hours=1),
            start_soc=25.0,
            is_complete=False,
        )
        db_session.add(session)
        db_session.flush()

        telemetry = TelemetryRaw(
            session_id=uuid.uuid4(),
            timestamp=datetime.now(timezone.utc),
            state_of_charge=60.0,
            charger_power_kw=6.6,
        )
        db_session.add(telemetry)
        db_session.flush()

        update_charging_session(session, telemetry)

        assert session.end_soc == 60.0

    def test_updates_end_soc_when_zero(self, app, db_session):
        """End SOC should be updated even when it's 0."""
        session = ChargingSession(
            start_time=datetime.now(timezone.utc) - timedelta(hours=1),
            start_soc=25.0,
            end_soc=50.0,  # Previous value
            is_complete=False,
        )
        db_session.add(session)
        db_session.flush()

        telemetry = TelemetryRaw(
            session_id=uuid.uuid4(),
            timestamp=datetime.now(timezone.utc),
            state_of_charge=0.0,  # Zero SOC - tests truthiness bug fix
            charger_power_kw=0.0,
        )
        db_session.add(telemetry)
        db_session.flush()

        update_charging_session(session, telemetry)

        # With the fix, 0.0 should be recorded (not skipped due to truthiness)
        assert session.end_soc == 0.0

    def test_updates_peak_power_when_higher(self, app, db_session):
        """Peak power should be updated when current power is higher."""
        session = ChargingSession(
            start_time=datetime.now(timezone.utc) - timedelta(hours=1),
            start_soc=25.0,
            peak_power_kw=5.0,
            is_complete=False,
        )
        db_session.add(session)
        db_session.flush()

        telemetry = TelemetryRaw(
            session_id=uuid.uuid4(),
            timestamp=datetime.now(timezone.utc),
            state_of_charge=60.0,
            charger_power_kw=7.2,  # Higher than current peak
        )
        db_session.add(telemetry)
        db_session.flush()

        update_charging_session(session, telemetry)

        assert session.peak_power_kw == 7.2

    def test_keeps_peak_power_when_lower(self, app, db_session):
        """Peak power should not change when current power is lower."""
        session = ChargingSession(
            start_time=datetime.now(timezone.utc) - timedelta(hours=1),
            start_soc=25.0,
            peak_power_kw=7.2,
            is_complete=False,
        )
        db_session.add(session)
        db_session.flush()

        telemetry = TelemetryRaw(
            session_id=uuid.uuid4(),
            timestamp=datetime.now(timezone.utc),
            state_of_charge=60.0,
            charger_power_kw=5.0,  # Lower than current peak
        )
        db_session.add(telemetry)
        db_session.flush()

        update_charging_session(session, telemetry)

        assert session.peak_power_kw == 7.2

    def test_appends_to_charging_curve(self, app, db_session):
        """Charging curve should be appended with new data points."""
        session = ChargingSession(
            start_time=datetime.now(timezone.utc) - timedelta(hours=1),
            start_soc=25.0,
            charging_curve=[],
            is_complete=False,
        )
        db_session.add(session)
        db_session.flush()

        telemetry = TelemetryRaw(
            session_id=uuid.uuid4(),
            timestamp=datetime.now(timezone.utc),
            state_of_charge=60.0,
            charger_power_kw=6.6,
        )
        db_session.add(telemetry)
        db_session.flush()

        update_charging_session(session, telemetry)

        assert len(session.charging_curve) == 1
        assert session.charging_curve[0]["power_kw"] == 6.6
        assert session.charging_curve[0]["soc"] == 60.0

    def test_initializes_charging_curve_if_none(self, app, db_session):
        """Charging curve should be initialized if None."""
        session = ChargingSession(
            start_time=datetime.now(timezone.utc) - timedelta(hours=1),
            start_soc=25.0,
            charging_curve=None,  # Not initialized
            is_complete=False,
        )
        db_session.add(session)
        db_session.flush()

        telemetry = TelemetryRaw(
            session_id=uuid.uuid4(),
            timestamp=datetime.now(timezone.utc),
            state_of_charge=60.0,
            charger_power_kw=6.6,
        )
        db_session.add(telemetry)
        db_session.flush()

        update_charging_session(session, telemetry)

        assert session.charging_curve is not None
        assert len(session.charging_curve) == 1


class TestChargingCurveMaxSize:
    """Tests for charging curve size limit handling."""

    def test_charging_curve_respects_max_size(self, app, db_session, monkeypatch):
        """Charging curve should respect MAX_CHARGING_CURVE_POINTS."""
        # Set a small max for testing
        monkeypatch.setattr("config.Config.MAX_CHARGING_CURVE_POINTS", 3)

        session = ChargingSession(
            start_time=datetime.now(timezone.utc) - timedelta(hours=1),
            start_soc=25.0,
            is_complete=False,
            charging_curve=[],
        )
        db_session.add(session)
        db_session.flush()

        # Add multiple telemetry points
        for i in range(5):
            telemetry = TelemetryRaw(
                session_id=uuid.uuid4(),
                timestamp=datetime.now(timezone.utc) + timedelta(minutes=i),
                state_of_charge=30.0 + i * 10,
                charger_power_kw=6.6,
            )
            db_session.add(telemetry)
            db_session.flush()
            update_charging_session(session, telemetry)

        # Should have at most max + 1 (allows one more to indicate truncation)
        assert len(session.charging_curve) <= 4  # MAX + 1


class TestFinalizingExceptionHandling:
    """Tests for exception handling in finalize_charging_session."""

    def test_handles_integrity_error(self, app, db_session):
        """IntegrityError during finalization is handled and re-raised."""
        from unittest.mock import patch
        from sqlalchemy.exc import IntegrityError

        session = ChargingSession(
            start_time=datetime.now(timezone.utc) - timedelta(hours=1),
            start_soc=25.0,
            end_soc=80.0,
            is_complete=False,
        )
        db_session.add(session)
        db_session.flush()

        with patch.object(db_session, "commit") as mock_commit:
            mock_commit.side_effect = IntegrityError("INSERT", {}, Exception())

            try:
                detect_and_finalize_charging_session(db_session, session, None)
                assert False, "Should have raised IntegrityError"
            except IntegrityError:
                pass  # Expected

    def test_handles_generic_exception(self, app, db_session):
        """Generic exception during finalization is handled and re-raised."""
        from unittest.mock import patch

        session = ChargingSession(
            start_time=datetime.now(timezone.utc) - timedelta(hours=1),
            start_soc=25.0,
            end_soc=80.0,
            is_complete=False,
        )
        db_session.add(session)
        db_session.flush()

        with patch.object(db_session, "commit") as mock_commit:
            mock_commit.side_effect = Exception("Test error")

            try:
                detect_and_finalize_charging_session(db_session, session, None)
                assert False, "Should have raised Exception"
            except Exception as e:
                assert "Test error" in str(e)
