"""
Locust load testing suite for VoltTracker.

Tests API performance under load to identify bottlenecks and ensure
system stability under high traffic.

Usage:
    # Run with web UI (http://localhost:8089)
    locust -f tests/load/locustfile.py --host=http://localhost:8080

    # Headless mode
    locust -f tests/load/locustfile.py --host=http://localhost:8080 \
           --users 100 --spawn-rate 10 --run-time 5m --headless

    # Test specific scenario
    locust -f tests/load/locustfile.py --host=http://localhost:8080 \
           TripReadOnlyUser --users 50 --spawn-rate 5 --run-time 2m
"""

import json
import random
from datetime import datetime, timedelta
from locust import HttpUser, task, between, SequentialTaskSet


class TripQueryBehavior(SequentialTaskSet):
    """
    Realistic trip query pattern - simulates dashboard usage.
    """

    @task
    def view_recent_trips(self):
        """Get recent trips (most common operation)."""
        self.client.get("/trips?per_page=50", name="/trips [list]")

    @task
    def filter_by_date_range(self):
        """Filter trips by date range."""
        shortcuts = ["last_7_days", "last_30_days", "last_90_days"]
        shortcut = random.choice(shortcuts)
        self.client.get(f"/trips?date_range={shortcut}", name="/trips [date_range]")

    @task
    def filter_gas_only(self):
        """Filter for gas-only trips."""
        self.client.get("/trips?gas_only=true&per_page=50", name="/trips [gas_only]")

    @task
    def view_trip_detail(self):
        """View detailed trip information."""
        # Assume trip IDs 1-1000 exist
        trip_id = random.randint(1, 1000)
        with self.client.get(f"/trips/{trip_id}", name="/trips/:id [detail]", catch_response=True) as response:
            if response.status_code == 404:
                response.success()  # 404 is expected for non-existent trips

    @task
    def get_efficiency_summary(self):
        """Get efficiency summary statistics."""
        self.client.get("/trips/efficiency/summary", name="/trips/efficiency/summary")

    @task
    def get_soc_analysis(self):
        """Get SOC floor analysis."""
        self.client.get("/trips/soc/analysis", name="/trips/soc/analysis")


class BatteryHealthBehavior(SequentialTaskSet):
    """Battery health monitoring pattern."""

    @task
    def view_battery_health(self):
        """Check battery health metrics."""
        self.client.get("/battery/health", name="/battery/health")

    @task
    def view_cell_voltages(self):
        """Get cell voltage distribution."""
        self.client.get("/battery/cell-voltages", name="/battery/cell-voltages")

    @task
    def view_degradation(self):
        """View battery degradation over time."""
        self.client.get("/battery/degradation", name="/battery/degradation")


class ChargingSessionBehavior(SequentialTaskSet):
    """Charging session monitoring."""

    @task
    def view_active_session(self):
        """Check for active charging session."""
        self.client.get("/charging/active", name="/charging/active")

    @task
    def view_session_list(self):
        """Get recent charging sessions."""
        self.client.get("/charging/sessions?limit=20", name="/charging/sessions [list]")

    @task
    def view_charging_summary(self):
        """Get charging summary statistics."""
        self.client.get("/charging/summary", name="/charging/summary")


class ExportBehavior(SequentialTaskSet):
    """Export operations (heavier load)."""

    @task
    def export_trips_csv(self):
        """Export trips as CSV."""
        self.client.get("/export/trips?format=csv&stream=true", name="/export/trips [csv]")

    @task
    def export_trips_json(self):
        """Export trips as JSON."""
        # Limit to avoid huge response
        start_date = (datetime.now() - timedelta(days=30)).strftime("%Y-%m-%d")
        self.client.get(
            f"/export/trips?format=json&start_date={start_date}",
            name="/export/trips [json]"
        )


# ============================================================================
# User Types (Simulated User Behaviors)
# ============================================================================

class TripReadOnlyUser(HttpUser):
    """
    Read-only user viewing trips and statistics.
    Most common user type - represents dashboard viewers.
    Weight: 70% of traffic
    """
    wait_time = between(2, 5)  # Wait 2-5 seconds between requests
    weight = 70

    tasks = [TripQueryBehavior]


class BatteryMonitorUser(HttpUser):
    """
    User monitoring battery health.
    Weight: 15% of traffic
    """
    wait_time = between(5, 10)
    weight = 15

    tasks = [BatteryHealthBehavior]


class ChargingMonitorUser(HttpUser):
    """
    User monitoring charging sessions.
    Weight: 10% of traffic
    """
    wait_time = between(3, 8)
    weight = 10

    tasks = [ChargingSessionBehavior]


class ExportUser(HttpUser):
    """
    User exporting data (occasional heavy operations).
    Weight: 5% of traffic
    """
    wait_time = between(30, 60)  # Longer wait time
    weight = 5

    tasks = [ExportBehavior]


class TelemetryUploadUser(HttpUser):
    """
    Simulated Torque Pro device uploading telemetry.
    Represents active vehicles sending data.
    """
    wait_time = between(1, 3)  # Torque uploads every 1-3 seconds
    weight = 20

    @task
    def upload_telemetry(self):
        """Upload telemetry data."""
        # Generate realistic telemetry payload
        telemetry = {
            "session": f"test-session-{random.randint(1, 10)}",
            "time": datetime.now().isoformat(),
            "kff1006": f"{random.uniform(10, 95):.1f}",  # SOC
            "k2f": f"{random.uniform(0, 100):.1f}",  # Fuel level
            "kd": f"{random.uniform(0, 70):.1f}",  # Speed
            "kc": f"{random.randint(0, 3000)}",  # RPM
            "kff1203": f"{random.uniform(20, 100):.1f}",  # Ambient temp
        }

        # Note: This requires TORQUE_API_TOKEN in practice
        # For load testing, configure auth appropriately
        self.client.get(
            "/torque/upload/test-token",
            params=telemetry,
            name="/torque/upload [telemetry]"
        )


class DashboardRealtimeUser(HttpUser):
    """
    User viewing live dashboard with WebSocket connection.
    Simulates real-time monitoring.
    """
    wait_time = between(5, 15)
    weight = 10

    @task
    def view_dashboard(self):
        """Load dashboard page."""
        self.client.get("/dashboard", name="/dashboard [page]")

    @task
    def check_health(self):
        """Health check (monitoring)."""
        self.client.get("/health", name="/health")


# ============================================================================
# Stress Test Scenarios
# ============================================================================

class StressTestUser(HttpUser):
    """
    Aggressive user for stress testing.
    Creates high concurrent load to find breaking points.
    """
    wait_time = between(0.1, 0.5)  # Minimal wait time
    weight = 100

    @task(10)
    def rapid_trip_list(self):
        """Rapidly request trip list."""
        self.client.get("/trips?per_page=50", name="[STRESS] /trips")

    @task(5)
    def rapid_trip_detail(self):
        """Rapidly request trip details."""
        trip_id = random.randint(1, 100)
        with self.client.get(f"/trips/{trip_id}", name="[STRESS] /trips/:id", catch_response=True) as response:
            if response.status_code == 404:
                response.success()

    @task(3)
    def rapid_stats(self):
        """Rapidly request statistics."""
        self.client.get("/trips/efficiency/summary", name="[STRESS] /efficiency")

    @task(1)
    def rapid_export(self):
        """Occasional heavy export."""
        self.client.get("/export/trips?format=csv&stream=true", name="[STRESS] /export")


# ============================================================================
# Cache Performance Test
# ============================================================================

class CacheTestUser(HttpUser):
    """
    Tests cache effectiveness by repeatedly accessing same data.
    First request = cache miss, subsequent = cache hits.
    """
    wait_time = between(0.5, 2)
    weight = 50

    @task
    def test_cache_effectiveness(self):
        """Access same endpoint repeatedly to measure cache performance."""
        # Use consistent date range to hit cache
        date_range = "last_30_days"

        # First request (likely cache miss)
        with self.client.get(
            f"/trips?date_range={date_range}&per_page=50",
            name="[CACHE] /trips [first]"
        ) as response:
            first_time = response.elapsed.total_seconds()

        # Second request (should be cache hit)
        with self.client.get(
            f"/trips?date_range={date_range}&per_page=50",
            name="[CACHE] /trips [cached]"
        ) as response:
            second_time = response.elapsed.total_seconds()

        # Log cache effectiveness
        if second_time < first_time * 0.5:
            # Cache is working well (50%+ speedup)
            pass
        else:
            # Cache might not be effective
            print(f"Cache might be ineffective: {first_time:.3f}s -> {second_time:.3f}s")


# ============================================================================
# Test Execution Helpers
# ============================================================================

def run_smoke_test():
    """
    Quick smoke test to verify system is working.

    Usage:
        locust -f tests/load/locustfile.py --host=http://localhost:8080 \
               TripReadOnlyUser --users 5 --spawn-rate 1 --run-time 1m --headless
    """
    pass


def run_load_test():
    """
    Standard load test with realistic traffic mix.

    Usage:
        locust -f tests/load/locustfile.py --host=http://localhost:8080 \
               --users 100 --spawn-rate 10 --run-time 10m --headless
    """
    pass


def run_stress_test():
    """
    Stress test to find breaking point.

    Usage:
        locust -f tests/load/locustfile.py --host=http://localhost:8080 \
               StressTestUser --users 500 --spawn-rate 50 --run-time 5m --headless
    """
    pass


def run_spike_test():
    """
    Spike test - sudden traffic increase.

    Usage:
        # Start with low load
        locust -f tests/load/locustfile.py --host=http://localhost:8080 \
               --users 20 --spawn-rate 5 --run-time 2m --headless

        # Then spike to high load
        locust -f tests/load/locustfile.py --host=http://localhost:8080 \
               --users 300 --spawn-rate 100 --run-time 2m --headless
    """
    pass


if __name__ == "__main__":
    print("""
VoltTracker Load Testing Suite

Available test scenarios:
1. Smoke Test:    5 users,  1m  - Quick validation
2. Load Test:    100 users, 10m - Realistic traffic
3. Stress Test:  500 users,  5m - Find breaking point
4. Spike Test:   Ramp from 20 to 300 users
5. Cache Test:    50 users,  5m - Validate caching

Use --help for full Locust options.
    """)
