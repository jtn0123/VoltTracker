"""Tests for system routes (health, favicon, etc.).

Focused on JTN-489: /favicon.ico should return 200 with an image content
type so browsers stop logging 404s on every page load.
"""


class TestFaviconRoute:
    """Tests for the /favicon.ico route added in JTN-489."""

    def test_favicon_returns_200(self, client):
        """GET /favicon.ico should not 404."""
        response = client.get("/favicon.ico")
        assert response.status_code == 200

    def test_favicon_returns_image_content_type(self, client):
        """Content-Type should be an image mimetype (SVG by default)."""
        response = client.get("/favicon.ico")
        assert response.status_code == 200
        content_type = response.headers.get("Content-Type", "")
        # We serve the existing PWA SVG icon. Accept SVG or any image/*
        # so future swaps to a real .ico/.png don't require a test churn.
        assert content_type.startswith("image/"), (
            f"Unexpected favicon content type: {content_type!r}"
        )

    def test_favicon_returns_non_empty_body(self, client):
        """Favicon body should be non-empty so browsers actually render it."""
        response = client.get("/favicon.ico")
        assert response.status_code == 200
        assert len(response.data) > 0

    def test_favicon_is_public(self, client):
        """Favicon must be reachable without auth (it is requested on every
        page load, including the login screen). Our test config does not set
        DASHBOARD_PASSWORD so this mostly guards against a regression where
        /favicon.ico accidentally gets added behind auth middleware."""
        response = client.get("/favicon.ico")
        assert response.status_code == 200
