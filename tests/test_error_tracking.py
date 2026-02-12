"""Tests for the error tracking utility."""

import json

import pytest


@pytest.fixture
def client(app):
    return app.test_client()


class TestErrorTracking:
    """Test error_tracking module."""

    def test_track_error_returns_structured_dict(self):
        """track_error should return a dict with expected keys."""
        from receiver.utils.error_tracking import track_error

        exc = ValueError("test error")
        result = track_error(
            exc,
            endpoint="/api/test",
            method="GET",
            status_code=500,
            request_context={"foo": "bar"},
        )

        assert result["endpoint"] == "/api/test"
        assert result["method"] == "GET"
        assert result["status_code"] == 500
        assert result["error_type"] == "ValueError"
        assert result["error_message"] == "test error"
        assert "timestamp" in result
        assert result["context"]["foo"] == "bar"

    def test_frontend_error_report_endpoint(self, client):
        """POST /api/errors/report should accept error payloads."""
        resp = client.post(
            "/api/errors/report",
            data=json.dumps({
                "message": "TypeError: x is not a function",
                "source": "main.js",
                "lineno": 42,
                "colno": 10,
                "stack": "TypeError: x is not a function\n    at main.js:42:10",
                "url": "https://example.com/dashboard",
                "userAgent": "TestAgent/1.0",
            }),
            content_type="application/json",
        )
        assert resp.status_code == 200
        data = resp.get_json()
        assert data["status"] == "ok"

    def test_frontend_error_report_empty_body(self, client):
        """Endpoint rejects empty/missing JSON body (S6 validation)."""
        resp = client.post("/api/errors/report", content_type="application/json")
        assert resp.status_code == 400

    def test_frontend_error_report_invalid_json(self, client):
        """Endpoint rejects non-JSON body (S6 validation)."""
        resp = client.post(
            "/api/errors/report",
            data="not json",
            content_type="text/plain",
        )
        assert resp.status_code == 400


class TestResponseTimeHeader:
    """Test that X-Response-Time header is added."""

    def test_response_time_header_present(self, client):
        """All responses should include X-Response-Time."""
        resp = client.get("/health")
        assert "X-Response-Time" in resp.headers
        assert resp.headers["X-Response-Time"].endswith("ms")
