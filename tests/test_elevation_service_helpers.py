"""
Direct tests for the helpers extracted by PR #45's S3776 refactor of
``receiver/services/elevation_service.py``. The existing
``test_weather_jobs.py`` mocks ``fetch_and_update_elevations`` at the
function-level boundary, so the new private helpers (extracted to drop
the parent function's cognitive complexity below 15) had no direct
coverage. These tests exercise them in isolation so a future change to
the helpers can't pass CI by accident.
"""

from unittest.mock import MagicMock

from services.elevation_service import (
    _build_coordinate_mapping,
    _build_point_to_original_index,
    _find_nearest_sampled_index,
    fetch_and_update_elevations,
    get_elevation_profile_for_telemetry,
)


class TestFindNearestSampledIndex:
    def test_picks_exact_match(self):
        sampled = [(40.0, -100.0), (41.0, -101.0), (42.0, -102.0)]
        assert _find_nearest_sampled_index(41.0, -101.0, sampled) == 1

    def test_picks_closest_to_query(self):
        sampled = [(40.0, -100.0), (41.0, -101.0), (42.0, -102.0)]
        # query is far closer to (42, -102) than to the others
        assert _find_nearest_sampled_index(41.95, -101.95, sampled) == 2

    def test_first_index_when_all_equidistant(self):
        sampled = [(40.0, -100.0), (40.0, -100.0)]
        assert _find_nearest_sampled_index(40.0, -100.0, sampled) == 0

    def test_handles_single_sample(self):
        sampled = [(40.0, -100.0)]
        assert _find_nearest_sampled_index(50.0, -90.0, sampled) == 0


class TestBuildCoordinateMapping:
    def test_each_original_maps_to_a_sampled_index(self):
        coordinates = [(40.0, -100.0), (40.5, -100.5), (41.0, -101.0)]
        sampled = [(40.0, -100.0), (41.0, -101.0)]
        mapping = _build_coordinate_mapping(coordinates, sampled)
        assert set(mapping.keys()) == {0, 1, 2}
        assert mapping[0] == 0  # (40,−100) → first sampled
        assert mapping[2] == 1  # (41,−101) → second sampled
        # The middle point (40.5,−100.5) is equidistant from both, but the
        # implementation picks the first encountered minimum.
        assert mapping[1] in (0, 1)

    def test_empty_inputs(self):
        assert _build_coordinate_mapping([], [(40.0, -100.0)]) == {}


class TestBuildPointToOriginalIndex:
    def _mk_point(self, lat, lon):
        p = MagicMock()
        p.latitude = lat
        p.longitude = lon
        return p

    def test_maps_each_point_to_its_coordinate_index(self):
        coordinates = [(40.0, -100.0), (41.0, -101.0)]
        points = [self._mk_point(40.0, -100.0), self._mk_point(41.0, -101.0)]
        result = _build_point_to_original_index(points, coordinates)
        assert result == {0: 0, 1: 1}

    def test_skips_points_with_no_gps(self):
        coordinates = [(40.0, -100.0)]
        points = [
            self._mk_point(None, None),
            self._mk_point(40.0, -100.0),
            self._mk_point(None, -100.0),
            self._mk_point(40.0, None),
        ]
        result = _build_point_to_original_index(points, coordinates)
        assert result == {1: 0}

    def test_first_occurrence_wins_for_duplicate_coordinates(self):
        # Two telemetry points share the same lat/lon — both should map to
        # the *first* coordinate index for that pair so the elevation lookup
        # is deterministic.
        coordinates = [(40.0, -100.0), (40.0, -100.0)]
        points = [self._mk_point(40.0, -100.0), self._mk_point(40.0, -100.0)]
        result = _build_point_to_original_index(points, coordinates)
        assert result == {0: 0, 1: 0}


class TestFetchAndUpdateElevations:
    """End-to-end coverage of the refactored function via the helper path."""

    def _mk_point(self, lat, lon):
        p = MagicMock()
        p.latitude = lat
        p.longitude = lon
        p.elevation_meters = None
        return p

    def test_returns_zero_for_empty_input(self):
        assert fetch_and_update_elevations(MagicMock(), []) == 0

    def test_returns_zero_when_fewer_than_two_gps_points(self):
        # Only one point with GPS, others have no coords.
        points = [self._mk_point(None, None), self._mk_point(40.0, -100.0)]
        assert fetch_and_update_elevations(MagicMock(), points) == 0

    def test_assigns_elevations_when_api_returns_data(self, monkeypatch):
        # Two unique GPS points; sample_coordinates returns them as-is,
        # get_elevation_for_points returns one elevation per sample.
        from services import elevation_service

        def fake_sample(coords, max_samples=50):
            return list(coords)

        def fake_get_elevations(coords):
            return [123.0, 456.0]

        monkeypatch.setattr(elevation_service, "sample_coordinates", fake_sample)
        monkeypatch.setattr(elevation_service, "get_elevation_for_points", fake_get_elevations)

        points = [self._mk_point(40.0, -100.0), self._mk_point(41.0, -101.0)]
        updated = fetch_and_update_elevations(MagicMock(), points)
        assert updated == 2
        assert points[0].elevation_meters == 123.0
        assert points[1].elevation_meters == 456.0

    def test_returns_zero_when_api_returns_nothing(self, monkeypatch):
        from services import elevation_service

        monkeypatch.setattr(elevation_service, "sample_coordinates", lambda c, max_samples=50: list(c))
        monkeypatch.setattr(elevation_service, "get_elevation_for_points", lambda c: None)

        points = [self._mk_point(40.0, -100.0), self._mk_point(41.0, -101.0)]
        assert fetch_and_update_elevations(MagicMock(), points) == 0
        assert points[0].elevation_meters is None


class TestGetElevationProfile:
    def test_returns_calculated_profile_for_points_with_elevation(self):
        points = [MagicMock(elevation_meters=10.0), MagicMock(elevation_meters=20.0), MagicMock(elevation_meters=15.0)]
        profile = get_elevation_profile_for_telemetry(points)
        assert isinstance(profile, dict)
        # The exact keys come from utils.elevation.calculate_elevation_profile
        # — we just assert that we get a populated dict back.
        assert profile

    def test_skips_points_without_elevation(self):
        points = [MagicMock(elevation_meters=None), MagicMock(elevation_meters=None)]
        profile = get_elevation_profile_for_telemetry(points)
        assert isinstance(profile, dict)
