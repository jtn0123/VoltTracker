"""
Audit Logging System

Tracks data changes, deletions, and critical operations for compliance and debugging.
"""

import logging
from datetime import datetime
from enum import Enum
from functools import wraps
from typing import Any, Dict, Optional

from database import get_db
from flask import g, request
from models import AuditLog
from sqlalchemy.orm import Session

logger = logging.getLogger(__name__)


class AuditAction(Enum):
    """Audit log action types."""

    CREATE = "create"
    UPDATE = "update"
    DELETE = "delete"
    SOFT_DELETE = "soft_delete"
    EXPORT = "export"
    IMPORT = "import"
    LOGIN = "login"
    LOGOUT = "logout"
    API_CALL = "api_call"


class AuditLogger:
    """
    Centralized audit logging for data changes and operations.

    Usage:
        AuditLogger.log_change("trips", trip_id, AuditAction.UPDATE, old_data, new_data)
        AuditLogger.log_delete("trips", trip_id)
        AuditLogger.log_export("trips", filters)
    """

    @staticmethod
    def _get_user_info() -> Dict[str, Any]:
        """Extract user information from request context."""
        # Get IP address
        ip_address = request.remote_addr if request else None

        # Try to get forwarded IP if behind proxy
        if request and request.headers.get("X-Forwarded-For"):
            ip_address = request.headers["X-Forwarded-For"].split(",")[0].strip()

        # Get user agent
        user_agent = request.headers.get("User-Agent") if request else None

        # Get auth info from headers (if you implement authentication later)
        user_id = g.get("user_id") if hasattr(g, "user_id") else None
        username = g.get("username") if hasattr(g, "username") else None

        return {"ip_address": ip_address, "user_agent": user_agent, "user_id": user_id, "username": username}

    @staticmethod
    def log_change(
        entity_type: str,
        entity_id: int,
        action: AuditAction,
        old_data: Optional[Dict] = None,
        new_data: Optional[Dict] = None,
        details: Optional[str] = None,
        db: Optional[Session] = None,
    ):
        """
        Log a data change operation.

        Args:
            entity_type: Type of entity (e.g., "trips", "charging_sessions")
            entity_id: ID of the entity being changed
            action: AuditAction enum value
            old_data: Previous state (for updates)
            new_data: New state (for creates/updates)
            details: Optional additional details
            db: Database session (optional - will create one if not provided)
        """
        try:
            user_info = AuditLogger._get_user_info()

            audit_log = AuditLog(
                entity_type=entity_type,
                entity_id=str(entity_id),
                action=action.value,
                old_data=old_data,
                new_data=new_data,
                ip_address=user_info["ip_address"],
                user_agent=user_info["user_agent"],
                user_id=user_info["user_id"],
                username=user_info["username"],
                details=details,
            )

            if db is None:
                db = get_db()
                audit_log.save(db)
            else:
                db.add(audit_log)
                db.commit()

            logger.info(
                f"Audit: {action.value} {entity_type}/{entity_id} by {user_info['ip_address']} "
                f"({user_info['username'] or 'anonymous'})"
            )

        except Exception as e:
            logger.error(f"Failed to write audit log: {e}", exc_info=True)
            # Don't fail the actual operation if audit logging fails

    @staticmethod
    def log_delete(entity_type: str, entity_id: int, soft: bool = False, db: Optional[Session] = None):
        """
        Log a deletion operation.

        Args:
            entity_type: Type of entity
            entity_id: ID being deleted
            soft: Whether this is a soft delete
            db: Database session
        """
        action = AuditAction.SOFT_DELETE if soft else AuditAction.DELETE
        details = "Soft delete (recoverable)" if soft else "Hard delete (permanent)"
        AuditLogger.log_change(entity_type, entity_id, action, details=details, db=db)

    @staticmethod
    def log_export(entity_type: str, filters: Optional[Dict] = None, count: Optional[int] = None):
        """
        Log a data export operation.

        Args:
            entity_type: Type of data being exported
            filters: Filter parameters used
            count: Number of records exported
        """
        details = f"Exported {count} records" if count else "Data export"
        if filters:
            details += f" with filters: {filters}"

        AuditLogger.log_change(
            entity_type="exports",
            entity_id=0,  # No specific entity
            action=AuditAction.EXPORT,
            new_data={"entity_type": entity_type, "filters": filters, "count": count},
            details=details,
        )

    @staticmethod
    def log_import(entity_type: str, count: int, source: Optional[str] = None):
        """
        Log a data import operation.

        Args:
            entity_type: Type of data being imported
            count: Number of records imported
            source: Source of the import (filename, etc.)
        """
        details = f"Imported {count} {entity_type} records"
        if source:
            details += f" from {source}"

        AuditLogger.log_change(
            entity_type="imports",
            entity_id=0,
            action=AuditAction.IMPORT,
            new_data={"entity_type": entity_type, "count": count, "source": source},
            details=details,
        )


def audit_endpoint(entity_type: str):
    """
    Decorator to automatically log API endpoint calls.

    Usage:
        @audit_endpoint("trips")
        def create_trip():
            # ...
    """

    def decorator(func):
        @wraps(func)
        def wrapper(*args, **kwargs):
            try:
                # Execute the function
                result = func(*args, **kwargs)

                # Log successful API call
                user_info = AuditLogger._get_user_info()
                logger.info(
                    f"API: {request.method} {request.path} "
                    f"by {user_info['ip_address']} ({user_info['username'] or 'anonymous'})"
                )

                return result

            except Exception as e:
                # Log failed API call
                logger.error(f"API Error: {request.method} {request.path} - {str(e)}")
                raise

        return wrapper

    return decorator
