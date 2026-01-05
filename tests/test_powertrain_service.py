"""
Tests for powertrain service module.

Tests the Volt Gen 2 powertrain mode detection including:
- Operating mode detection from RPM data
- Mode transitions and timeline
- Edge cases with missing/invalid data
- Statistical analysis of powertrain operation
"""

import uuid
from datetime import datetime, timedelta, timezone

from models import TelemetryRaw, Trip
from services.powertrain_service import (
    PowertrainMode,
    analyze_trip_powertrain,
    detect_operating_mode,
    get_powertrain_summary,
)


class TestDetectOperatingMode:
    """Tests for detect_operating_mode function."""

    def test_ev_mode_detection(self):
        """Pure electric mode: motors active, no engine."""
        mode = detect_operating_mode(
            motor_a_rpm=1500.0,
            motor_b_rpm=1000.0,
            generator_rpm=0.0,
            engine_rpm=0.0,
            hv_battery_power_kw=-8.5,
        )
        assert mode == PowertrainMode.EV_MODE

    def test_hold_mode_detection(self):
        """Hold mode: engine + generator running, battery neutral."""
        mode = detect_operating_mode(
            motor_a_rpm=1200.0,
            motor_b_rpm=800.0,
            generator_rpm=1800.0,
            engine_rpm=1500.0,
            hv_battery_power_kw=0.5,  # Slight positive
        )
        assert mode == PowertrainMode.HOLD_MODE

    def test_mountain_mode_detection(self):
        """Mountain mode: engine running, battery charging."""
        mode = detect_operating_mode(
            motor_a_rpm=1200.0,
            motor_b_rpm=800.0,
            generator_rpm=1800.0,
            engine_rpm=1500.0,
            hv_battery_power_kw=-2.5,  # Charging (negative > -1.0)
        )
        assert mode == PowertrainMode.MOUNTAIN_MODE

    def test_engine_direct_detection(self):
        """Engine direct: Motor B + engine, no Motor A."""
        mode = detect_operating_mode(
            motor_a_rpm=0.0,  # Not active
            motor_b_rpm=2000.0,  # Active
            generator_rpm=1800.0,
            engine_rpm=2500.0,
            hv_battery_power_kw=0.2,
        )
        assert mode == PowertrainMode.ENGINE_DIRECT

    def test_hybrid_assist_detection(self):
        """Hybrid assist: all systems active."""
        mode = detect_operating_mode(
            motor_a_rpm=1800.0,
            motor_b_rpm=1800.0,
            generator_rpm=1800.0,
            engine_rpm=2000.0,
            hv_battery_power_kw=-5.0,  # Discharging
        )
        assert mode == PowertrainMode.HYBRID_ASSIST

    def test_unknown_mode_all_off(self):
        """Unknown mode: everything off (parked)."""
        mode = detect_operating_mode(
            motor_a_rpm=0.0,
            motor_b_rpm=0.0,
            generator_rpm=0.0,
            engine_rpm=0.0,
            hv_battery_power_kw=0.0,
        )
        assert mode == PowertrainMode.UNKNOWN

    def test_none_values_handled(self):
        """None values treated as zero."""
        mode = detect_operating_mode(
            motor_a_rpm=None,
            motor_b_rpm=None,
            generator_rpm=None,
            engine_rpm=None,
            hv_battery_power_kw=None,
        )
        assert mode == PowertrainMode.UNKNOWN

    def test_boundary_motor_threshold(self):
        """Test motor active threshold boundary (100 RPM)."""
        # Just below threshold - should be EV mode
        mode = detect_operating_mode(
            motor_a_rpm=99.0,
            motor_b_rpm=99.0,
            generator_rpm=0.0,
            engine_rpm=0.0,
        )
        assert mode == PowertrainMode.UNKNOWN

        # Just above threshold - should be EV mode
        mode = detect_operating_mode(
            motor_a_rpm=101.0,
            motor_b_rpm=101.0,
            generator_rpm=0.0,
            engine_rpm=0.0,
        )
        assert mode == PowertrainMode.EV_MODE

    def test_boundary_engine_threshold(self):
        """Test engine active threshold boundary (400 RPM)."""
        # Just below threshold
        mode = detect_operating_mode(
            motor_a_rpm=1200.0,
            motor_b_rpm=800.0,
            generator_rpm=1800.0,
            engine_rpm=399.0,
        )
        assert mode == PowertrainMode.EV_MODE

        # Just above threshold
        mode = detect_operating_mode(
            motor_a_rpm=1200.0,
            motor_b_rpm=800.0,
            generator_rpm=1800.0,
            engine_rpm=401.0,
        )
        assert mode == PowertrainMode.HOLD_MODE

    def test_negative_rpm_values(self):
        """Negative RPM values treated as zero."""
        mode = detect_operating_mode(
            motor_a_rpm=-100.0,  # Invalid
            motor_b_rpm=-50.0,  # Invalid
            generator_rpm=-200.0,  # Invalid
            engine_rpm=-150.0,  # Invalid
        )
        assert mode == PowertrainMode.UNKNOWN


class TestAnalyzeTripPowertrain:
    """Tests for analyze_trip_powertrain function."""

    def test_analyzes_pure_electric_trip(self, app, db_session):
        """Pure electric trip shows 100% EV mode."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now - timedelta(minutes=30),
            end_time=now,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.flush()

        # Add telemetry - all EV mode
        for i in range(10):
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=now - timedelta(minutes=30 - i * 3),
                motor_a_rpm=1500.0 + i * 10,
                motor_b_rpm=1000.0 + i * 10,
                generator_rpm=0.0,
                engine_rpm=0.0,
                hv_battery_power_kw=-8.0,
            )
            db_session.add(telemetry)
        db_session.commit()

        result = analyze_trip_powertrain(db_session, str(session_id))

        assert result is not None
        assert result["total_samples"] == 10
        assert result["mode_percentages"][PowertrainMode.EV_MODE] == 100.0
        assert result["mode_percentages"][PowertrainMode.HOLD_MODE] == 0.0

    def test_analyzes_mixed_mode_trip(self, app, db_session):
        """Mixed mode trip shows mode distribution."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now - timedelta(minutes=30),
            end_time=now,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.flush()

        # Add telemetry - 5 EV, 5 Hold mode
        for i in range(10):
            if i < 5:
                # EV mode
                telemetry = TelemetryRaw(
                    session_id=session_id,
                    timestamp=now - timedelta(minutes=30 - i * 3),
                    motor_a_rpm=1500.0,
                    motor_b_rpm=1000.0,
                    generator_rpm=0.0,
                    engine_rpm=0.0,
                    hv_battery_power_kw=-8.0,
                )
            else:
                # Hold mode
                telemetry = TelemetryRaw(
                    session_id=session_id,
                    timestamp=now - timedelta(minutes=30 - i * 3),
                    motor_a_rpm=1200.0,
                    motor_b_rpm=800.0,
                    generator_rpm=1800.0,
                    engine_rpm=1500.0,
                    hv_battery_power_kw=0.5,
                )
            db_session.add(telemetry)
        db_session.commit()

        result = analyze_trip_powertrain(db_session, str(session_id))

        assert result is not None
        assert result["total_samples"] == 10
        assert result["mode_percentages"][PowertrainMode.EV_MODE] == 50.0
        assert result["mode_percentages"][PowertrainMode.HOLD_MODE] == 50.0

    def test_detects_mode_transitions(self, app, db_session):
        """Mode transitions are detected in timeline."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now - timedelta(minutes=20),
            end_time=now,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.flush()

        # Create transition: EV -> Hold -> EV
        modes_sequence = [
            (PowertrainMode.EV_MODE, 0, 0, 0, -8.0),  # t=0
            (PowertrainMode.EV_MODE, 0, 0, 0, -8.0),  # t=1
            (PowertrainMode.HOLD_MODE, 1800, 1500, 0.5, None),  # t=2 - transition
            (PowertrainMode.HOLD_MODE, 1800, 1500, 0.5, None),  # t=3
            (PowertrainMode.EV_MODE, 0, 0, -8.0, None),  # t=4 - transition
        ]

        for i, (mode, gen_rpm, eng_rpm, power, _) in enumerate(modes_sequence):
            if mode == PowertrainMode.EV_MODE:
                telemetry = TelemetryRaw(
                    session_id=session_id,
                    timestamp=now - timedelta(minutes=20 - i * 4),
                    motor_a_rpm=1500.0,
                    motor_b_rpm=1000.0,
                    generator_rpm=gen_rpm,
                    engine_rpm=eng_rpm,
                    hv_battery_power_kw=power,
                )
            else:
                telemetry = TelemetryRaw(
                    session_id=session_id,
                    timestamp=now - timedelta(minutes=20 - i * 4),
                    motor_a_rpm=1200.0,
                    motor_b_rpm=800.0,
                    generator_rpm=gen_rpm,
                    engine_rpm=eng_rpm,
                    hv_battery_power_kw=power,
                )
            db_session.add(telemetry)
        db_session.commit()

        result = analyze_trip_powertrain(db_session, str(session_id))

        assert result is not None
        assert len(result["transitions"]) >= 2  # At least 2 transitions

    def test_handles_trip_with_no_telemetry(self, app, db_session):
        """Trip with no telemetry returns None."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.commit()

        result = analyze_trip_powertrain(db_session, str(session_id))

        assert result is None

    def test_handles_invalid_session_id(self, app, db_session):
        """Invalid session ID returns None."""
        result = analyze_trip_powertrain(db_session, "00000000-0000-0000-0000-000000000000")
        assert result is None

    def test_timeline_ordered_chronologically(self, app, db_session):
        """Timeline events are in chronological order."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now - timedelta(minutes=15),
            end_time=now,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.flush()

        # Add telemetry out of order in database
        timestamps = [0, 10, 5, 15, 3]
        for i in timestamps:
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=now - timedelta(minutes=15 - i),
                motor_a_rpm=1500.0,
                motor_b_rpm=1000.0,
                generator_rpm=0.0,
                engine_rpm=0.0,
                hv_battery_power_kw=-8.0,
            )
            db_session.add(telemetry)
        db_session.commit()

        result = analyze_trip_powertrain(db_session, str(session_id))

        assert result is not None
        # Check timeline is sorted
        timeline = result["timeline"]
        for i in range(1, len(timeline)):
            prev_time = datetime.fromisoformat(timeline[i - 1]["timestamp"].replace("Z", "+00:00"))
            curr_time = datetime.fromisoformat(timeline[i]["timestamp"].replace("Z", "+00:00"))
            assert curr_time >= prev_time


class TestGetPowertrainSummary:
    """Tests for get_powertrain_summary function."""

    def test_returns_summary_for_valid_trip(self, app, db_session):
        """Valid trip returns powertrain summary."""
        session_id = uuid.uuid4()
        now = datetime.now(timezone.utc)

        trip = Trip(
            session_id=session_id,
            start_time=now - timedelta(minutes=20),
            end_time=now,
            distance_miles=15.0,
            is_closed=True,
        )
        db_session.add(trip)
        db_session.flush()

        # Add some telemetry
        for i in range(5):
            telemetry = TelemetryRaw(
                session_id=session_id,
                timestamp=now - timedelta(minutes=20 - i * 4),
                motor_a_rpm=1500.0,
                motor_b_rpm=1000.0,
                generator_rpm=0.0,
                engine_rpm=0.0,
            )
            db_session.add(telemetry)
        db_session.commit()

        result = get_powertrain_summary(db_session, str(session_id))

        assert result is not None
        assert "mode_percentages" in result
        assert "total_samples" in result

    def test_handles_nonexistent_trip(self, app, db_session):
        """Nonexistent trip returns None."""
        result = get_powertrain_summary(db_session, "00000000-0000-0000-0000-000000000000")
        assert result is None


class TestPowertrainValidation:
    """Tests for edge cases and validation."""

    def test_high_rpm_values(self):
        """Very high RPM values handled correctly."""
        mode = detect_operating_mode(
            motor_a_rpm=15000.0,  # Very high
            motor_b_rpm=15000.0,
            generator_rpm=8000.0,
            engine_rpm=6000.0,
            hv_battery_power_kw=-20.0,
        )
        # Should still detect a mode (likely hybrid assist)
        assert mode is not None

    def test_zero_power_with_active_motors(self):
        """Zero power with active motors should work."""
        mode = detect_operating_mode(
            motor_a_rpm=1500.0,
            motor_b_rpm=1000.0,
            generator_rpm=0.0,
            engine_rpm=0.0,
            hv_battery_power_kw=0.0,  # Zero power
        )
        assert mode == PowertrainMode.EV_MODE

    def test_mountain_mode_boundary(self):
        """Test mountain mode detection at -1.0 kW boundary."""
        # Exactly -1.0 should be mountain mode
        mode = detect_operating_mode(
            motor_a_rpm=1200.0,
            motor_b_rpm=800.0,
            generator_rpm=1800.0,
            engine_rpm=1500.0,
            hv_battery_power_kw=-1.0,
        )
        assert mode == PowertrainMode.MOUNTAIN_MODE

        # Slightly above -1.0 should be hold mode
        mode = detect_operating_mode(
            motor_a_rpm=1200.0,
            motor_b_rpm=800.0,
            generator_rpm=1800.0,
            engine_rpm=1500.0,
            hv_battery_power_kw=-0.9,
        )
        assert mode == PowertrainMode.HOLD_MODE
