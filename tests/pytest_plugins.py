"""
Custom Pytest Plugins for VoltTracker

Provides custom pytest plugins for enhanced testing:
- Performance tracking
- Slow test detection
- Database query counting
- Custom markers and fixtures
- Enhanced failure reporting

Usage:
    Add to conftest.py:
        pytest_plugins = ['tests.pytest_plugins']
"""

import os
import time
from datetime import datetime
from typing import Dict, List

import pytest


# ============================================================================
# Performance Tracking Plugin
# ============================================================================


class PerformancePlugin:
    """Track test performance and identify slow tests."""

    def __init__(self):
        self.test_durations = []
        self.slow_threshold = float(os.environ.get("SLOW_TEST_THRESHOLD", "1.0"))

    @pytest.hookimpl(hookwrapper=True)
    def pytest_runtest_protocol(self, item):
        """Track test execution time."""
        start = time.perf_counter()
        yield
        duration = time.perf_counter() - start

        self.test_durations.append({
            "name": item.nodeid,
            "duration": duration,
        })

    def pytest_terminal_summary(self, terminalreporter):
        """Print slow test summary."""
        if not self.test_durations:
            return

        slow_tests = [
            t for t in self.test_durations
            if t["duration"] > self.slow_threshold
        ]

        if slow_tests:
            terminalreporter.section("Slow Tests")
            terminalreporter.write_line(
                f"\nTests slower than {self.slow_threshold}s:\n"
            )

            # Sort by duration (slowest first)
            slow_tests.sort(key=lambda x: x["duration"], reverse=True)

            for test in slow_tests[:10]:  # Top 10
                terminalreporter.write_line(
                    f"  {test['duration']:6.2f}s  {test['name']}"
                )


# ============================================================================
# Database Query Counter Plugin
# ============================================================================


class QueryCounterPlugin:
    """Count database queries and detect N+1 problems."""

    def __init__(self):
        self.query_counts = {}
        self.query_threshold = int(os.environ.get("QUERY_COUNT_THRESHOLD", "50"))

    @pytest.hookimpl(hookwrapper=True)
    def pytest_runtest_protocol(self, item):
        """Count queries for each test."""
        # This would integrate with debug_utils.SQLQueryLogger
        # For now, it's a placeholder
        yield

    def pytest_terminal_summary(self, terminalreporter):
        """Print tests with excessive queries."""
        if not self.query_counts:
            return

        high_query_tests = [
            (name, count) for name, count in self.query_counts.items()
            if count > self.query_threshold
        ]

        if high_query_tests:
            terminalreporter.section("High Query Count Tests")
            terminalreporter.write_line(
                f"\nTests with >{self.query_threshold} queries:\n"
            )

            high_query_tests.sort(key=lambda x: x[1], reverse=True)

            for name, count in high_query_tests:
                terminalreporter.write_line(f"  {count:4d} queries  {name}")


# ============================================================================
# Coverage Threshold Plugin
# ============================================================================


class CoverageThresholdPlugin:
    """Enforce coverage thresholds per module."""

    def __init__(self):
        self.thresholds = {
            "utils/": 90,
            "services/": 85,
            "routes/": 75,
            "models.py": 95,
        }

    def pytest_terminal_summary(self, terminalreporter, exitstatus):
        """Check coverage thresholds."""
        # This would integrate with pytest-cov
        # For now, it's a placeholder showing the concept
        pass


# ============================================================================
# Test Categorization Plugin
# ============================================================================


class TestCategorizationPlugin:
    """Categorize tests by type and provide statistics."""

    def __init__(self):
        self.categories = {
            "unit": [],
            "integration": [],
            "api": [],
            "service": [],
            "model": [],
        }

    @pytest.hookimpl(hookwrapper=True)
    def pytest_collection_modifyitems(self, items):
        """Categorize tests based on markers and paths."""
        for item in items:
            # Categorize by file path
            path = str(item.fspath)

            if "test_api" in path or "test_routes" in path:
                self.categories["api"].append(item.nodeid)
            elif "test_service" in path:
                self.categories["service"].append(item.nodeid)
            elif "test_model" in path:
                self.categories["model"].append(item.nodeid)
            elif "test_integration" in path:
                self.categories["integration"].append(item.nodeid)
            else:
                self.categories["unit"].append(item.nodeid)

        yield

    def pytest_terminal_summary(self, terminalreporter):
        """Print test categorization summary."""
        terminalreporter.section("Test Categories")
        terminalreporter.write_line("")

        total = sum(len(tests) for tests in self.categories.values())

        for category, tests in self.categories.items():
            count = len(tests)
            percentage = (count / total * 100) if total > 0 else 0
            terminalreporter.write_line(
                f"  {category:15s} {count:4d} tests ({percentage:5.1f}%)"
            )


# ============================================================================
# Failure Analysis Plugin
# ============================================================================


class FailureAnalysisPlugin:
    """Analyze test failures and provide insights."""

    def __init__(self):
        self.failures = []

    @pytest.hookimpl(hookwrapper=True)
    def pytest_runtest_makereport(self, item, call):
        """Capture test failures."""
        outcome = yield
        report = outcome.get_result()

        if report.when == "call" and report.failed:
            self.failures.append({
                "name": item.nodeid,
                "exception": str(call.excinfo.value) if call.excinfo else "Unknown",
                "traceback": str(call.excinfo.traceback) if call.excinfo else "",
            })

    def pytest_terminal_summary(self, terminalreporter):
        """Print failure analysis."""
        if not self.failures:
            return

        terminalreporter.section("Failure Analysis")
        terminalreporter.write_line(f"\nTotal failures: {len(self.failures)}\n")

        # Group by exception type
        exception_counts = {}
        for failure in self.failures:
            exc_type = failure["exception"].split(":")[0]
            exception_counts[exc_type] = exception_counts.get(exc_type, 0) + 1

        terminalreporter.write_line("Failures by exception type:")
        for exc_type, count in sorted(
            exception_counts.items(),
            key=lambda x: x[1],
            reverse=True
        ):
            terminalreporter.write_line(f"  {count:3d}  {exc_type}")


# ============================================================================
# Fixture Usage Plugin
# ============================================================================


class FixtureUsagePlugin:
    """Track fixture usage across tests."""

    def __init__(self):
        self.fixture_usage = {}

    @pytest.hookimpl(hookwrapper=True)
    def pytest_runtest_setup(self, item):
        """Track which fixtures are used."""
        for fixture_name in item.fixturenames:
            if fixture_name not in self.fixture_usage:
                self.fixture_usage[fixture_name] = 0
            self.fixture_usage[fixture_name] += 1

        yield

    def pytest_terminal_summary(self, terminalreporter):
        """Print fixture usage statistics."""
        if not self.fixture_usage:
            return

        terminalreporter.section("Fixture Usage")
        terminalreporter.write_line("\nMost used fixtures:\n")

        # Sort by usage count
        sorted_fixtures = sorted(
            self.fixture_usage.items(),
            key=lambda x: x[1],
            reverse=True
        )

        for fixture_name, count in sorted_fixtures[:15]:  # Top 15
            terminalreporter.write_line(f"  {count:4d}  {fixture_name}")


# ============================================================================
# Plugin Registration
# ============================================================================


def pytest_configure(config):
    """Register custom plugins."""
    # Register custom markers
    config.addinivalue_line(
        "markers",
        "slow: marks tests as slow (deselect with '-m \"not slow\"')"
    )
    config.addinivalue_line(
        "markers",
        "integration: marks tests as integration tests"
    )
    config.addinivalue_line(
        "markers",
        "requires_network: marks tests that require network access"
    )
    config.addinivalue_line(
        "markers",
        "requires_postgres: marks tests that require PostgreSQL"
    )

    # Register plugins if not running in minimal mode
    if not config.option.collectonly:
        if os.environ.get("PYTEST_ENABLE_PERF_TRACKING", "1") == "1":
            config.pluginmanager.register(PerformancePlugin(), "performance")

        if os.environ.get("PYTEST_ENABLE_QUERY_COUNTING", "0") == "1":
            config.pluginmanager.register(QueryCounterPlugin(), "query_counter")

        if os.environ.get("PYTEST_ENABLE_CATEGORIZATION", "1") == "1":
            config.pluginmanager.register(
                TestCategorizationPlugin(),
                "categorization"
            )

        if os.environ.get("PYTEST_ENABLE_FAILURE_ANALYSIS", "1") == "1":
            config.pluginmanager.register(FailureAnalysisPlugin(), "failure_analysis")

        if os.environ.get("PYTEST_ENABLE_FIXTURE_TRACKING", "0") == "1":
            config.pluginmanager.register(FixtureUsagePlugin(), "fixture_usage")


# ============================================================================
# Custom Fixtures
# ============================================================================


@pytest.fixture
def performance_profiler():
    """Provide a performance profiler for tests."""
    from tests.debug_utils import TestProfiler

    profiler = TestProfiler()
    yield profiler
    profiler.report()


@pytest.fixture
def sql_logger():
    """Provide SQL query logger for tests."""
    from tests.debug_utils import _sql_logger

    _sql_logger.enable()
    _sql_logger.clear()

    yield _sql_logger

    _sql_logger.disable()


@pytest.fixture
def api_helper(client):
    """Provide API test helper."""
    from tests.test_helpers import APITestHelper

    return APITestHelper(client)


@pytest.fixture
def torque_builder():
    """Provide Torque data builder."""
    from tests.test_helpers import TorqueDataBuilder

    return TorqueDataBuilder()


@pytest.fixture
def memory_tracker():
    """Provide memory tracker for tests."""
    from tests.debug_utils import MemoryTracker

    tracker = MemoryTracker()
    tracker.snapshot("start")

    yield tracker

    tracker.snapshot("end")
    tracker.report()


# ============================================================================
# Parametrize Helpers
# ============================================================================


def parametrize_with_names(argnames, argvalues, ids=None):
    """
    Helper to create parametrize decorators with automatic ID generation.

    Usage:
        @parametrize_with_names("soc,expected", [
            (100, "full"),
            (50, "half"),
            (0, "empty"),
        ])
    """
    if ids is None:
        ids = [f"case_{i}" for i in range(len(argvalues))]

    return pytest.mark.parametrize(argnames, argvalues, ids=ids)
