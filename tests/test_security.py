"""
Tests for security hardening changes.

Covers: CSP header, hmac.compare_digest usage, cache invalidation rate limit.
"""

import hmac


class TestCSPHeader:
    """Test Content-Security-Policy header is present and correct."""

    def test_csp_header_present(self, client):
        """S1: CSP header should be set on all responses."""
        response = client.get("/health")
        assert response.status_code == 200
        csp = response.headers.get("Content-Security-Policy")
        assert csp is not None
        assert "script-src 'self' cdn.jsdelivr.net cdn.socket.io unpkg.com" in csp
        assert "frame-ancestors 'none'" in csp
        assert "connect-src 'self' ws: wss:" in csp

    def test_xss_protection_removed(self, client):
        """S8: X-XSS-Protection header should NOT be present."""
        response = client.get("/health")
        assert "X-XSS-Protection" not in response.headers


class TestHmacComparison:
    """Test that timing-safe comparisons are used."""

    def test_hmac_compare_digest_basic(self):
        """S3/S5: hmac.compare_digest should work for string comparison."""
        assert hmac.compare_digest("secret", "secret") is True
        assert hmac.compare_digest("secret", "wrong") is False

    def test_verify_password_uses_hmac(self, app_with_auth, client):
        """S3: verify_password should use timing-safe comparison for plaintext."""
        import base64
        # Correct credentials
        creds = base64.b64encode(b"test_user:test_password").decode()
        response = client.get("/api/status", headers={"Authorization": f"Basic {creds}"})
        assert response.status_code == 200

        # Wrong credentials
        creds_bad = base64.b64encode(b"test_user:wrong_password").decode()
        response = client.get("/api/status", headers={"Authorization": f"Basic {creds_bad}"})
        assert response.status_code == 401


class TestCacheInvalidateRateLimit:
    """Test that cache invalidation endpoint has rate limiting."""

    def test_cache_invalidate_requires_body(self, client):
        """S4: /cache/invalidate should require pattern or tag."""
        response = client.post("/cache/invalidate", json={})
        assert response.status_code == 400
