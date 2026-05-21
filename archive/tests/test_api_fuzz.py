"""
API fuzz testing with Hypothesis.

Tests key API endpoints with random/malformed payloads to find crashes,
unhandled exceptions, and input validation gaps.
"""

import json

import pytest
from hypothesis import HealthCheck, given, settings, strategies as st


@pytest.fixture
def client(app):
    """Get test client."""
    return app.test_client()


# ── Telemetry upload fuzz ─────────────────────────────────────────────────────

# Strategy for random Torque-style query parameters
torque_params = st.fixed_dictionaries(
    {},
    optional={
        "eml": st.one_of(st.text(max_size=50), st.integers(), st.none()),
        "session": st.one_of(st.text(max_size=100), st.integers()),
        "id": st.one_of(st.text(max_size=100), st.integers()),
        "time": st.one_of(st.integers(), st.text(max_size=30), st.floats()),
        "kff1006": st.one_of(st.text(max_size=20), st.floats(allow_nan=True, allow_infinity=True)),
        "kff1005": st.one_of(st.text(max_size=20), st.floats(allow_nan=True, allow_infinity=True)),
        "kd": st.one_of(st.text(max_size=20), st.floats()),
        "k46": st.one_of(st.text(max_size=20), st.floats()),
        "kff1001": st.one_of(st.text(max_size=20), st.floats()),
        "k5": st.one_of(st.text(max_size=20), st.floats()),
    },
)


# deadline=None: /torque/upload performs real DB writes (trip query + insert +
# context enrichment), plus one-time connection init on the first example.
# Hypothesis's 200ms default deadline is unrealistic for a write endpoint and
# produces spurious DeadlineExceeded failures unrelated to correctness.
@settings(
    max_examples=50,
    deadline=None,
    suppress_health_check=[HealthCheck.too_slow, HealthCheck.function_scoped_fixture],
)
@given(params=torque_params)
def test_torque_upload_fuzz(client, params):
    """Torque upload endpoint should never crash with any query params."""
    resp = client.get("/torque/upload", query_string=params)
    # Should return a valid HTTP response (not 500)
    assert resp.status_code in (200, 400, 401, 403, 404, 413, 422, 429)


# ── Trip endpoints fuzz ───────────────────────────────────────────────────────

@settings(max_examples=30, suppress_health_check=[HealthCheck.too_slow, HealthCheck.function_scoped_fixture])
@given(
    trip_id=st.one_of(
        st.integers(min_value=-1000, max_value=999999),
        st.text(max_size=20),
    )
)
def test_trip_detail_fuzz(client, trip_id):
    """Trip detail endpoint should handle any trip_id without crashing."""
    resp = client.get(f"/api/trips/{trip_id}")
    assert resp.status_code in (200, 400, 404, 422)


@settings(max_examples=30, suppress_health_check=[HealthCheck.too_slow, HealthCheck.function_scoped_fixture])
@given(
    data=st.fixed_dictionaries(
        {},
        optional={
            "days": st.one_of(st.integers(min_value=-100, max_value=10000), st.text(max_size=10)),
            "limit": st.one_of(st.integers(min_value=-10, max_value=10000), st.text(max_size=10)),
            "offset": st.one_of(st.integers(min_value=-10, max_value=10000), st.text(max_size=10)),
        },
    )
)
def test_trip_list_fuzz(client, data):
    """Trip list endpoint should handle any query params."""
    resp = client.get("/api/trips", query_string=data)
    assert resp.status_code in (200, 400, 422)


# ── Charging endpoints fuzz ───────────────────────────────────────────────────

@settings(max_examples=30, suppress_health_check=[HealthCheck.too_slow, HealthCheck.function_scoped_fixture])
@given(
    data=st.fixed_dictionaries(
        {},
        optional={
            "start_soc": st.one_of(st.floats(allow_nan=True, allow_infinity=True), st.text(max_size=10)),
            "end_soc": st.one_of(st.floats(allow_nan=True, allow_infinity=True), st.text(max_size=10)),
            "kwh_added": st.one_of(st.floats(allow_nan=True, allow_infinity=True), st.text(max_size=10)),
            "cost": st.one_of(st.floats(allow_nan=True, allow_infinity=True), st.text(max_size=10)),
            "location": st.text(max_size=200),
            "charge_type": st.text(max_size=50),
        },
    )
)
def test_charging_create_fuzz(client, data):
    """Charging session creation should not crash with malformed data."""
    resp = client.post(
        "/api/charging/sessions",
        data=json.dumps(data),
        content_type="application/json",
    )
    # Should not be 500
    assert resp.status_code != 500, f"Server error with data: {data}"


# ── Frontend error report endpoint ────────────────────────────────────────────

@settings(max_examples=30, suppress_health_check=[HealthCheck.too_slow, HealthCheck.function_scoped_fixture])
@given(
    data=st.one_of(
        st.fixed_dictionaries(
            {},
            optional={
                "message": st.text(max_size=500),
                "source": st.text(max_size=200),
                "lineno": st.one_of(st.integers(), st.none()),
                "colno": st.one_of(st.integers(), st.none()),
                "stack": st.text(max_size=2000),
            },
        ),
        st.text(max_size=100),  # Non-JSON payloads
    )
)
def test_error_report_fuzz(client, data):
    """Error report endpoint should accept anything without crashing."""
    if isinstance(data, dict):
        resp = client.post(
            "/api/errors/report",
            data=json.dumps(data),
            content_type="application/json",
        )
    else:
        resp = client.post(
            "/api/errors/report",
            data=data,
            content_type="text/plain",
        )
    # S6: endpoint now validates JSON body — 400 for missing/invalid, 200 for valid
    assert resp.status_code in (200, 400)
