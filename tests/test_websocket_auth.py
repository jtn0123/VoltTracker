"""
WebSocket Authentication Edge Case Tests.

Tests Socket.IO connect/disconnect behavior with various auth scenarios.
"""

import os
import sys
from unittest.mock import patch


sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "receiver"))


class TestWebSocketAuthEnabled:
    """Tests for WebSocket authentication when enabled."""

    def test_reject_no_credentials(self, app, monkeypatch):
        """Connection without any credentials is rejected when auth enabled."""
        monkeypatch.setattr("config.Config.WEBSOCKET_AUTH_ENABLED", True)
        monkeypatch.setattr("config.Config.WEBSOCKET_TOKEN", "valid-secret-token")
        monkeypatch.setattr("config.Config.DASHBOARD_PASSWORD", None)

        from app import handle_connect

        with app.test_request_context():
            with patch("flask_socketio.disconnect") as mock_disconnect:
                result = handle_connect(None)
                assert result is False
                mock_disconnect.assert_called_once()

    def test_reject_invalid_token(self, app, monkeypatch):
        """Connection with wrong token is rejected."""
        monkeypatch.setattr("config.Config.WEBSOCKET_AUTH_ENABLED", True)
        monkeypatch.setattr("config.Config.WEBSOCKET_TOKEN", "valid-secret-token")
        monkeypatch.setattr("config.Config.DASHBOARD_PASSWORD", None)

        from app import handle_connect

        with app.test_request_context():
            with patch("flask_socketio.disconnect") as mock_disconnect:
                result = handle_connect({"token": "wrong-token"})
                assert result is False
                mock_disconnect.assert_called_once()

    def test_accept_valid_token(self, app, monkeypatch):
        """Connection with correct token is accepted."""
        monkeypatch.setattr("config.Config.WEBSOCKET_AUTH_ENABLED", True)
        monkeypatch.setattr("config.Config.WEBSOCKET_TOKEN", "valid-secret-token")
        monkeypatch.setattr("config.Config.DASHBOARD_PASSWORD", None)

        from app import handle_connect

        with app.test_request_context():
            result = handle_connect({"token": "valid-secret-token"})
            assert result is not False

    def test_accept_valid_password(self, app, monkeypatch):
        """Connection with correct dashboard password is accepted."""
        monkeypatch.setattr("config.Config.WEBSOCKET_AUTH_ENABLED", True)
        monkeypatch.setattr("config.Config.WEBSOCKET_TOKEN", None)
        monkeypatch.setattr("config.Config.DASHBOARD_PASSWORD", "my-password")

        from app import handle_connect

        with app.test_request_context():
            result = handle_connect({"password": "my-password"})
            assert result is not False

    def test_reject_wrong_password(self, app, monkeypatch):
        """Connection with wrong password is rejected."""
        monkeypatch.setattr("config.Config.WEBSOCKET_AUTH_ENABLED", True)
        monkeypatch.setattr("config.Config.WEBSOCKET_TOKEN", None)
        monkeypatch.setattr("config.Config.DASHBOARD_PASSWORD", "my-password")

        from app import handle_connect

        with app.test_request_context():
            with patch("flask_socketio.disconnect") as mock_disconnect:
                result = handle_connect({"password": "wrong-password"})
                assert result is False
                mock_disconnect.assert_called_once()

    def test_empty_auth_dict_rejected(self, app, monkeypatch):
        """Empty auth dict is rejected when auth is required."""
        monkeypatch.setattr("config.Config.WEBSOCKET_AUTH_ENABLED", True)
        monkeypatch.setattr("config.Config.WEBSOCKET_TOKEN", "secret")
        monkeypatch.setattr("config.Config.DASHBOARD_PASSWORD", None)

        from app import handle_connect

        with app.test_request_context():
            with patch("flask_socketio.disconnect") as mock_disconnect:
                result = handle_connect({})
                assert result is False
                mock_disconnect.assert_called_once()


class TestWebSocketAuthDisabled:
    """Tests when authentication is disabled."""

    def test_allow_no_credentials_when_disabled(self, app, monkeypatch):
        """Unauthenticated connection allowed when auth disabled."""
        monkeypatch.setattr("config.Config.WEBSOCKET_AUTH_ENABLED", False)

        from app import handle_connect

        with app.test_request_context():
            result = handle_connect(None)
            assert result is True

    def test_allow_when_no_auth_configured(self, app, monkeypatch):
        """Unauthenticated connection allowed when no password/token configured."""
        monkeypatch.setattr("config.Config.WEBSOCKET_AUTH_ENABLED", True)
        monkeypatch.setattr("config.Config.WEBSOCKET_TOKEN", None)
        monkeypatch.setattr("config.Config.DASHBOARD_PASSWORD", None)

        from app import handle_connect

        with app.test_request_context():
            result = handle_connect(None)
            assert result is True


class TestWebSocketTokenPrecedence:
    """Tests token vs password precedence."""

    def test_token_takes_precedence_over_password(self, app, monkeypatch):
        """When both token and password configured, valid token is sufficient."""
        monkeypatch.setattr("config.Config.WEBSOCKET_AUTH_ENABLED", True)
        monkeypatch.setattr("config.Config.WEBSOCKET_TOKEN", "ws-token")
        monkeypatch.setattr("config.Config.DASHBOARD_PASSWORD", "dash-pass")

        from app import handle_connect

        with app.test_request_context():
            result = handle_connect({"token": "ws-token"})
            assert result is not False

    def test_password_fallback_when_token_not_provided(self, app, monkeypatch):
        """Password works when token configured but not provided in auth."""
        monkeypatch.setattr("config.Config.WEBSOCKET_AUTH_ENABLED", True)
        monkeypatch.setattr("config.Config.WEBSOCKET_TOKEN", None)
        monkeypatch.setattr("config.Config.DASHBOARD_PASSWORD", "dash-pass")

        from app import handle_connect

        with app.test_request_context():
            result = handle_connect({"password": "dash-pass"})
            assert result is not False
