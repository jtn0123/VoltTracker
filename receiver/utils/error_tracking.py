"""
Lightweight error tracking utility for VoltTracker.

Captures structured error data (timestamp, endpoint, error type, traceback,
request context) and writes to a rotating log file for review.

Usage:
    from utils.error_tracking import track_error, init_error_handlers

    # In app setup:
    init_error_handlers(app)

    # Manual tracking:
    track_error(exception, endpoint="/api/trips", context={"trip_id": 42})
"""

import json
import logging
import traceback
from datetime import datetime, timezone
from logging.handlers import RotatingFileHandler
from pathlib import Path
from typing import Any, Optional

logger = logging.getLogger(__name__)

# Dedicated error tracking logger (writes structured JSON to errors.log)
_error_logger: Optional[logging.Logger] = None


def _get_error_logger() -> logging.Logger:
    """Lazily initialize the structured error logger."""
    global _error_logger
    if _error_logger is not None:
        return _error_logger

    _error_logger = logging.getLogger("volttracker.errors")
    _error_logger.setLevel(logging.ERROR)
    _error_logger.propagate = False  # Don't duplicate to root logger

    log_dir = Path(__file__).parent.parent / "logs"
    log_dir.mkdir(exist_ok=True)

    handler = RotatingFileHandler(
        log_dir / "errors.json.log",
        maxBytes=5 * 1024 * 1024,  # 5 MB
        backupCount=3,
        encoding="utf-8",
    )
    handler.setFormatter(logging.Formatter("%(message)s"))
    _error_logger.addHandler(handler)

    return _error_logger


def track_error(
    exception: BaseException,
    *,
    endpoint: Optional[str] = None,
    method: Optional[str] = None,
    status_code: int = 500,
    request_context: Optional[dict[str, Any]] = None,
) -> dict[str, Any]:
    """
    Record a structured error entry.

    Args:
        exception: The exception that occurred.
        endpoint: The request endpoint/URL rule (e.g. "/api/trips/<id>").
        method: HTTP method (GET, POST, etc.).
        status_code: HTTP status code returned.
        request_context: Extra context (request args, headers, user info).

    Returns:
        The structured error dict that was logged.
    """
    entry = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "endpoint": endpoint,
        "method": method,
        "status_code": status_code,
        "error_type": type(exception).__name__,
        "error_message": str(exception),
        "traceback": traceback.format_exception(exception),
        "context": request_context or {},
    }

    try:
        _get_error_logger().error(json.dumps(entry, default=str))
    except Exception:
        # Last-resort fallback: use the standard logger
        logger.error("Failed to write structured error log: %s", entry)

    return entry


def _safe_request_context() -> dict[str, Any]:
    """Extract safe request context for error logging."""
    try:
        from flask import request

        return {
            "url": request.url,
            "method": request.method,
            "endpoint": request.endpoint,
            "remote_addr": request.remote_addr,
            "user_agent": request.headers.get("User-Agent", "")[:200],
            "content_length": request.content_length,
        }
    except RuntimeError:
        # Outside request context
        return {}


def init_error_handlers(app):
    """
    Register Flask error handlers that log structured error data.

    Call this once during app setup, after blueprint registration.
    """

    @app.errorhandler(404)
    def handle_404(error):
        from flask import jsonify, request

        track_error(
            error,
            endpoint=request.endpoint or request.path,
            method=request.method,
            status_code=404,
            request_context={"url": request.url},
        )
        return jsonify({"error": "Not found", "path": request.path}), 404

    @app.errorhandler(500)
    def handle_500(error):
        from flask import jsonify

        ctx = _safe_request_context()
        track_error(
            error,
            endpoint=ctx.get("endpoint"),
            method=ctx.get("method"),
            status_code=500,
            request_context=ctx,
        )
        return jsonify({"error": "Internal server error"}), 500

    @app.errorhandler(Exception)
    def handle_unhandled(error):
        from flask import jsonify
        from werkzeug.exceptions import HTTPException

        # Let HTTP exceptions (4xx, etc.) pass through to Flask's default handling
        if isinstance(error, HTTPException):
            return error.get_response()

        ctx = _safe_request_context()
        track_error(
            error,
            endpoint=ctx.get("endpoint"),
            method=ctx.get("method"),
            status_code=500,
            request_context=ctx,
        )
        logger.exception("Unhandled exception: %s", error)
        return jsonify({"error": "Internal server error"}), 500

    logger.info("Error tracking handlers registered")
