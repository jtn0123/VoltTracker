"""Tests for battery cell voltage tracking functionality."""

from datetime import datetime, timezone
from models import BatteryCellReading


class TestBatteryCellReadingModel:
    """Tests for the BatteryCellReading model."""

    def test_from_cell_voltages_basic(self):
        """Test creating a reading from cell voltages."""
        voltages = [3.8] * 96  # 96 cells at 3.8V
        reading = BatteryCellReading.from_cell_voltages(
            timestamp=datetime.now(timezone.utc),
            cell_voltages=voltages
        )

        assert reading is not None
        assert reading.min_voltage == 3.8
        assert reading.max_voltage == 3.8
        assert reading.avg_voltage == 3.8
        assert reading.voltage_delta == 0.0

    def test_from_cell_voltages_with_variation(self):
        """Test voltage delta calculation with cell variation."""
        voltages = [3.7] * 48 + [3.9] * 48  # Half at 3.7V, half at 3.9V
        reading = BatteryCellReading.from_cell_voltages(
            timestamp=datetime.now(timezone.utc),
            cell_voltages=voltages
        )

        assert reading is not None
        assert reading.min_voltage == 3.7
        assert reading.max_voltage == 3.9
        assert reading.voltage_delta == 0.2
        assert abs(reading.avg_voltage - 3.8) < 0.01

    def test_from_cell_voltages_module_averages(self):
        """Test module average calculations."""
        # Module 1: 3.7V, Module 2: 3.8V, Module 3: 3.9V
        voltages = [3.7] * 32 + [3.8] * 32 + [3.9] * 32
        reading = BatteryCellReading.from_cell_voltages(
            timestamp=datetime.now(timezone.utc),
            cell_voltages=voltages
        )

        assert reading is not None
        assert reading.module1_avg == 3.7
        assert reading.module2_avg == 3.8
        assert reading.module3_avg == 3.9

    def test_from_cell_voltages_with_context(self):
        """Test reading with environmental context."""
        voltages = [3.8] * 96
        reading = BatteryCellReading.from_cell_voltages(
            timestamp=datetime.now(timezone.utc),
            cell_voltages=voltages,
            ambient_temp_f=72.5,
            state_of_charge=80.0,
            is_charging=True
        )

        assert reading is not None
        assert reading.ambient_temp_f == 72.5
        assert reading.state_of_charge == 80.0
        assert reading.is_charging is True

    def test_from_cell_voltages_empty_list(self):
        """Test that empty voltage list returns None."""
        reading = BatteryCellReading.from_cell_voltages(
            timestamp=datetime.now(timezone.utc),
            cell_voltages=[]
        )
        assert reading is None

    def test_from_cell_voltages_all_none(self):
        """Test that all-None voltage list returns None."""
        reading = BatteryCellReading.from_cell_voltages(
            timestamp=datetime.now(timezone.utc),
            cell_voltages=[None] * 96
        )
        assert reading is None

    def test_from_cell_voltages_partial_none(self):
        """Test handling of some None values."""
        voltages = [3.8] * 48 + [None] * 48
        reading = BatteryCellReading.from_cell_voltages(
            timestamp=datetime.now(timezone.utc),
            cell_voltages=voltages
        )

        assert reading is not None
        assert reading.min_voltage == 3.8
        assert reading.max_voltage == 3.8

    def test_to_dict(self):
        """Test to_dict serialization."""
        voltages = [3.8] * 96
        reading = BatteryCellReading.from_cell_voltages(
            timestamp=datetime.now(timezone.utc),
            cell_voltages=voltages,
            state_of_charge=75.0
        )

        d = reading.to_dict()
        assert 'timestamp' in d
        assert 'cell_voltages' in d
        assert 'min_voltage' in d
        assert 'max_voltage' in d
        assert 'voltage_delta' in d
        assert d['state_of_charge'] == 75.0


class TestBatteryCellEndpoints:
    """Tests for battery cell API endpoints."""

    def test_get_cells_empty(self, client, db_session):
        """Test getting cells when none exist."""
        response = client.get('/api/battery/cells')
        assert response.status_code == 200
        data = response.get_json()
        assert data['readings'] == []
        assert data['count'] == 0

    def test_add_cell_reading(self, client, db_session):
        """Test adding a cell voltage reading."""
        response = client.post('/api/battery/cells/add', json={
            'cell_voltages': [3.8] * 96,
            'state_of_charge': 80.0,
            'ambient_temp_f': 70.0
        })

        assert response.status_code == 201
        data = response.get_json()
        assert 'reading' in data
        assert data['reading']['avg_voltage'] == 3.8
        assert data['reading']['voltage_delta'] == 0.0

    def test_add_cell_reading_no_voltages(self, client, db_session):
        """Test adding reading without voltages fails."""
        response = client.post('/api/battery/cells/add', json={
            'state_of_charge': 80.0
        })

        assert response.status_code == 400
        assert b'cell_voltages' in response.data

    def test_add_cell_reading_empty_voltages(self, client, db_session):
        """Test adding reading with empty voltages fails."""
        response = client.post('/api/battery/cells/add', json={
            'cell_voltages': []
        })

        assert response.status_code == 400

    def test_get_latest_cell_reading(self, client, db_session):
        """Test getting the latest cell reading."""
        # Add a reading first
        client.post('/api/battery/cells/add', json={
            'cell_voltages': [3.8] * 96
        })

        response = client.get('/api/battery/cells/latest')
        assert response.status_code == 200
        data = response.get_json()
        assert data['reading'] is not None
        assert data['reading']['avg_voltage'] == 3.8

    def test_get_latest_cell_reading_empty(self, client, db_session):
        """Test getting latest reading when none exist."""
        response = client.get('/api/battery/cells/latest')
        assert response.status_code == 200
        data = response.get_json()
        assert data['reading'] is None

    def test_get_cell_readings_with_limit(self, client, db_session):
        """Test getting multiple readings with limit."""
        # Add 3 readings
        for i in range(3):
            client.post('/api/battery/cells/add', json={
                'cell_voltages': [3.7 + i * 0.1] * 96
            })

        response = client.get('/api/battery/cells?limit=2')
        assert response.status_code == 200
        data = response.get_json()
        assert data['count'] == 2
        assert len(data['readings']) == 2

    def test_get_cell_analysis_empty(self, client, db_session):
        """Test cell analysis with no data."""
        response = client.get('/api/battery/cells/analysis')
        assert response.status_code == 200
        data = response.get_json()
        assert data['analysis'] is None

    def test_get_cell_analysis_with_data(self, client, db_session):
        """Test cell analysis with readings."""
        # Add a reading with some variation
        voltages = [3.7] * 32 + [3.8] * 32 + [3.9] * 32
        client.post('/api/battery/cells/add', json={
            'cell_voltages': voltages
        })

        response = client.get('/api/battery/cells/analysis')
        assert response.status_code == 200
        data = response.get_json()
        assert data['analysis'] is not None
        assert data['analysis']['reading_count'] == 1
        assert 'avg_voltage_delta' in data['analysis']
        assert 'health_status' in data['analysis']

    def test_get_cell_analysis_with_module_balance(self, client, db_session):
        """Test module balance analysis."""
        voltages = [3.7] * 32 + [3.8] * 32 + [3.9] * 32
        client.post('/api/battery/cells/add', json={
            'cell_voltages': voltages
        })

        response = client.get('/api/battery/cells/analysis')
        data = response.get_json()

        assert 'module_balance' in data['analysis']
        assert data['analysis']['module_balance']['module1_avg'] == 3.7
        assert data['analysis']['module_balance']['module2_avg'] == 3.8
        assert data['analysis']['module_balance']['module3_avg'] == 3.9

    def test_add_cell_reading_with_timestamp(self, client, db_session):
        """Test adding reading with custom timestamp."""
        response = client.post('/api/battery/cells/add', json={
            'cell_voltages': [3.8] * 96,
            'timestamp': '2024-01-15T10:30:00Z'
        })

        assert response.status_code == 201
        data = response.get_json()
        assert '2024-01-15' in data['reading']['timestamp']

    def test_add_cell_reading_invalid_timestamp(self, client, db_session):
        """Test adding reading with invalid timestamp."""
        response = client.post('/api/battery/cells/add', json={
            'cell_voltages': [3.8] * 96,
            'timestamp': 'not-a-timestamp'
        })

        assert response.status_code == 400
        assert b'Invalid timestamp' in response.data
