"""
Wide Events (Canonical Log Lines) - Structured Logging Utility

Following loggingsucks.com recommendations:
- Emit ONE comprehensive JSON event per request/operation
- Include high-cardinality data (session_ids, user_ids, request_ids)
- Capture full context: business metrics, errors, latencies, feature flags
- Use tail sampling: keep all errors/slow requests, sample successful fast requests

Instead of logging what your code is doing, log what happened to this request.
"""

import random
import time
import uuid
from contextlib import contextmanager
from datetime import datetime
from typing import Any, Dict, Optional

import structlog

# Configure structlog for JSON output
structlog.configure(
    processors=[
        structlog.stdlib.filter_by_level,
        structlog.stdlib.add_logger_name,
        structlog.stdlib.add_log_level,
        structlog.stdlib.PositionalArgumentsFormatter(),
        structlog.processors.TimeStamper(fmt="iso"),
        structlog.processors.StackInfoRenderer(),
        structlog.processors.format_exc_info,
        structlog.processors.UnicodeDecoder(),
        structlog.processors.JSONRenderer(),
    ],
    context_class=dict,
    logger_factory=structlog.stdlib.LoggerFactory(),
    cache_logger_on_first_use=True,
)


class WideEvent:
    """
    Accumulates context throughout an operation, then emits one comprehensive log event.

    Usage:
        event = WideEvent("telemetry_upload", trace_id=session_id)
        event.add_context(session_id="abc123", odometer_miles=50000)
        event.add_business_metric("data_points", 42)

        with event.timer("db_query"):
            db.query(...).all()

        event.add_feature_flags(new_algorithm=True)
        event.emit()
    """

    def __init__(self, operation: str, request_id: Optional[str] = None, trace_id: Optional[str] = None):
        """
        Initialize a wide event for a specific operation.

        Args:
            operation: Name of the operation (e.g., "telemetry_upload")
            request_id: Unique ID for this specific request (auto-generated if not provided)
            trace_id: ID that connects related operations (e.g., session_id for all telemetry uploads)
        """
        self.operation = operation
        self.timers: Dict[str, float] = {}  # Track timer start times
        self.context: Dict[str, Any] = {
            "operation": operation,
            "timestamp": datetime.utcnow().isoformat(),
            "start_time": time.time(),
            "request_id": request_id or str(uuid.uuid4()),
        }

        # Add trace_id if provided (connects related operations)
        if trace_id:
            self.context["trace_id"] = trace_id

        self.logger = structlog.get_logger()

    def add_context(self, **kwargs) -> "WideEvent":
        """Add high-cardinality context fields (session_id, user_id, trip_id, etc.)."""
        self.context.update(kwargs)
        return self

    def add_business_metric(self, key: str, value: Any) -> "WideEvent":
        """Add business metrics (data points processed, miles driven, kWh charged, etc.)."""
        if "business_metrics" not in self.context:
            self.context["business_metrics"] = {}
        self.context["business_metrics"][key] = value
        return self

    def add_technical_metric(self, key: str, value: Any) -> "WideEvent":
        """Add technical metrics (latency, db queries, cache hits, etc.)."""
        if "technical_metrics" not in self.context:
            self.context["technical_metrics"] = {}
        self.context["technical_metrics"][key] = value
        return self

    def add_error(self, error: Exception, **kwargs) -> "WideEvent":
        """Add error details to the event."""
        self.context["error"] = {
            "type": type(error).__name__,
            "message": str(error),
            "details": kwargs,
        }
        self.context["success"] = False
        return self

    def mark_success(self) -> "WideEvent":
        """Mark the operation as successful."""
        self.context["success"] = True
        return self

    def mark_failure(self, reason: str) -> "WideEvent":
        """Mark the operation as failed."""
        self.context["success"] = False
        self.context["failure_reason"] = reason
        return self

    def add_feature_flags(self, **flags) -> "WideEvent":
        """Add feature flag states."""
        if "feature_flags" not in self.context:
            self.context["feature_flags"] = {}
        self.context["feature_flags"].update(flags)
        return self

    @contextmanager
    def timer(self, operation_name: str):
        """
        Context manager to time a specific operation within the request.

        Usage:
            with event.timer("db_query"):
                db.query(...).all()

            with event.timer("weather_api"):
                fetch_weather()

            # Outputs: {"performance_breakdown": {"db_query_ms": 45.2, "weather_api_ms": 342.5}}
        """
        start = time.time()
        try:
            yield
        finally:
            duration_ms = (time.time() - start) * 1000
            if "performance_breakdown" not in self.context:
                self.context["performance_breakdown"] = {}
            self.context["performance_breakdown"][f"{operation_name}_ms"] = round(duration_ms, 2)

    def set_duration(self) -> "WideEvent":
        """Calculate and set the duration of the operation."""
        if "start_time" in self.context:
            duration_ms = (time.time() - self.context["start_time"]) * 1000
            self.context["duration_ms"] = round(duration_ms, 2)
            del self.context["start_time"]  # Remove start_time from final log
        return self

    def should_emit(self, sample_rate: float = 0.05, slow_threshold_ms: float = 1000) -> bool:
        """
        Implement tail sampling logic:
        - Always emit errors
        - Always emit slow requests (>slow_threshold_ms)
        - Always emit critical business events (trip_created, gas_mode_transition, etc.)
        - Sample successful fast requests at sample_rate (default 5%)
        """
        # Always log errors
        if not self.context.get("success", True):
            return True

        # Always log slow requests
        if self.context.get("duration_ms", 0) > slow_threshold_ms:
            return True

        # Always log critical business events
        business_metrics = self.context.get("business_metrics", {})
        critical_events = [
            "trip_created",
            "gas_mode_entered",
            "charging_session_started",
            "refuel_detected",
        ]
        if any(business_metrics.get(event) for event in critical_events):
            return True

        # Sample successful fast requests
        return random.random() < sample_rate

    def emit(self, level: str = "info", force: bool = False) -> None:
        """
        Emit the wide event as a single comprehensive log line.

        Args:
            level: Log level (info, warning, error)
            force: Force emission even if sampling says no
        """
        self.set_duration()

        # Apply tail sampling unless forced
        if not force and not self.should_emit():
            return

        # Select log level
        log_method = getattr(self.logger, level, self.logger.info)

        # Emit the comprehensive event
        log_method(
            f"{self.operation}_complete",
            **self.context,
        )


@contextmanager
def track_operation(operation: str, **initial_context):
    """
    Context manager for tracking an operation with a wide event.

    Usage:
        with track_operation("telemetry_upload", session_id="abc123") as event:
            # Do work
            event.add_business_metric("data_points", 42)
            # Event emits automatically on exit
    """
    event = WideEvent(operation)
    event.add_context(**initial_context)

    try:
        yield event
        event.mark_success()
    except Exception as e:
        event.add_error(e)
        event.mark_failure(str(e))
        raise
    finally:
        # Emit regardless of success/failure
        event.emit(level="error" if not event.context.get("success", True) else "info", force=True)


# Convenience functions for common operations


def log_telemetry_upload(
    session_id: str,
    data_points: int,
    duration_ms: float,
    success: bool,
    **kwargs,
) -> None:
    """Log a telemetry upload event."""
    event = WideEvent("telemetry_upload")
    event.add_context(session_id=session_id, **kwargs)
    event.add_business_metric("data_points", data_points)
    event.context["duration_ms"] = duration_ms

    if success:
        event.mark_success()
    else:
        event.mark_failure(kwargs.get("error", "Unknown error"))

    event.emit(force=True)


def log_trip_event(
    trip_id: int,
    session_id: str,
    operation: str,
    success: bool,
    **kwargs,
) -> None:
    """Log a trip-related event (creation, finalization, etc.)."""
    event = WideEvent(f"trip_{operation}")
    event.add_context(trip_id=trip_id, session_id=session_id, **kwargs)

    if success:
        event.mark_success()
    else:
        event.mark_failure(kwargs.get("error", "Unknown error"))

    event.emit(force=True)


def log_charging_event(
    session_id: Optional[int],
    operation: str,
    success: bool,
    **kwargs,
) -> None:
    """Log a charging session event."""
    event = WideEvent(f"charging_{operation}")
    if session_id:
        event.add_context(charging_session_id=session_id)
    event.add_context(**kwargs)

    if success:
        event.mark_success()
    else:
        event.mark_failure(kwargs.get("error", "Unknown error"))

    event.emit(force=True)
