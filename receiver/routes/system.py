"""
System routes for VoltTracker (C5).

Health checks, cache management, and utility endpoints.
"""

import logging
import os

from flask import Blueprint, jsonify, render_template, request
from version import APP_VERSION

logger = logging.getLogger(__name__)

system_bp = Blueprint("system", __name__)


@system_bp.route("/health", methods=["GET"])
@system_bp.route("/healthz", methods=["GET"])
def health_check():
    """Liveness probe - basic check that app is running."""
    # APP_VERSION comes from the dedicated ``version`` module (not ``app``)
    # to avoid re-importing ``app.py`` when the server is started via
    # ``python receiver/app.py``. See JTN-482.
    return {"status": "healthy", "service": "volttracker", "version": APP_VERSION}, 200


@system_bp.route("/clear-cache", methods=["GET"])
def clear_cache_page():
    """Utility page to clear service worker and browser caches (C5: moved to template)."""
    return render_template("clear_cache.html")


@system_bp.route("/cache/stats", methods=["GET"])
def cache_stats():
    """Get Redis cache statistics."""
    from utils.cache_utils import get_cache_stats
    stats = get_cache_stats()
    return jsonify(stats), 200


@system_bp.route("/cache/invalidate", methods=["POST"])
def cache_invalidate():
    """Invalidate cache entries by pattern or tag.

    S4: Explicit auth check — this endpoint mutates cache state,
    so we verify authentication even though global middleware should catch it.
    """
    from utils.auth_utils import require_auth
    auth_error = require_auth()
    if auth_error:
        return auth_error

    from utils.cache_utils import invalidate_cache_pattern, invalidate_cache_by_tag

    data = request.get_json() or {}
    pattern = data.get("pattern")
    tag = data.get("tag")

    if not pattern and not tag:
        return jsonify({"error": "Either 'pattern' or 'tag' must be provided"}), 400

    deleted = 0
    if pattern:
        deleted += invalidate_cache_pattern(pattern)
    if tag:
        deleted += invalidate_cache_by_tag(tag)

    return jsonify({"deleted": deleted, "message": f"Invalidated {deleted} cache entries"}), 200


@system_bp.route("/ready", methods=["GET"])
@system_bp.route("/readiness", methods=["GET"])
def readiness_check():
    """Readiness probe - check if app is ready to serve traffic."""
    from database import SessionLocal
    from sqlalchemy import text

    checks = {"database": False, "scheduler": False}
    errors = []

    try:
        db = SessionLocal()
        db.execute(text("SELECT 1"))
        db.close()
        checks["database"] = True
    except Exception as e:
        errors.append(f"Database: {str(e)[:100]}")

    if os.environ.get("FLASK_TESTING"):
        checks["scheduler"] = True
    else:
        from services.scheduler import scheduler as sched
        if sched and sched.running:
            checks["scheduler"] = True
        else:
            errors.append("Scheduler: not running")

    all_healthy = all(checks.values())

    response = {"status": "ready" if all_healthy else "not_ready", "checks": checks}

    if errors:
        response["errors"] = errors

    return response, 200 if all_healthy else 503
