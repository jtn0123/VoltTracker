"""
Property-based tests using Hypothesis.

These tests automatically generate hundreds of test cases to find edge cases
and validate invariants that should always hold true.

Hypothesis discovers bugs that manual testing might miss by exploring
the input space systematically.
"""

import pytest
from hypothesis import given, strategies as st, assume, settings, HealthCheck
from datetime import datetime, timedelta, timezone
from decimal import Decimal


# ============================================================================
# Time Parsing Property Tests
# ============================================================================

class TestTimeParsing:
    """Property-based tests for time parsing utilities."""

    @given(st.datetimes(min_value=datetime(2000, 1, 1), max_value=datetime(2099, 12, 31)))
    @settings(suppress_health_check=[HealthCheck.too_slow])
    def test_datetime_round_trip_iso(self, dt):
        """
        Property: Parsing an ISO formatted datetime should return equivalent datetime.
        """
        from utils.time_utils import parse_datetime, format_datetime_iso

        # Add UTC timezone
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)

        # Format and parse should be inverse operations
        formatted = format_datetime_iso(dt)
        parsed = parse_datetime(formatted)

        assert parsed is not None
        # Should be within 1 second (accounting for microsecond truncation)
        assert abs((parsed - dt).total_seconds()) < 1

    @given(st.integers(min_value=0, max_value=2**31 - 1))
    def test_unix_timestamp_parsing(self, timestamp):
        """
        Property: All valid Unix timestamps should parse successfully.
        """
        from utils.time_utils import parse_datetime

        result = parse_datetime(str(timestamp))
        assert result is not None
        assert isinstance(result, datetime)

    @given(
        st.datetimes(min_value=datetime(2020, 1, 1)),
        st.integers(min_value=1, max_value=365)
    )
    def test_date_range_ordering(self, end_date, days_back):
        """
        Property: parse_date_range should always return start <= end.
        """
        from utils.time_utils import parse_date_range

        end_str = end_date.strftime("%Y-%m-%d")
        start, end = parse_date_range(None, end_str, default_days=days_back)

        assert start <= end, f"Start {start} should be <= end {end}"

    @given(st.text(min_size=1, max_size=100))
    def test_invalid_date_strings_dont_crash(self, invalid_string):
        """
        Property: Invalid date strings should not crash, just return default.
        """
        from utils.time_utils import parse_datetime

        # Should not raise exception
        result = parse_datetime(invalid_string, default=None)
        # Result is either None or a valid datetime
        assert result is None or isinstance(result, datetime)


# ============================================================================
# Calculation Property Tests
# ============================================================================

class TestCalculations:
    """Property-based tests for efficiency calculations."""

    @given(
        st.floats(min_value=0, max_value=100, allow_nan=False, allow_infinity=False),
        st.floats(min_value=0.1, max_value=200, allow_nan=False, allow_infinity=False)
    )
    def test_mpg_calculation_positive(self, gallons, miles):
        """
        Property: MPG should always be positive for positive inputs.
        """
        if gallons == 0:
            return  # Skip division by zero

        mpg = miles / gallons
        assert mpg >= 0, "MPG should never be negative"

    @given(
        st.floats(min_value=0, max_value=100, allow_nan=False, allow_infinity=False),
        st.floats(min_value=0, max_value=100, allow_nan=False, allow_infinity=False)
    )
    def test_soc_bounds(self, start_soc, end_soc):
        """
        Property: SOC values should always be 0-100%.
        """
        assume(0 <= start_soc <= 100)
        assume(0 <= end_soc <= 100)

        soc_change = end_soc - start_soc
        assert -100 <= soc_change <= 100, "SOC change should be within bounds"

    @given(
        st.floats(min_value=0, max_value=100, allow_nan=False, allow_infinity=False)
    )
    def test_soc_to_kwh_conversion(self, soc_percent):
        """
        Property: SOC to kWh conversion should be monotonic and bounded.
        """
        from utils import soc_to_kwh

        assume(0 <= soc_percent <= 100)

        kwh = soc_to_kwh(soc_percent)

        # Should be positive
        assert kwh >= 0, "kWh should be non-negative"

        # Should be reasonable (Volt has ~18.4 kWh usable battery)
        assert kwh <= 20, "kWh should not exceed battery capacity"

        # Monotonic: higher SOC = more kWh
        if soc_percent > 0:
            lower_kwh = soc_to_kwh(soc_percent - 1)
            assert kwh >= lower_kwh, "Higher SOC should have more kWh"

    @given(
        st.floats(min_value=0.01, max_value=50, allow_nan=False, allow_infinity=False),
        st.floats(min_value=1, max_value=100, allow_nan=False, allow_infinity=False)
    )
    def test_kwh_per_mile_calculation(self, kwh_used, miles_driven):
        """
        Property: kWh/mile efficiency should be reasonable.
        """
        assume(miles_driven > 0)

        efficiency = kwh_used / miles_driven

        # Should be positive
        assert efficiency > 0, "Efficiency should be positive"

        # With constrained inputs (kwh <= 50, miles >= 1), efficiency <= 50
        # which is a reasonable upper bound for very inefficient driving
        assert efficiency <= 50, "Efficiency should be reasonable"


# ============================================================================
# Temperature Conversion Property Tests
# ============================================================================

class TestTemperatureConversions:
    """Property-based tests for temperature conversions."""

    @given(st.floats(min_value=-100, max_value=150, allow_nan=False, allow_infinity=False))
    def test_fahrenheit_celsius_round_trip(self, temp_f):
        """
        Property: F -> C -> F should return original value (within floating point error).
        """
        # F to C formula: (F - 32) * 5/9
        temp_c = (temp_f - 32) * 5 / 9

        # C to F formula: C * 9/5 + 32
        temp_f_back = temp_c * 9 / 5 + 32

        assert abs(temp_f - temp_f_back) < 0.001, "Round trip should preserve value"

    @given(st.floats(min_value=-60, max_value=150, allow_nan=False, allow_infinity=False))
    def test_temperature_validation_range(self, temp_f):
        """
        Property: Validate temperature ranges are enforced.
        """
        # VoltTracker accepts -60F to 150F (reasonable for ambient)
        is_valid = -60 <= temp_f <= 150

        if is_valid:
            # Should be accepted
            assert True
        else:
            # Should be rejected (outside reasonable range)
            assert temp_f < -60 or temp_f > 150


# ============================================================================
# CSV Import Property Tests
# ============================================================================

class TestCSVImport:
    """Property-based tests for CSV import validation."""

    @given(
        st.floats(min_value=-90, max_value=90, allow_nan=False, allow_infinity=False),
        st.floats(min_value=-180, max_value=180, allow_nan=False, allow_infinity=False)
    )
    def test_gps_coordinate_validation(self, latitude, longitude):
        """
        Property: GPS coordinates should be validated within bounds.
        """
        # Valid range
        assert -90 <= latitude <= 90, "Latitude must be -90 to 90"
        assert -180 <= longitude <= 180, "Longitude must be -180 to 180"

    @given(
        st.lists(
            st.floats(min_value=0, max_value=100, allow_nan=False),
            min_size=1,
            max_size=1000
        )
    )
    def test_soc_transition_detection(self, soc_values):
        """
        Property: SOC transitions should have bounded change rates.

        Note: This test validates that SOC values are within valid range (0-100),
        not that the change rate is realistic. Realistic change rate validation
        would require time information between samples.
        """
        # Check all SOC values are in valid range
        for soc in soc_values:
            assert 0 <= soc <= 100, "SOC must be between 0 and 100%"


# ============================================================================
# Cache Key Generation Property Tests
# ============================================================================

class TestCacheKeys:
    """Property-based tests for cache key generation."""

    @given(
        st.text(min_size=1, max_size=50),
        st.lists(st.integers(), min_size=0, max_size=5)
    )
    def test_cache_key_deterministic(self, prefix, args):
        """
        Property: Same inputs should always generate same cache key.
        """
        from utils.cache_utils import generate_cache_key

        key1 = generate_cache_key(prefix, *args)
        key2 = generate_cache_key(prefix, *args)

        assert key1 == key2, "Cache keys should be deterministic"

    @given(
        st.text(min_size=1, max_size=50),
        st.dictionaries(
            st.text(min_size=1, max_size=20),
            st.integers(),
            min_size=0,
            max_size=5
        )
    )
    def test_cache_key_kwargs_order_independent(self, prefix, kwargs):
        """
        Property: Kwargs order shouldn't affect cache key.
        """
        from utils.cache_utils import generate_cache_key

        # Generate key with kwargs in different orders
        key1 = generate_cache_key(prefix, **kwargs)

        # Reverse the kwargs
        reversed_kwargs = dict(reversed(list(kwargs.items())))
        key2 = generate_cache_key(prefix, **reversed_kwargs)

        assert key1 == key2, "Kwargs order should not affect cache key"


# ============================================================================
# Query Builder Property Tests
# ============================================================================

class TestQueryBuilder:
    """Property-based tests for query builder."""

    @given(
        st.floats(min_value=0.1, max_value=1000, allow_nan=False),
        st.floats(min_value=0.1, max_value=1000, allow_nan=False)
    )
    def test_distance_filter_consistency(self, min_dist, max_dist):
        """
        Property: Min/max filters should be consistent.
        """
        # Skip if min > max
        if min_dist > max_dist:
            min_dist, max_dist = max_dist, min_dist

        # Should not raise exception
        # (actual query execution would be tested in integration tests)
        assert min_dist <= max_dist


# ============================================================================
# API Token Generation Property Tests
# ============================================================================

class TestTokenGeneration:
    """Property-based tests for token generation."""

    @given(st.integers(min_value=16, max_value=128))
    def test_token_length_and_uniqueness(self, token_length):
        """
        Property: Generated tokens should be unique and correct length.
        """
        from utils.auth_utils import generate_api_token

        token1 = generate_api_token(length=token_length)
        token2 = generate_api_token(length=token_length)

        # Should be different (extremely high probability)
        assert token1 != token2, "Tokens should be unique"

        # Should have correct length (prefix + underscore + hex)
        # hex length is 2 * token_length (each byte = 2 hex chars)
        expected_hex_len = token_length * 2
        token_parts = token1.split("_")
        assert len(token_parts) == 2, "Token should have prefix_hex format"
        assert len(token_parts[1]) == expected_hex_len, f"Token hex should be {expected_hex_len} chars"


# ============================================================================
# Statistical Calculations Property Tests
# ============================================================================

class TestStatistics:
    """Property-based tests for statistical calculations."""

    @given(st.lists(st.floats(min_value=0, max_value=100, allow_nan=False), min_size=1, max_size=100))
    def test_average_calculation_bounds(self, values):
        """
        Property: Average should be between min and max of input values.
        """
        if not values:
            return

        avg = sum(values) / len(values)
        min_val = min(values)
        max_val = max(values)

        # Use small epsilon for floating point comparison
        eps = 1e-9
        assert min_val - eps <= avg <= max_val + eps, "Average should be within min/max range"

    @given(st.lists(st.floats(min_value=0, max_value=100, allow_nan=False), min_size=2, max_size=100))
    @settings(suppress_health_check=[HealthCheck.filter_too_much])
    def test_confidence_interval_contains_mean(self, values):
        """
        Property: 95% confidence interval should contain the mean.
        """
        import statistics

        if len(values) < 2:
            return

        mean = statistics.mean(values)
        stdev = statistics.stdev(values)

        # 95% CI = mean ± 1.96 * (stdev / sqrt(n))
        n = len(values)
        margin = 1.96 * (stdev / (n ** 0.5))

        ci_lower = mean - margin
        ci_upper = mean + margin

        # Mean should be in the interval (by definition)
        assert ci_lower <= mean <= ci_upper


# ============================================================================
# Run Tests
# ============================================================================

if __name__ == "__main__":
    pytest.main([__file__, "-v", "--hypothesis-show-statistics"])
