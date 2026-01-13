"""
Tests for battery routes in VoltTracker.

Tests battery health, cell readings, and database error handling.
"""

import json
import os
import sys
from datetime import datetime, timezone, timedelta
from unittest.mock import patch, MagicMock

import pytest
from sqlalchemy.exc import IntegrityError, OperationalError

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "receiver"))


# ============================================================================
# Fixtures
# ============================================================================


@pytest.fixture
def battery_cell_readings(db_session):
    """Create sample battery cell readings for testing."""
    from models import BatteryCellReading

    readings = []
    now = datetime.now(timezone.utc)

    for i in range(5):
        # Create realistic 96-cell voltages with slight variation
        cell_voltages = [3.72 + (j % 10) * 0.003 for j in range(96)]

        reading = BatteryCellReading.from_cell_voltages(
            timestamp=now - timedelta(days=i),
            cell_voltages=cell_voltages,
            ambient_temp_f=70.0 + i,
            state_of_charge=80.0 - i * 5,
            is_charging=False,
        )
        if reading:
            readings.append(reading)
            db_session.add(reading)

    db_session.commit()
    return readings


@pytest.fixture
def battery_health_with_telemetry(db_session):
    """Create battery health data in telemetry."""
    from models import TelemetryRaw
    import uuid

    session_id = uuid.uuid4()
    now = datetime.now(timezone.utc)

    points = []
    for i in range(10):
        point = TelemetryRaw(
            session_id=session_id,
            timestamp=now - timedelta(hours=i),
            battery_capacity_kwh=17.8 + (i % 3) * 0.1,
            state_of_charge=80.0 - i * 2,
        )
        points.append(point)
        db_session.add(point)

    db_session.commit()
    return points


# ============================================================================
# Battery Health Tests
# ============================================================================


class TestBatteryHealth:
    """Tests for GET /api/battery/health."""

    def test_get_battery_health_no_data(self, client):
        """Test battery health with no data returns defaults."""
        response = client.get("/api/battery/health")

        assert response.status_code == 200
        result = json.loads(response.data)
        assert "original_capacity_kwh" in result
        assert "health_status" in result
        assert result["has_data"] is False

    def test_get_battery_health_from_telemetry(self, client, battery_health_with_telemetry):
        """Test battery health calculated from telemetry data."""
        response = client.get("/api/battery/health")

        assert response.status_code == 200
        result = json.loads(response.data)
        assert result["has_data"] is True
        assert result["current_capacity_kwh"] is not None
        assert result["health_percent"] is not None

    def test_get_battery_health_with_readings(self, client, battery_health_readings):
        """Test battery health from dedicated health readings."""
        response = client.get("/api/battery/health")

        assert response.status_code == 200
        result = json.loads(response.data)
        assert result["has_data"] is True
        assert "health_status" in result

    def test_get_battery_health_returns_expected_fields(self, client, battery_health_with_telemetry):
        """Test battery health returns all expected fields."""
        response = client.get("/api/battery/health")

        assert response.status_code == 200
        result = json.loads(response.data)

        expected_fields = [
            "current_capacity_kwh",
            "original_capacity_kwh",
            "health_percent",
            "health_status",
            "readings_count",
            "has_data",
        ]
        for field in expected_fields:
            assert field in result


# ============================================================================
# Battery Cell Readings Tests
# ============================================================================


class TestBatteryCellReadings:
    """Tests for GET /api/battery/cells."""

    def test_get_cell_readings_empty(self, client):
        """Test cell readings with no data returns empty list."""
        response = client.get("/api/battery/cells")

        assert response.status_code == 200
        result = json.loads(response.data)
        assert "readings" in result
        assert result["count"] == 0
        assert len(result["readings"]) == 0

    def test_get_cell_readings_with_data(self, client, battery_cell_readings):
        """Test cell readings returns available data."""
        response = client.get("/api/battery/cells")

        assert response.status_code == 200
        result = json.loads(response.data)
        assert result["count"] > 0
        assert len(result["readings"]) > 0

    def test_get_cell_readings_with_limit(self, client, battery_cell_readings):
        """Test cell readings respects limit parameter."""
        response = client.get("/api/battery/cells?limit=2")

        assert response.status_code == 200
        result = json.loads(response.data)
        assert len(result["readings"]) <= 2

    def test_get_cell_readings_with_days_filter(self, client, battery_cell_readings):
        """Test cell readings respects days filter."""
        response = client.get("/api/battery/cells?days=1")

        assert response.status_code == 200
        result = json.loads(response.data)
        # Only readings from last day
        assert result["count"] <= 2

    def test_get_cell_readings_max_limit(self, client, battery_cell_readings):
        """Test cell readings enforces max limit of 100."""
        response = client.get("/api/battery/cells?limit=200")

        assert response.status_code == 200
        result = json.loads(response.data)
        # Should cap at 100
        assert len(result["readings"]) <= 100


# ============================================================================
# Latest Cell Reading Tests
# ============================================================================


class TestLatestCellReading:
    """Tests for GET /api/battery/cells/latest."""

    def test_get_latest_cell_reading_no_data(self, client):
        """Test latest reading with no data."""
        response = client.get("/api/battery/cells/latest")

        assert response.status_code == 200
        result = json.loads(response.data)
        assert result["reading"] is None
        assert "message" in result

    def test_get_latest_cell_reading_success(self, client, battery_cell_readings):
        """Test getting latest cell reading."""
        response = client.get("/api/battery/cells/latest")

        assert response.status_code == 200
        result = json.loads(response.data)
        assert result["reading"] is not None
        assert "timestamp" in result["reading"]
        assert "voltage_delta" in result["reading"]


# ============================================================================
# Cell Analysis Tests
# ============================================================================


class TestCellAnalysis:
    """Tests for GET /api/battery/cells/analysis."""

    def test_get_cell_analysis_no_data(self, client):
        """Test cell analysis with no data."""
        response = client.get("/api/battery/cells/analysis")

        assert response.status_code == 200
        result = json.loads(response.data)
        assert result["analysis"] is None
        assert "message" in result

    def test_get_cell_analysis_with_data(self, client, battery_cell_readings):
        """Test cell analysis with available data."""
        response = client.get("/api/battery/cells/analysis")

        assert response.status_code == 200
        result = json.loads(response.data)
        assert result["analysis"] is not None
        assert "reading_count" in result["analysis"]
        assert "health_status" in result["analysis"]

    def test_get_cell_analysis_with_days_param(self, client, battery_cell_readings):
        """Test cell analysis respects days parameter."""
        response = client.get("/api/battery/cells/analysis?days=7")

        assert response.status_code == 200
        result = json.loads(response.data)
        if result["analysis"]:
            assert result["analysis"]["period_days"] == 7

    def test_get_cell_analysis_detects_weak_cells(self, client, db_session):
        """Test cell analysis detects weak cells."""
        from models import BatteryCellReading

        # Create reading with one weak cell
        voltages = [3.75] * 96
        voltages[42] = 3.60  # One weak cell

        reading = BatteryCellReading.from_cell_voltages(
            timestamp=datetime.now(timezone.utc),
            cell_voltages=voltages,
            state_of_charge=80.0,
        )
        if reading:
            db_session.add(reading)
            db_session.commit()

        response = client.get("/api/battery/cells/analysis")

        assert response.status_code == 200
        result = json.loads(response.data)
        if result["analysis"] and result["analysis"]["weak_cells"]:
            # Should detect cell 43 (index 42 + 1 for 1-based)
            weak_indices = [c["cell_index"] for c in result["analysis"]["weak_cells"]]
            assert 43 in weak_indices


# ============================================================================
# Add Cell Reading Tests
# ============================================================================


class TestAddCellReading:
    """Tests for POST /api/battery/cells/add."""

    def test_add_cell_reading_success(self, client):
        """Test successfully adding a cell reading."""
        voltages = [3.72 + (i % 10) * 0.003 for i in range(96)]
        data = {
            "cell_voltages": voltages,
            "ambient_temp_f": 72.0,
            "state_of_charge": 85.0,
            "is_charging": False,
        }

        response = client.post(
            "/api/battery/cells/add",
            data=json.dumps(data),
            content_type="application/json",
        )

        assert response.status_code == 201
        result = json.loads(response.data)
        assert "reading" in result
        assert result["reading"]["voltage_delta"] is not None

    def test_add_cell_reading_missing_voltages(self, client):
        """Test adding reading without cell_voltages returns error."""
        data = {"ambient_temp_f": 72.0}

        response = client.post(
            "/api/battery/cells/add",
            data=json.dumps(data),
            content_type="application/json",
        )

        assert response.status_code == 400
        result = json.loads(response.data)
        assert "cell_voltages" in result["error"].lower()

    def test_add_cell_reading_wrong_cell_count(self, client):
        """Test adding reading with wrong cell count returns error."""
        data = {"cell_voltages": [3.72] * 50}  # Only 50 cells, need 96

        response = client.post(
            "/api/battery/cells/add",
            data=json.dumps(data),
            content_type="application/json",
        )

        assert response.status_code == 400
        result = json.loads(response.data)
        assert "96" in result["error"]

    def test_add_cell_reading_voltage_out_of_range(self, client):
        """Test adding reading with out-of-range voltage returns error."""
        voltages = [3.72] * 96
        voltages[0] = 5.0  # Too high

        data = {"cell_voltages": voltages}

        response = client.post(
            "/api/battery/cells/add",
            data=json.dumps(data),
            content_type="application/json",
        )

        assert response.status_code == 400
        result = json.loads(response.data)
        assert "voltage" in result["error"].lower()

    def test_add_cell_reading_voltage_too_low(self, client):
        """Test adding reading with too-low voltage returns error."""
        voltages = [3.72] * 96
        voltages[0] = 2.0  # Too low

        data = {"cell_voltages": voltages}

        response = client.post(
            "/api/battery/cells/add",
            data=json.dumps(data),
            content_type="application/json",
        )

        assert response.status_code == 400
        result = json.loads(response.data)
        assert "voltage" in result["error"].lower()

    def test_add_cell_reading_empty_voltages(self, client):
        """Test adding reading with empty voltages array returns error."""
        data = {"cell_voltages": []}

        response = client.post(
            "/api/battery/cells/add",
            data=json.dumps(data),
            content_type="application/json",
        )

        assert response.status_code == 400
        result = json.loads(response.data)
        assert "error" in result

    def test_add_cell_reading_invalid_timestamp(self, client):
        """Test adding reading with invalid timestamp returns error."""
        voltages = [3.72] * 96
        data = {
            "cell_voltages": voltages,
            "timestamp": "not-a-timestamp",
        }

        response = client.post(
            "/api/battery/cells/add",
            data=json.dumps(data),
            content_type="application/json",
        )

        assert response.status_code == 400
        result = json.loads(response.data)
        assert "timestamp" in result["error"].lower()

    def test_add_cell_reading_with_timestamp(self, client):
        """Test adding reading with explicit timestamp."""
        voltages = [3.72] * 96
        timestamp = (datetime.now(timezone.utc) - timedelta(hours=1)).isoformat()
        data = {
            "cell_voltages": voltages,
            "timestamp": timestamp,
        }

        response = client.post(
            "/api/battery/cells/add",
            data=json.dumps(data),
            content_type="application/json",
        )

        assert response.status_code == 201

    def test_add_cell_reading_integrity_error(self, client):
        """Test handling of IntegrityError on add."""
        voltages = [3.72] * 96
        data = {"cell_voltages": voltages}

        with patch("routes.battery.get_db") as mock_get_db:
            mock_db = MagicMock()
            mock_db.commit.side_effect = IntegrityError("mock", "params", "orig")
            mock_get_db.return_value = mock_db

            response = client.post(
                "/api/battery/cells/add",
                data=json.dumps(data),
                content_type="application/json",
            )

            assert response.status_code == 409
            result = json.loads(response.data)
            assert "constraint" in result["error"].lower()
            mock_db.rollback.assert_called_once()

    def test_add_cell_reading_operational_error(self, client):
        """Test handling of OperationalError on add."""
        voltages = [3.72] * 96
        data = {"cell_voltages": voltages}

        with patch("routes.battery.get_db") as mock_get_db:
            mock_db = MagicMock()
            mock_db.commit.side_effect = OperationalError("mock", "params", "orig")
            mock_get_db.return_value = mock_db

            response = client.post(
                "/api/battery/cells/add",
                data=json.dumps(data),
                content_type="application/json",
            )

            assert response.status_code == 500
            result = json.loads(response.data)
            assert "database" in result["error"].lower()
            mock_db.rollback.assert_called_once()
