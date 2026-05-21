"""Tests for CSV import parsing logic and torque parser."""

import os
import sys
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "receiver"))

from utils.csv_importer import TorqueCSVImporter, VALIDATION_RANGES
from utils.torque_parser import TorqueParser


class TestTorqueCSVImporter:
    """Tests for the TorqueCSVImporter class."""

    def test_column_map_has_soc_entries(self):
        importer = TorqueCSVImporter()
        soc_keys = [k for k, v in importer.COLUMN_MAP.items() if v == "state_of_charge"]
        assert len(soc_keys) > 0

    def test_column_map_has_fuel_entries(self):
        importer = TorqueCSVImporter()
        fuel_keys = [k for k, v in importer.COLUMN_MAP.items() if v == "fuel_level_percent"]
        assert len(fuel_keys) > 0

    def test_column_map_has_speed_entries(self):
        importer = TorqueCSVImporter()
        speed_keys = [k for k, v in importer.COLUMN_MAP.items() if "speed" in v]
        assert len(speed_keys) > 0

    def test_column_map_has_gps_entries(self):
        importer = TorqueCSVImporter()
        gps_keys = [k for k, v in importer.COLUMN_MAP.items() if v in ("latitude", "longitude")]
        assert len(gps_keys) >= 2

    def test_validation_ranges_soc(self):
        assert VALIDATION_RANGES["state_of_charge"] == (0, 100)

    def test_validation_ranges_longitude(self):
        assert VALIDATION_RANGES["longitude"] == (-180, 180)

    def test_validation_ranges_speed(self):
        assert VALIDATION_RANGES["speed_mph"] == (0, 200)

    def test_validation_ranges_rpm(self):
        assert VALIDATION_RANGES["engine_rpm"] == (0, 8000)


class TestTorqueParser:
    """Tests for TorqueParser."""

    def test_parser_instantiation(self):
        parser = TorqueParser()
        assert parser is not None

    def test_parser_has_parse_method(self):
        parser = TorqueParser()
        assert callable(getattr(parser, "parse", None))

    def test_parser_handles_empty_data(self):
        parser = TorqueParser()
        result = parser.parse({})
        # Should return None or a dict with missing required fields
        assert result is None or isinstance(result, dict)
