"""
Regression tests for backend audit bug fixes.

Each test guards a specific fix in receiver/app.py and
receiver/calculations/battery.py. The ChargingSession.start_time
UNIQUE-constraint fix in receiver/models.py is already covered by
tests/test_db_integrity.py::TestCascadingDeletes
::test_charging_sessions_allow_duplicate_start_time, so it is not
duplicated here.
"""

from types import SimpleNamespace

from flask import Flask

from receiver.calculations.battery import (
    calculate_degradation_rate_per_10k_miles,
)


def _make_fresh_app():
    """Return a fresh, never-served Flask app.

    ``_init_csrf_and_limiter`` calls ``CSRFProtect(app)``, which registers a
    template ``context_processor``. Flask forbids registering setup methods on
    an app that has already handled a request, so the shared session-scoped
    ``app`` fixture cannot be reused here -- a brand-new app is required.
    """
    app = Flask(__name__)
    app.config["SECRET_KEY"] = "test"
    app.config["WTF_CSRF_ENABLED"] = False
    return app


class TestRateLimiterToggleRegression:
    """Guards receiver/app.py: rate limiter could not be disabled.

    The buggy code assigned ``limiter._enabled`` (not a real attribute),
    so ``RATE_LIMIT_ENABLED=false`` silently failed to disable limiting.
    The fix assigns the public ``limiter.enabled`` attribute.
    """

    def test_rate_limit_disabled_sets_limiter_enabled_false(self):
        """With RATE_LIMIT_ENABLED=false, limiter.enabled must be False."""
        from app import _init_csrf_and_limiter
        from extensions import limiter

        fake_cfg = SimpleNamespace(RATE_LIMIT_ENABLED=False)
        try:
            _init_csrf_and_limiter(_make_fresh_app(), fake_cfg)
            assert limiter.enabled is False
        finally:
            # Restore the global singleton so other tests are unaffected.
            limiter.enabled = True

    def test_rate_limit_enabled_sets_limiter_enabled_true(self):
        """With RATE_LIMIT_ENABLED=true, limiter.enabled must be True."""
        from app import _init_csrf_and_limiter
        from extensions import limiter

        fake_cfg = SimpleNamespace(RATE_LIMIT_ENABLED=True)
        try:
            _init_csrf_and_limiter(_make_fresh_app(), fake_cfg)
            assert limiter.enabled is True
        finally:
            limiter.enabled = True


class TestDegradationRateDivisionByZeroRegression:
    """Guards receiver/calculations/battery.py: division-by-zero.

    calculate_degradation_rate_per_10k_miles divided by
    nominal_capacity_kwh; a zero value raised ZeroDivisionError.
    The fix returns 0.0 for a falsy nominal capacity.
    """

    def test_zero_nominal_capacity_returns_number(self):
        """Zero nominal capacity must return a number, not crash."""
        result = calculate_degradation_rate_per_10k_miles(-0.00001, 0)
        assert isinstance(result, (int, float))
        assert result == 0.0

    def test_zero_float_nominal_capacity_returns_number(self):
        """Zero float nominal capacity must also be handled."""
        result = calculate_degradation_rate_per_10k_miles(0.5, 0.0)
        assert isinstance(result, (int, float))
        assert result == 0.0

    def test_nonzero_nominal_capacity_still_computes(self):
        """Non-zero nominal capacity still produces the expected value."""
        result = calculate_degradation_rate_per_10k_miles(-0.00001, 18.4)
        assert result == 0.54
