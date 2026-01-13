"""
Tests for audit logging functionality.
"""

import pytest
from unittest.mock import MagicMock, patch, PropertyMock
from datetime import datetime


class TestAuditAction:
    """Tests for AuditAction enum."""

    def test_audit_action_values(self):
        """Test all audit action enum values exist."""
        from utils.audit_log import AuditAction

        assert AuditAction.CREATE.value == "create"
        assert AuditAction.UPDATE.value == "update"
        assert AuditAction.DELETE.value == "delete"
        assert AuditAction.SOFT_DELETE.value == "soft_delete"
        assert AuditAction.EXPORT.value == "export"
        assert AuditAction.IMPORT.value == "import"
        assert AuditAction.LOGIN.value == "login"
        assert AuditAction.LOGOUT.value == "logout"
        assert AuditAction.API_CALL.value == "api_call"

    def test_audit_action_is_enum(self):
        """Test AuditAction is an Enum."""
        from utils.audit_log import AuditAction
        from enum import Enum

        assert issubclass(AuditAction, Enum)


class TestAuditLoggerGetUserInfo:
    """Tests for AuditLogger._get_user_info method."""

    def test_get_user_info_no_request(self, app):
        """Test user info extraction when no request context."""
        from utils.audit_log import AuditLogger

        # Use app context and test without a request context
        with app.app_context():
            # Create a mock request that behaves like None/falsy
            mock_request = MagicMock()
            mock_request.__bool__ = lambda self: False
            mock_request.remote_addr = None
            mock_request.headers = MagicMock()
            mock_request.headers.get.return_value = None

            with patch("utils.audit_log.request", mock_request):
                info = AuditLogger._get_user_info()

        assert info["ip_address"] is None
        assert info["user_agent"] is None

    def test_get_user_info_with_request(self, app, client):
        """Test user info extraction with request context."""
        from utils.audit_log import AuditLogger

        with app.test_request_context(
            "/test", headers={"User-Agent": "TestAgent/1.0"}, environ_base={"REMOTE_ADDR": "192.168.1.1"}
        ):
            info = AuditLogger._get_user_info()

        assert info["ip_address"] == "192.168.1.1"
        assert info["user_agent"] == "TestAgent/1.0"

    def test_get_user_info_with_forwarded_ip(self, app):
        """Test user info extraction with X-Forwarded-For header."""
        from utils.audit_log import AuditLogger

        with app.test_request_context(
            "/test",
            headers={"X-Forwarded-For": "10.0.0.1, 10.0.0.2, 192.168.1.1", "User-Agent": "TestAgent"},
            environ_base={"REMOTE_ADDR": "192.168.1.1"},
        ):
            info = AuditLogger._get_user_info()

        # Should use first IP from X-Forwarded-For
        assert info["ip_address"] == "10.0.0.1"

    def test_get_user_info_with_flask_g(self, app):
        """Test user info extraction with flask g context."""
        from utils.audit_log import AuditLogger
        from flask import g

        with app.test_request_context("/test"):
            g.user_id = "user123"
            g.username = "testuser"
            info = AuditLogger._get_user_info()

        assert info["user_id"] == "user123"
        assert info["username"] == "testuser"


class TestAuditLoggerLogChange:
    """Tests for AuditLogger.log_change method."""

    def test_log_change_creates_audit_entry(self, app, db_session):
        """Test logging a change creates an audit log entry."""
        from utils.audit_log import AuditLogger, AuditAction
        from models import AuditLog

        with app.test_request_context(
            "/test", environ_base={"REMOTE_ADDR": "192.168.1.1"}, headers={"User-Agent": "Test"}
        ):
            AuditLogger.log_change(
                entity_type="trips",
                entity_id=123,
                action=AuditAction.UPDATE,
                old_data={"status": "active"},
                new_data={"status": "completed"},
                details="Trip completed",
                db=db_session,
            )

        # Verify audit log was created
        audit_entry = db_session.query(AuditLog).filter_by(entity_type="trips", entity_id="123").first()
        assert audit_entry is not None
        assert audit_entry.action == "update"
        assert audit_entry.old_data == {"status": "active"}
        assert audit_entry.new_data == {"status": "completed"}
        assert audit_entry.details == "Trip completed"
        assert audit_entry.ip_address == "192.168.1.1"

    def test_log_change_with_create_action(self, app, db_session):
        """Test logging a create action."""
        from utils.audit_log import AuditLogger, AuditAction
        from models import AuditLog

        with app.test_request_context("/test"):
            AuditLogger.log_change(
                entity_type="charging_sessions",
                entity_id=456,
                action=AuditAction.CREATE,
                new_data={"start_soc": 20, "end_soc": 80},
                db=db_session,
            )

        audit_entry = db_session.query(AuditLog).filter_by(entity_type="charging_sessions").first()
        assert audit_entry is not None
        assert audit_entry.action == "create"

    def test_log_change_handles_exception(self, app, caplog):
        """Test that log_change doesn't raise exceptions."""
        from utils.audit_log import AuditLogger, AuditAction
        import logging

        with app.test_request_context("/test"):
            # Use a mock that raises an exception
            with patch("utils.audit_log.get_db") as mock_get_db:
                mock_get_db.side_effect = Exception("Database error")

                # Should not raise
                AuditLogger.log_change(
                    entity_type="trips", entity_id=1, action=AuditAction.UPDATE, db=None  # Force use of get_db
                )

        # Check that error was logged
        assert "Failed to write audit log" in caplog.text


class TestAuditLoggerLogDelete:
    """Tests for AuditLogger.log_delete method."""

    def test_log_delete_hard_delete(self, app, db_session):
        """Test logging a hard delete operation."""
        from utils.audit_log import AuditLogger
        from models import AuditLog

        with app.test_request_context("/test"):
            AuditLogger.log_delete(entity_type="trips", entity_id=789, soft=False, db=db_session)

        audit_entry = db_session.query(AuditLog).filter_by(entity_type="trips", entity_id="789").first()
        assert audit_entry is not None
        assert audit_entry.action == "delete"
        assert "permanent" in audit_entry.details.lower()

    def test_log_delete_soft_delete(self, app, db_session):
        """Test logging a soft delete operation."""
        from utils.audit_log import AuditLogger
        from models import AuditLog

        with app.test_request_context("/test"):
            AuditLogger.log_delete(entity_type="trips", entity_id=999, soft=True, db=db_session)

        audit_entry = db_session.query(AuditLog).filter_by(entity_type="trips", entity_id="999").first()
        assert audit_entry is not None
        assert audit_entry.action == "soft_delete"
        assert "recoverable" in audit_entry.details.lower()


class TestAuditLoggerLogExport:
    """Tests for AuditLogger.log_export method."""

    def test_log_export_basic(self, app, db_session):
        """Test logging a basic export operation."""
        from utils.audit_log import AuditLogger
        from models import AuditLog

        with app.test_request_context("/test"):
            with patch("utils.audit_log.get_db", return_value=db_session):
                AuditLogger.log_export(entity_type="trips", count=50)

        audit_entry = db_session.query(AuditLog).filter_by(action="export").first()
        assert audit_entry is not None
        assert audit_entry.entity_type == "exports"
        assert "50" in audit_entry.details

    def test_log_export_with_filters(self, app, db_session):
        """Test logging an export operation with filters."""
        from utils.audit_log import AuditLogger
        from models import AuditLog

        filters = {"start_date": "2024-01-01", "end_date": "2024-12-31"}

        with app.test_request_context("/test"):
            with patch("utils.audit_log.get_db", return_value=db_session):
                AuditLogger.log_export(entity_type="telemetry", filters=filters, count=1000)

        audit_entry = db_session.query(AuditLog).filter_by(action="export").first()
        assert audit_entry is not None
        assert audit_entry.new_data["filters"] == filters
        assert audit_entry.new_data["count"] == 1000


class TestAuditLoggerLogImport:
    """Tests for AuditLogger.log_import method."""

    def test_log_import_basic(self, app, db_session):
        """Test logging a basic import operation."""
        from utils.audit_log import AuditLogger
        from models import AuditLog

        with app.test_request_context("/test"):
            with patch("utils.audit_log.get_db", return_value=db_session):
                AuditLogger.log_import(entity_type="telemetry", count=500)

        audit_entry = db_session.query(AuditLog).filter_by(action="import").first()
        assert audit_entry is not None
        assert "500" in audit_entry.details
        assert "telemetry" in audit_entry.details

    def test_log_import_with_source(self, app, db_session):
        """Test logging an import operation with source."""
        from utils.audit_log import AuditLogger
        from models import AuditLog

        with app.test_request_context("/test"):
            with patch("utils.audit_log.get_db", return_value=db_session):
                AuditLogger.log_import(entity_type="trips", count=10, source="import_file.csv")

        audit_entry = db_session.query(AuditLog).filter_by(action="import").first()
        assert audit_entry is not None
        assert "import_file.csv" in audit_entry.details
        assert audit_entry.new_data["source"] == "import_file.csv"


class TestAuditEndpointDecorator:
    """Tests for audit_endpoint decorator."""

    def test_audit_endpoint_logs_successful_call(self, app, caplog):
        """Test that decorator logs successful API calls."""
        from utils.audit_log import audit_endpoint
        import logging

        @audit_endpoint("trips")
        def test_function():
            return {"status": "success"}

        with app.test_request_context("/api/trips", method="GET"):
            with caplog.at_level(logging.INFO):
                result = test_function()

        assert result == {"status": "success"}
        assert "API:" in caplog.text
        assert "GET" in caplog.text

    def test_audit_endpoint_logs_failed_call(self, app, caplog):
        """Test that decorator logs failed API calls."""
        from utils.audit_log import audit_endpoint
        import logging

        @audit_endpoint("trips")
        def failing_function():
            raise ValueError("Test error")

        with app.test_request_context("/api/trips", method="POST"):
            with caplog.at_level(logging.ERROR):
                with pytest.raises(ValueError):
                    failing_function()

        assert "API Error:" in caplog.text

    def test_audit_endpoint_preserves_function_name(self):
        """Test that decorator preserves the original function name."""
        from utils.audit_log import audit_endpoint

        @audit_endpoint("test")
        def my_function():
            pass

        assert my_function.__name__ == "my_function"

    def test_audit_endpoint_passes_args_kwargs(self, app):
        """Test that decorator passes arguments correctly."""
        from utils.audit_log import audit_endpoint

        @audit_endpoint("trips")
        def function_with_args(a, b, c=None):
            return (a, b, c)

        with app.test_request_context("/test"):
            result = function_with_args(1, 2, c=3)

        assert result == (1, 2, 3)
