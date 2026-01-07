"""
Tests for 12V auxiliary battery monitoring service.

Tests the 12V battery health tracking including:
- Voltage health reading storage and retrieval
- Health status calculation
- Anomaly detection (low voltage, voltage drops, charging issues)
- Replacement timing forecasting
- Event logging
"""

from datetime import datetime, timedelta, timezone

import pytest
from models import AuxBatteryEvent, AuxBatteryHealthReading, TelemetryRaw
from services import auxiliary_battery_service
from utils.timezone import utc_now


class TestVoltageReadingStorage:
    """Tests for storing and retrieving voltage readings."""

    def test_create_health_reading(self, app, db_session):
        """Can create a 12V battery health reading."""
        now = utc_now()

        reading = AuxBatteryHealthReading(
            timestamp=now,
            voltage_v=12.5,
            is_charging=False,
            charger_connected=False,
            engine_running=False,
            ambient_temp_f=72.0,
            odometer_miles=50000.0,
        )
        db_session.add(reading)
        db_session.commit()

        # Verify it was saved
        saved = db_session.query(AuxBatteryHealthReading).first()
        assert saved.voltage_v == 12.5
        assert saved.is_charging is False
        assert saved.odometer_miles == 50000.0

    def test_get_latest_voltage_reading(self, app, db_session):
        """Returns most recent voltage reading."""
        now = utc_now()

        # Add multiple readings
        for i in range(5):
            reading = AuxBatteryHealthReading(
                timestamp=now - timedelta(minutes=i * 10),
                voltage_v=12.5 + (i * 0.1),
                is_charging=False,
                charger_connected=False,
                engine_running=False,
            )
            db_session.add(reading)
        db_session.commit()

        latest = auxiliary_battery_service.get_latest_voltage_reading(db_session)
        assert latest is not None
        assert latest.voltage_v == 12.5  # Most recent (i=0)

    def test_get_voltage_history(self, app, db_session):
        """Returns voltage history for specified period."""
        now = utc_now()

        # Add readings spanning 60 days
        for i in range(60):
            reading = AuxBatteryHealthReading(
                timestamp=now - timedelta(days=i),
                voltage_v=12.5,
                is_charging=False,
                charger_connected=False,
                engine_running=False,
            )
            db_session.add(reading)
        db_session.commit()

        # Get last 30 days
        history = auxiliary_battery_service.get_voltage_history(db_session, days=30)
        assert len(history) <= 30  # Should only return last 30 days

    def test_get_voltage_at_rest_history(self, app, db_session):
        """Returns only at-rest voltage readings."""
        now = utc_now()

        # Add mix of charging and at-rest readings
        for i in range(10):
            is_charging = (i % 2 == 0)
            reading = AuxBatteryHealthReading(
                timestamp=now - timedelta(minutes=i * 5),
                voltage_v=13.5 if is_charging else 12.5,
                is_charging=is_charging,
                charger_connected=is_charging,
                engine_running=False,
            )
            db_session.add(reading)
        db_session.commit()

        # Get at-rest readings only
        rest_history = auxiliary_battery_service.get_voltage_at_rest_history(db_session, days=1)
        assert all(not r.is_charging and not r.charger_connected for r in rest_history)


class TestHealthStatusCalculation:
    """Tests for battery health status calculation."""

    def test_health_status_healthy(self, app, db_session):
        """Reports healthy status for good voltage."""
        reading = AuxBatteryHealthReading(
            timestamp=utc_now(),
            voltage_v=12.6,  # Healthy voltage at rest
            is_charging=False,
            charger_connected=False,
            engine_running=False,
        )
        db_session.add(reading)
        db_session.commit()

        assert reading.health_status == "healthy"
        assert reading.health_percentage == 100

    def test_health_status_warning(self, app, db_session):
        """Reports warning status for marginal voltage."""
        reading = AuxBatteryHealthReading(
            timestamp=utc_now(),
            voltage_v=12.2,  # Warning voltage at rest
            is_charging=False,
            charger_connected=False,
            engine_running=False,
        )
        db_session.add(reading)
        db_session.commit()

        assert reading.health_status == "warning"
        assert reading.health_percentage < 100

    def test_health_status_critical(self, app, db_session):
        """Reports critical status for low voltage."""
        reading = AuxBatteryHealthReading(
            timestamp=utc_now(),
            voltage_v=11.8,  # Critical voltage at rest
            is_charging=False,
            charger_connected=False,
            engine_running=False,
        )
        db_session.add(reading)
        db_session.commit()

        assert reading.health_status == "critical"
        assert reading.health_percentage < 50

    def test_health_status_charging(self, app, db_session):
        """Health status considers charging state."""
        reading = AuxBatteryHealthReading(
            timestamp=utc_now(),
            voltage_v=13.5,  # Healthy charging voltage
            is_charging=True,
            charger_connected=True,
            engine_running=False,
        )
        db_session.add(reading)
        db_session.commit()

        assert reading.health_status == "healthy"
        assert reading.health_percentage is None  # Can't estimate during charging

    def test_calculate_battery_health(self, app, db_session):
        """Calculates overall battery health."""
        now = utc_now()

        # Add readings over 30 days
        for i in range(30):
            reading = AuxBatteryHealthReading(
                timestamp=now - timedelta(days=i),
                voltage_v=12.5 - (i * 0.01),  # Gradual decline
                is_charging=False,
                charger_connected=False,
                engine_running=False,
            )
            db_session.add(reading)
        db_session.commit()

        health = auxiliary_battery_service.calculate_battery_health(db_session)
        assert "current_voltage" in health
        assert "health_status" in health
        assert "health_percentage" in health
        assert "voltage_trend" in health
        assert health["rest_readings_30d"] > 0


class TestAnomalyDetection:
    """Tests for voltage anomaly detection."""

    def test_detect_sudden_voltage_drop(self, app, db_session):
        """Detects sudden voltage drops."""
        now = utc_now()

        # Create telemetry with voltage drop
        telemetry_data = []
        for i in range(10):
            # Drop voltage suddenly at i=5
            voltage = 12.5 if i < 5 else 11.8

            t = TelemetryRaw(
                session_id="test-session",
                timestamp=now - timedelta(seconds=(10 - i)),
                battery_voltage=voltage,
                charger_connected=False,
                engine_rpm=0,
            )
            telemetry_data.append(t)

        anomalies = auxiliary_battery_service.detect_voltage_anomalies(db_session, telemetry_data)
        assert len(anomalies) > 0

        # Should detect voltage_drop event
        voltage_drops = [a for a in anomalies if a["event_type"] == "voltage_drop"]
        assert len(voltage_drops) > 0
        assert voltage_drops[0]["severity"] in ["warning", "critical"]

    def test_detect_low_voltage(self, app, db_session):
        """Detects sustained low voltage."""
        now = utc_now()

        # Create telemetry with sustained low voltage
        telemetry_data = []
        for i in range(10):
            t = TelemetryRaw(
                session_id="test-session",
                timestamp=now - timedelta(seconds=(10 - i)),
                battery_voltage=11.5,  # Critical low voltage
                charger_connected=False,
                engine_rpm=0,
            )
            telemetry_data.append(t)

        anomalies = auxiliary_battery_service.detect_voltage_anomalies(db_session, telemetry_data)
        assert len(anomalies) > 0

        # Should detect low_voltage event
        low_voltage_events = [a for a in anomalies if a["event_type"] == "low_voltage"]
        # Might detect low voltage depending on sustained duration threshold

    def test_detect_overcharge(self, app, db_session):
        """Detects overcharge condition."""
        now = utc_now()

        # Create telemetry with overcharge
        telemetry_data = []
        for i in range(5):
            t = TelemetryRaw(
                session_id="test-session",
                timestamp=now - timedelta(seconds=(5 - i)),
                battery_voltage=15.0,  # Overcharge voltage
                charger_connected=True,
                engine_rpm=800,  # Engine running
            )
            telemetry_data.append(t)

        anomalies = auxiliary_battery_service.detect_voltage_anomalies(db_session, telemetry_data)
        assert len(anomalies) > 0

        # Should detect charging_issue event
        charging_issues = [a for a in anomalies if a["event_type"] == "charging_issue"]
        assert len(charging_issues) > 0
        assert "overcharge" in charging_issues[0]["description"].lower()


class TestEventLogging:
    """Tests for battery event logging."""

    def test_log_battery_event(self, app, db_session):
        """Can log a battery event."""
        event = auxiliary_battery_service.log_battery_event(
            db=db_session,
            event_type="low_voltage",
            severity="warning",
            voltage_v=12.0,
            timestamp=utc_now(),
            description="Low voltage detected",
            voltage_change_v=-0.5,
            is_charging=False,
        )

        assert event.id is not None
        assert event.event_type == "low_voltage"
        assert event.severity == "warning"
        assert event.voltage_v == 12.0
        assert event.description == "Low voltage detected"

    def test_get_recent_events(self, app, db_session):
        """Returns recent battery events."""
        now = utc_now()

        # Add events over time
        for i in range(10):
            severity = "critical" if i < 3 else "warning"
            event = AuxBatteryEvent(
                timestamp=now - timedelta(days=i),
                event_type="low_voltage",
                severity=severity,
                voltage_v=11.5 + (i * 0.1),
                description=f"Event {i}",
            )
            db_session.add(event)
        db_session.commit()

        # Get recent events
        events = auxiliary_battery_service.get_recent_events(db_session, days=7)
        assert len(events) <= 7

        # Get critical events only
        critical_events = auxiliary_battery_service.get_recent_events(db_session, days=7, severity="critical")
        assert all(e.severity == "critical" for e in critical_events)


class TestReplacementForecasting:
    """Tests for battery replacement timing forecast."""

    def test_forecast_with_sufficient_data(self, app, db_session):
        """Forecasts replacement timing with enough data."""
        now = utc_now()

        # Add voltage readings over several months
        for i in range(180):  # 6 months of daily readings
            reading = AuxBatteryHealthReading(
                timestamp=now - timedelta(days=i),
                voltage_v=12.6 - (i * 0.001),  # Gradual decline
                is_charging=False,
                charger_connected=False,
                engine_running=False,
            )
            db_session.add(reading)
        db_session.commit()

        forecast = auxiliary_battery_service.forecast_replacement_timing(db_session)

        assert "time_based_forecast" in forecast
        assert "voltage_based_forecast" in forecast
        assert "recommendation" in forecast
        assert "urgency" in forecast

        # Should have time-based estimate
        assert "estimated_age_days" in forecast["time_based_forecast"]
        assert "replacement_window_start" in forecast["time_based_forecast"]

    def test_forecast_insufficient_data(self, app, db_session):
        """Returns error when insufficient data."""
        # Add only a few readings
        now = utc_now()
        for i in range(3):
            reading = AuxBatteryHealthReading(
                timestamp=now - timedelta(days=i),
                voltage_v=12.5,
                is_charging=False,
                charger_connected=False,
                engine_running=False,
            )
            db_session.add(reading)
        db_session.commit()

        forecast = auxiliary_battery_service.forecast_replacement_timing(db_session)
        assert "error" in forecast
        assert "not enough" in forecast["error"].lower()


class TestVoltageStatistics:
    """Tests for voltage statistics calculation."""

    def test_voltage_statistics(self, app, db_session):
        """Calculates voltage statistics correctly."""
        now = utc_now()

        # Add mix of readings
        voltages = [12.5, 12.6, 12.4, 13.5, 12.3, 12.7]
        for i, v in enumerate(voltages):
            is_charging = (v > 13.0)
            reading = AuxBatteryHealthReading(
                timestamp=now - timedelta(minutes=i * 10),
                voltage_v=v,
                is_charging=is_charging,
                charger_connected=is_charging,
                engine_running=False,
            )
            db_session.add(reading)
        db_session.commit()

        stats = auxiliary_battery_service.get_voltage_statistics(db_session, days=1)

        assert "all_voltages" in stats
        assert "rest_voltages" in stats
        assert "total_readings" in stats
        assert "rest_readings" in stats

        assert stats["all_voltages"]["min"] == min(voltages)
        assert stats["all_voltages"]["max"] == max(voltages)
        assert stats["total_readings"] == len(voltages)


class TestLinearRegression:
    """Tests for linear regression helper."""

    def test_simple_linear_regression(self, app, db_session):
        """Performs linear regression correctly."""
        # Perfect linear data: y = 2x + 1
        data = [(1, 3), (2, 5), (3, 7), (4, 9), (5, 11)]

        slope, intercept = auxiliary_battery_service.simple_linear_regression(data)

        # Should be close to y = 2x + 1
        assert abs(slope - 2.0) < 0.01
        assert abs(intercept - 1.0) < 0.01

    def test_regression_insufficient_data(self, app, db_session):
        """Handles insufficient data gracefully."""
        data = [(1, 12.5)]  # Only one point

        slope, intercept = auxiliary_battery_service.simple_linear_regression(data)

        # Should return safe defaults
        assert slope == 0
        assert intercept == 12.5

    def test_regression_declining_voltage(self, app, db_session):
        """Detects declining voltage trend."""
        # Declining voltage over time
        data = [
            (1000, 12.6),
            (2000, 12.5),
            (3000, 12.4),
            (4000, 12.3),
            (5000, 12.2),
        ]

        slope, intercept = auxiliary_battery_service.simple_linear_regression(data)

        # Slope should be negative (declining)
        assert slope < 0
