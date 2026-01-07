"""
Debugging Utilities for VoltTracker Tests

Provides utilities to make debugging tests easier:
- SQL query logging
- Performance profiling
- Enhanced assertions with better error messages
- Request/response debugging
- Database state inspection

Usage:
    # Enable SQL logging for a test
    @with_sql_logging
    def test_something(db_session):
        ...

    # Profile a test function
    @profile_test
    def test_performance():
        ...

    # Use enhanced assertions
    assert_close_enough(actual, expected, tolerance=0.01)
"""

import contextlib
import functools
import json
import logging
import sys
import time
import traceback
from datetime import datetime
from io import StringIO
from typing import Any, Callable, Dict, List, Optional, Union

import sqlalchemy.event
from sqlalchemy import inspect
from sqlalchemy.engine import Engine


# ============================================================================
# SQL Query Logging
# ============================================================================


class SQLQueryLogger:
    """Logs all SQL queries executed during tests."""

    def __init__(self, output_file=None):
        self.queries = []
        self.output_file = output_file or sys.stdout
        self.enabled = False

    def enable(self):
        """Enable query logging."""
        if not self.enabled:
            sqlalchemy.event.listen(Engine, "before_cursor_execute", self._log_query)
            self.enabled = True

    def disable(self):
        """Disable query logging."""
        if self.enabled:
            sqlalchemy.event.remove(Engine, "before_cursor_execute", self._log_query)
            self.enabled = False

    def _log_query(self, conn, cursor, statement, parameters, context, executemany):
        """Log a single query."""
        query_info = {
            "timestamp": datetime.now().isoformat(),
            "statement": statement,
            "parameters": parameters,
        }
        self.queries.append(query_info)

        # Pretty print
        self.output_file.write(f"\n{'='*80}\n")
        self.output_file.write(f"SQL Query at {query_info['timestamp']}:\n")
        self.output_file.write(f"{statement}\n")
        if parameters:
            self.output_file.write(f"Parameters: {parameters}\n")
        self.output_file.write(f"{'='*80}\n")

    def get_queries(self) -> List[Dict[str, Any]]:
        """Get all logged queries."""
        return self.queries

    def clear(self):
        """Clear logged queries."""
        self.queries = []


# Global SQL logger instance
_sql_logger = SQLQueryLogger()


def with_sql_logging(func):
    """Decorator to enable SQL logging for a test."""
    @functools.wraps(func)
    def wrapper(*args, **kwargs):
        _sql_logger.enable()
        _sql_logger.clear()
        try:
            result = func(*args, **kwargs)
            return result
        finally:
            print(f"\n\nTotal queries executed: {len(_sql_logger.get_queries())}")
            _sql_logger.disable()
    return wrapper


@contextlib.contextmanager
def sql_logging():
    """Context manager for SQL logging."""
    _sql_logger.enable()
    _sql_logger.clear()
    try:
        yield _sql_logger
    finally:
        _sql_logger.disable()


# ============================================================================
# Performance Profiling
# ============================================================================


class TestProfiler:
    """Profile test execution time and identify bottlenecks."""

    def __init__(self):
        self.timings = {}
        self.current_section = None
        self.section_start = None

    @contextlib.contextmanager
    def section(self, name: str):
        """Time a section of code."""
        start = time.perf_counter()
        try:
            yield
        finally:
            elapsed = time.perf_counter() - start
            self.timings[name] = elapsed

    def report(self, output_file=sys.stdout):
        """Print profiling report."""
        output_file.write("\n" + "="*80 + "\n")
        output_file.write("TEST PROFILING REPORT\n")
        output_file.write("="*80 + "\n")

        if not self.timings:
            output_file.write("No profiling data collected.\n")
            return

        # Sort by time (slowest first)
        sorted_timings = sorted(
            self.timings.items(),
            key=lambda x: x[1],
            reverse=True
        )

        total_time = sum(self.timings.values())

        for name, elapsed in sorted_timings:
            percentage = (elapsed / total_time * 100) if total_time > 0 else 0
            output_file.write(
                f"{name:50s} {elapsed:8.4f}s ({percentage:5.1f}%)\n"
            )

        output_file.write("="*80 + "\n")
        output_file.write(f"Total time: {total_time:.4f}s\n")
        output_file.write("="*80 + "\n")


def profile_test(func):
    """Decorator to profile a test function."""
    @functools.wraps(func)
    def wrapper(*args, **kwargs):
        profiler = TestProfiler()
        start = time.perf_counter()

        try:
            result = func(*args, **kwargs)
            elapsed = time.perf_counter() - start
            profiler.timings[func.__name__] = elapsed
            return result
        finally:
            profiler.report()

    return wrapper


# ============================================================================
# Enhanced Assertions
# ============================================================================


def assert_close_enough(
    actual: float,
    expected: float,
    tolerance: float = 0.01,
    msg: Optional[str] = None
):
    """
    Assert that two floats are within tolerance.

    Provides better error messages than pytest.approx for debugging.
    """
    diff = abs(actual - expected)
    within_tolerance = diff <= tolerance

    if not within_tolerance:
        error_msg = (
            f"\nValues not within tolerance:\n"
            f"  Expected: {expected}\n"
            f"  Actual:   {actual}\n"
            f"  Diff:     {diff}\n"
            f"  Tolerance: {tolerance}\n"
        )
        if msg:
            error_msg = f"{msg}\n{error_msg}"
        raise AssertionError(error_msg)


def assert_dict_subset(
    actual: Dict[str, Any],
    expected_subset: Dict[str, Any],
    msg: Optional[str] = None
):
    """
    Assert that actual dict contains all keys/values from expected_subset.

    More lenient than dict equality - allows extra keys in actual.
    """
    missing_keys = set(expected_subset.keys()) - set(actual.keys())
    if missing_keys:
        raise AssertionError(
            f"Missing keys in actual dict: {missing_keys}\n"
            f"Expected subset: {expected_subset}\n"
            f"Actual: {actual}"
        )

    mismatched = {}
    for key, expected_value in expected_subset.items():
        actual_value = actual[key]
        if actual_value != expected_value:
            mismatched[key] = {
                "expected": expected_value,
                "actual": actual_value
            }

    if mismatched:
        error_msg = f"\nMismatched values:\n"
        for key, values in mismatched.items():
            error_msg += f"  {key}:\n"
            error_msg += f"    Expected: {values['expected']}\n"
            error_msg += f"    Actual:   {values['actual']}\n"
        if msg:
            error_msg = f"{msg}\n{error_msg}"
        raise AssertionError(error_msg)


def assert_json_equal(actual: str, expected: Union[str, Dict], msg: Optional[str] = None):
    """Assert that two JSON strings are equal, with pretty error messages."""
    actual_data = json.loads(actual) if isinstance(actual, str) else actual
    expected_data = json.loads(expected) if isinstance(expected, str) else expected

    if actual_data != expected_data:
        error_msg = (
            f"\nJSON mismatch:\n"
            f"Expected:\n{json.dumps(expected_data, indent=2)}\n\n"
            f"Actual:\n{json.dumps(actual_data, indent=2)}\n"
        )
        if msg:
            error_msg = f"{msg}\n{error_msg}"
        raise AssertionError(error_msg)


def assert_query_count(
    expected_count: int,
    func: Callable,
    *args,
    **kwargs
):
    """
    Assert that a function executes exactly N SQL queries.

    Useful for detecting N+1 query problems.
    """
    _sql_logger.enable()
    _sql_logger.clear()

    try:
        func(*args, **kwargs)
        actual_count = len(_sql_logger.get_queries())

        if actual_count != expected_count:
            queries = _sql_logger.get_queries()
            error_msg = (
                f"\nUnexpected query count:\n"
                f"  Expected: {expected_count}\n"
                f"  Actual:   {actual_count}\n\n"
                f"Queries executed:\n"
            )
            for i, query in enumerate(queries, 1):
                error_msg += f"\n{i}. {query['statement'][:100]}..."

            raise AssertionError(error_msg)
    finally:
        _sql_logger.disable()


# ============================================================================
# HTTP Request/Response Debugging
# ============================================================================


class RequestDebugger:
    """Debug Flask test client requests and responses."""

    @staticmethod
    def print_request(client, method: str, url: str, **kwargs):
        """Print request details."""
        print("\n" + "="*80)
        print(f"REQUEST: {method.upper()} {url}")
        print("="*80)

        if "json" in kwargs:
            print(f"JSON Body:\n{json.dumps(kwargs['json'], indent=2)}")
        if "data" in kwargs:
            print(f"Data: {kwargs['data']}")
        if "headers" in kwargs:
            print(f"Headers: {kwargs['headers']}")

        print("="*80 + "\n")

    @staticmethod
    def print_response(response):
        """Print response details."""
        print("\n" + "="*80)
        print(f"RESPONSE: {response.status_code} {response.status}")
        print("="*80)

        print(f"Headers: {dict(response.headers)}")

        if response.content_type == "application/json":
            try:
                print(f"JSON Body:\n{json.dumps(response.get_json(), indent=2)}")
            except Exception:
                print(f"Body: {response.get_data(as_text=True)}")
        else:
            body = response.get_data(as_text=True)
            if len(body) < 500:
                print(f"Body: {body}")
            else:
                print(f"Body: {body[:500]}... (truncated)")

        print("="*80 + "\n")


@contextlib.contextmanager
def debug_requests():
    """Context manager to debug all requests in a block."""
    # This would require monkey-patching the test client
    # For now, use RequestDebugger manually
    yield


# ============================================================================
# Database State Inspection
# ============================================================================


class DatabaseInspector:
    """Inspect database state during tests."""

    @staticmethod
    def print_table_counts(db_session, tables=None):
        """Print row counts for all or specified tables."""
        from models import (
            BatteryHealthReading,
            ChargingSession,
            FuelEvent,
            TelemetryRaw,
            Trip,
        )

        if tables is None:
            tables = {
                "Trips": Trip,
                "Telemetry": TelemetryRaw,
                "Charging": ChargingSession,
                "Fuel Events": FuelEvent,
                "Battery Health": BatteryHealthReading,
            }

        print("\n" + "="*80)
        print("DATABASE TABLE COUNTS")
        print("="*80)

        for name, model in tables.items():
            count = db_session.query(model).count()
            print(f"{name:30s} {count:8d} rows")

        print("="*80 + "\n")

    @staticmethod
    def print_trip_summary(db_session, trip_id):
        """Print detailed summary of a trip."""
        from models import Trip

        trip = db_session.query(Trip).filter(Trip.id == trip_id).first()

        if not trip:
            print(f"Trip {trip_id} not found")
            return

        print("\n" + "="*80)
        print(f"TRIP SUMMARY - ID: {trip.id}")
        print("="*80)

        # Use SQLAlchemy inspector
        mapper = inspect(trip)

        for column in mapper.mapper.column_attrs:
            value = getattr(trip, column.key)
            print(f"{column.key:30s} {value}")

        print("="*80 + "\n")

    @staticmethod
    def dump_database_to_json(db_session, output_file: str):
        """Dump entire database state to JSON for debugging."""
        from models import (
            BatteryHealthReading,
            ChargingSession,
            FuelEvent,
            TelemetryRaw,
            Trip,
        )

        data = {
            "trips": [trip.to_dict() for trip in db_session.query(Trip).all()],
            "telemetry": [
                t.to_dict()
                for t in db_session.query(TelemetryRaw).limit(100).all()
            ],
            "charging": [
                c.to_dict() for c in db_session.query(ChargingSession).all()
            ],
            "fuel_events": [f.to_dict() for f in db_session.query(FuelEvent).all()],
            "battery_health": [
                b.to_dict() for b in db_session.query(BatteryHealthReading).all()
            ],
        }

        with open(output_file, "w") as f:
            json.dump(data, f, indent=2, default=str)

        print(f"Database dumped to {output_file}")


# ============================================================================
# Exception Debugging
# ============================================================================


def print_exception_context(e: Exception, context: Dict[str, Any] = None):
    """Print detailed exception information with context."""
    print("\n" + "="*80)
    print("EXCEPTION OCCURRED")
    print("="*80)

    print(f"Type: {type(e).__name__}")
    print(f"Message: {str(e)}")

    if context:
        print("\nContext:")
        for key, value in context.items():
            print(f"  {key}: {value}")

    print("\nTraceback:")
    traceback.print_exc()

    print("="*80 + "\n")


@contextlib.contextmanager
def capture_exceptions(expected_exception=None):
    """
    Context manager to capture and debug exceptions.

    If expected_exception is provided, won't re-raise that exception.
    """
    try:
        yield
    except Exception as e:
        print_exception_context(e)

        if expected_exception and isinstance(e, expected_exception):
            # Expected exception, don't re-raise
            pass
        else:
            # Unexpected exception, re-raise
            raise


# ============================================================================
# Memory Debugging
# ============================================================================


class MemoryTracker:
    """Track memory usage during tests."""

    def __init__(self):
        self.snapshots = []

    def snapshot(self, label: str = ""):
        """Take a memory snapshot."""
        try:
            import psutil
            process = psutil.Process()
            mem_info = process.memory_info()

            self.snapshots.append({
                "label": label,
                "rss_mb": mem_info.rss / 1024 / 1024,
                "vms_mb": mem_info.vms / 1024 / 1024,
            })
        except ImportError:
            print("psutil not installed - memory tracking disabled")

    def report(self):
        """Print memory usage report."""
        if not self.snapshots:
            return

        print("\n" + "="*80)
        print("MEMORY USAGE REPORT")
        print("="*80)

        for snapshot in self.snapshots:
            label = snapshot['label'] or "(unlabeled)"
            print(f"{label:40s} RSS: {snapshot['rss_mb']:8.2f} MB")

        if len(self.snapshots) > 1:
            growth = self.snapshots[-1]['rss_mb'] - self.snapshots[0]['rss_mb']
            print(f"\nTotal memory growth: {growth:8.2f} MB")

        print("="*80 + "\n")
