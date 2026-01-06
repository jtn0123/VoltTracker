"""
Tests for wide events (canonical log lines) utility.

Tests the structured logging patterns including:
- WideEvent creation and context management
- Timer and performance breakdown
- Tail sampling logic
- Convenience logging functions
"""

import time
from unittest.mock import patch

import pytest
from utils.wide_events import (
    WideEvent,
    log_charging_event,
    log_telemetry_upload,
    log_trip_event,
    track_operation,
)


class TestWideEvent:
    """Tests for WideEvent class."""

    def test_creates_event_with_defaults(self):
        """Creates event with default values."""
        event = WideEvent(operation="test_operation")

        assert event.context["operation"] == "test_operation"
        assert "timestamp" in event.context
        assert "start_time" in event.context
        assert "request_id" in event.context
        assert "service" in event.context

    def test_creates_event_with_trace_id(self):
        """Creates event with trace ID for related operations."""
        event = WideEvent(operation="test_op", trace_id="abc123")

        assert event.context["trace_id"] == "abc123"

    def test_add_context(self):
        """Adds context fields."""
        event = WideEvent(operation="test")
        event.add_context(session_id="sess123", odometer=50000)

        assert event.context["session_id"] == "sess123"
        assert event.context["odometer"] == 50000

    def test_add_business_metric(self):
        """Adds business metrics under business_metrics key."""
        event = WideEvent(operation="test")
        event.add_business_metric("data_points", 42)
        event.add_business_metric("miles_driven", 25.5)

        assert event.context["business_metrics"]["data_points"] == 42
        assert event.context["business_metrics"]["miles_driven"] == 25.5

    def test_add_technical_metric(self):
        """Adds technical metrics under technical_metrics key."""
        event = WideEvent(operation="test")
        event.add_technical_metric("db_queries", 3)
        event.add_technical_metric("cache_hits", 5)

        assert event.context["technical_metrics"]["db_queries"] == 3
        assert event.context["technical_metrics"]["cache_hits"] == 5

    def test_add_error_regular_exception(self):
        """Adds error details for regular exception."""
        event = WideEvent(operation="test")
        error = ValueError("Something went wrong")
        event.add_error(error, extra_info="test")

        assert event.context["error"]["type"] == "ValueError"
        assert event.context["error"]["message"] == "Something went wrong"
        assert event.context["error"]["details"]["extra_info"] == "test"
        assert event.context["success"] is False

    def test_add_error_structured_error(self, app):
        """Adds error details for StructuredError."""
        from utils.error_codes import ErrorCode, StructuredError

        event = WideEvent(operation="test")
        error = StructuredError(ErrorCode.E400_TRIP_NOT_FOUND, "Trip missing")
        event.add_error(error)

        assert "code" in event.context["error"]
        assert event.context["success"] is False

    def test_mark_success(self):
        """Marks operation as successful."""
        event = WideEvent(operation="test")
        event.mark_success()

        assert event.context["success"] is True

    def test_mark_failure(self):
        """Marks operation as failed."""
        event = WideEvent(operation="test")
        event.mark_failure("Connection timeout")

        assert event.context["success"] is False
        assert event.context["failure_reason"] == "Connection timeout"

    def test_add_feature_flags(self):
        """Adds feature flag states."""
        event = WideEvent(operation="test")
        event.add_feature_flags(new_algorithm=True, dark_mode=False)

        assert event.context["feature_flags"]["new_algorithm"] is True
        assert event.context["feature_flags"]["dark_mode"] is False

    def test_add_vehicle_context(self):
        """Adds vehicle context for enriched debugging."""
        event = WideEvent(operation="test")
        event.add_vehicle_context(
            total_trips=100,
            total_miles=3000.0,
            usage_tier="heavy",
        )

        assert event.context["vehicle_context"]["total_trips"] == 100
        assert event.context["vehicle_context"]["usage_tier"] == "heavy"

    def test_timer_context_manager(self):
        """Timer measures operation duration."""
        event = WideEvent(operation="test")

        with event.timer("slow_operation"):
            time.sleep(0.01)  # 10ms

        assert "performance_breakdown" in event.context
        assert "slow_operation_ms" in event.context["performance_breakdown"]
        assert event.context["performance_breakdown"]["slow_operation_ms"] >= 10

    def test_set_duration(self):
        """Set duration calculates total operation time."""
        event = WideEvent(operation="test")
        time.sleep(0.01)  # 10ms
        event.set_duration()

        assert "duration_ms" in event.context
        assert event.context["duration_ms"] >= 10
        assert "start_time" not in event.context  # Removed after calculation


class TestShouldEmit:
    """Tests for should_emit sampling logic."""

    def test_always_emits_errors(self):
        """Always emits when success is False."""
        event = WideEvent(operation="test")
        event.context["success"] = False

        assert event.should_emit() is True

    def test_always_emits_slow_requests(self):
        """Always emits when duration exceeds threshold."""
        event = WideEvent(operation="test")
        event.context["duration_ms"] = 2000  # 2 seconds
        event.mark_success()

        assert event.should_emit(slow_threshold_ms=1000) is True

    def test_always_emits_critical_business_events(self):
        """Always emits for critical business events."""
        event = WideEvent(operation="test")
        event.add_business_metric("trip_created", True)
        event.mark_success()
        event.context["duration_ms"] = 10  # Fast

        assert event.should_emit() is True

    def test_always_emits_heavy_users(self):
        """Always emits for heavy users (VIP tier)."""
        event = WideEvent(operation="test")
        event.add_vehicle_context(usage_tier="heavy")
        event.mark_success()
        event.context["duration_ms"] = 10  # Fast

        assert event.should_emit() is True

    @patch("utils.wide_events.random.random")
    def test_samples_new_users_at_25_percent(self, mock_random):
        """Samples new users (< 30 days) at 25%."""
        event = WideEvent(operation="test")
        event.add_vehicle_context(account_age_days=15)
        event.mark_success()
        event.context["duration_ms"] = 10  # Fast

        # Under 0.25 -> should emit
        mock_random.return_value = 0.1
        assert event.should_emit() is True

        # Over 0.25 -> should not emit
        mock_random.return_value = 0.3
        assert event.should_emit() is False

    @patch("utils.wide_events.random.random")
    def test_samples_normal_requests_at_configured_rate(self, mock_random):
        """Samples normal requests at configured sample rate."""
        event = WideEvent(operation="test")
        event.mark_success()
        event.context["duration_ms"] = 10  # Fast

        # Under 5% -> should emit
        mock_random.return_value = 0.01
        assert event.should_emit(sample_rate=0.05) is True

        # Over 5% -> should not emit
        mock_random.return_value = 0.1
        assert event.should_emit(sample_rate=0.05) is False


class TestEmit:
    """Tests for emit method."""

    @patch("structlog.get_logger")
    def test_emits_event(self, mock_get_logger):
        """Emits event with all context."""
        mock_logger = mock_get_logger.return_value

        event = WideEvent(operation="test_op")
        event.mark_success()
        event.emit(force=True)

        mock_logger.info.assert_called_once()


class TestTrackOperation:
    """Tests for track_operation context manager."""

    @patch("structlog.get_logger")
    def test_tracks_successful_operation(self, mock_get_logger):
        """Tracks successful operation and emits event."""
        mock_logger = mock_get_logger.return_value

        with track_operation("test_op", session_id="abc123") as event:
            event.add_business_metric("items_processed", 10)

        # Should emit success
        mock_logger.info.assert_called()

    @patch("structlog.get_logger")
    def test_tracks_failed_operation(self, mock_get_logger):
        """Tracks failed operation and emits error event."""
        mock_logger = mock_get_logger.return_value

        with pytest.raises(ValueError):
            with track_operation("test_op") as event:
                raise ValueError("Test error")

        # Should emit error
        mock_logger.error.assert_called()


class TestLogTelemetryUpload:
    """Tests for log_telemetry_upload convenience function."""

    @patch("structlog.get_logger")
    def test_logs_successful_upload(self, mock_get_logger):
        """Logs successful telemetry upload."""
        mock_logger = mock_get_logger.return_value

        log_telemetry_upload(
            session_id="sess123",
            data_points=42,
            duration_ms=150.5,
            success=True,
        )

        mock_logger.info.assert_called()

    @patch("structlog.get_logger")
    def test_logs_failed_upload(self, mock_get_logger):
        """Logs failed telemetry upload."""
        mock_logger = mock_get_logger.return_value

        log_telemetry_upload(
            session_id="sess123",
            data_points=0,
            duration_ms=50.0,
            success=False,
            error="Connection timeout",
        )

        mock_logger.info.assert_called()


class TestLogTripEvent:
    """Tests for log_trip_event convenience function."""

    @patch("structlog.get_logger")
    def test_logs_successful_trip_event(self, mock_get_logger):
        """Logs successful trip event."""
        mock_logger = mock_get_logger.return_value

        log_trip_event(
            trip_id=123,
            session_id="sess456",
            operation="finalize",
            success=True,
            distance_miles=25.5,
        )

        mock_logger.info.assert_called()

    @patch("structlog.get_logger")
    def test_logs_failed_trip_event(self, mock_get_logger):
        """Logs failed trip event."""
        mock_logger = mock_get_logger.return_value

        log_trip_event(
            trip_id=123,
            session_id="sess456",
            operation="create",
            success=False,
            error="Database error",
        )

        mock_logger.info.assert_called()


class TestLogChargingEvent:
    """Tests for log_charging_event convenience function."""

    @patch("structlog.get_logger")
    def test_logs_charging_event_with_session(self, mock_get_logger):
        """Logs charging event with session ID."""
        mock_logger = mock_get_logger.return_value

        log_charging_event(
            session_id=789,
            operation="complete",
            success=True,
            kwh_added=12.5,
        )

        mock_logger.info.assert_called()

    @patch("structlog.get_logger")
    def test_logs_charging_event_without_session(self, mock_get_logger):
        """Logs charging event without session ID."""
        mock_logger = mock_get_logger.return_value

        log_charging_event(
            session_id=None,
            operation="detected",
            success=True,
        )

        mock_logger.info.assert_called()

    @patch("structlog.get_logger")
    def test_logs_failed_charging_event(self, mock_get_logger):
        """Logs failed charging event."""
        mock_logger = mock_get_logger.return_value

        log_charging_event(
            session_id=789,
            operation="finalize",
            success=False,
            error="Invalid SOC data",
        )

        mock_logger.info.assert_called()
