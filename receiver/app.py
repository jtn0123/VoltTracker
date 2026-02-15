"""
Volt Efficiency Tracker - Flask Application

Receives telemetry from Torque Pro and provides API for dashboard.

Architecture: Uses app factory pattern via create_app().
Module-level `app = create_app()` for backward compatibility.
"""

import atexit
import hmac
import logging
import os

from config import Config
from flask import Flask, jsonify
from flask_compress import Compress
from flask_httpauth import HTTPBasicAuth
from flask_socketio import SocketIO
from extensions import limiter
from werkzeug.security import check_password_hash

_STATIC_PREFIX = "/static/"

import json


# Read version from package.json
def _read_version():
    """Read version from frontend package.json or fall back to unknown."""
    try:
        pkg_path = os.path.join(os.path.dirname(__file__), 'frontend', 'package.json')
        with open(pkg_path) as f:
            return json.load(f).get('version', 'unknown')
    except Exception:
        return 'unknown'


APP_VERSION = _read_version()


# Configure logging with rotation

def setup_logging():
    """
    Configure logging with rotating file handler and console output.

    Creates logs in ./logs directory with rotation:
    - Max 10MB per file
    - Keep 5 backup files
    - Console output for Docker compatibility
    """
    from logging.handlers import RotatingFileHandler

    from utils.log_formatter import get_log_formatter

    log_level = getattr(logging, Config.LOG_LEVEL)
    formatter = get_log_formatter()

    # Get root logger
    root_logger = logging.getLogger()
    root_logger.setLevel(log_level)

    # Clear existing handlers (avoid duplicates)
    root_logger.handlers.clear()

    # Console handler (always add for Docker/terminal visibility)
    console_handler = logging.StreamHandler()
    console_handler.setLevel(log_level)
    console_handler.setFormatter(formatter)
    root_logger.addHandler(console_handler)

    # File handler with rotation (optional, skip in testing)
    if not os.environ.get("FLASK_TESTING"):
        log_dir = os.path.join(os.path.dirname(__file__), "logs")
        os.makedirs(log_dir, exist_ok=True)
        log_file = os.path.join(log_dir, "volttracker.log")

        file_handler = RotatingFileHandler(
            log_file, maxBytes=10 * 1024 * 1024, backupCount=5, encoding="utf-8"  # 10 MB
        )
        file_handler.setLevel(log_level)
        file_handler.setFormatter(formatter)
        root_logger.addHandler(file_handler)


setup_logging()
logger = logging.getLogger(__name__)

# Shared extension instances (initialized once, bound in create_app)
compress = Compress()
# P5: Caching consolidated on Redis via utils/cache_utils.py (SimpleCache removed)
auth = HTTPBasicAuth()


def create_app(config_class=None):
    """
    Application factory for VoltTracker.

    Creates and configures the Flask application, initializes all extensions,
    registers blueprints, and sets up middleware/hooks.

    Args:
        config_class: Optional config class override (default: Config).

    Returns:
        Configured Flask application instance.
    """
    _app = Flask(__name__)
    cfg = config_class or Config
    _app.config.from_object(cfg)

    # Set request size limits
    _app.config['MAX_CONTENT_LENGTH'] = cfg.MAX_CONTENT_LENGTH

    # Initialize gzip compression (60-80% smaller API responses)
    compress.init_app(_app)

    # Initialize cache (disabled in testing mode)

    # Initialize SocketIO for real-time updates
    # Use 'threading' async_mode in testing to avoid gevent dependency
    _async_mode = "threading" if os.environ.get("FLASK_TESTING") else "gevent"
    socketio = SocketIO(
        _app, cors_allowed_origins=cfg.CORS_ALLOWED_ORIGINS, async_mode=_async_mode,
        logger=False, engineio_logger=False
    )

    # Store socketio on app for access
    _app.extensions["socketio"] = socketio

    # ========================================================================
    # WebSocket Authentication
    # ========================================================================

    def _extract_ws_credentials(ws_auth):
        """Extract token and password from WebSocket auth dict or query params."""
        import flask
        token = None
        password = None
        if ws_auth:
            token = ws_auth.get('token')
            password = ws_auth.get('password')
        if not token and not password and hasattr(flask.request, 'args'):
            token = flask.request.args.get('token')
            password = flask.request.args.get('password')
        return token, password

    def _validate_ws_credentials(token, password):
        """Validate WebSocket credentials against configured secrets."""
        if cfg.WEBSOCKET_TOKEN and token:
            if hmac.compare_digest(str(token), str(cfg.WEBSOCKET_TOKEN)):
                logger.info("WebSocket authenticated with token")
                return True
            return False
        if cfg.DASHBOARD_PASSWORD and password:
            stored = cfg.DASHBOARD_PASSWORD
            if stored.startswith("pbkdf2:") or stored.startswith("scrypt:"):
                if check_password_hash(stored, password):
                    logger.info("WebSocket authenticated with dashboard password")
                    return True
            elif hmac.compare_digest(str(password), str(stored)):
                logger.info("WebSocket authenticated with dashboard password")
                return True
        return False

    @socketio.on('connect')
    def handle_connect(ws_auth):
        """Handle WebSocket connection with authentication."""
        import flask
        from flask_socketio import disconnect

        if not cfg.WEBSOCKET_AUTH_ENABLED:
            logger.debug("WebSocket connection established (auth disabled)")
            return True
        if not cfg.DASHBOARD_PASSWORD and not cfg.WEBSOCKET_TOKEN:
            logger.debug("WebSocket connection established (no auth configured)")
            return True

        token, password = _extract_ws_credentials(ws_auth)
        if _validate_ws_credentials(token, password):
            logger.info("WebSocket connection established from %s", flask.request.remote_addr)
            return True

        logger.warning("Unauthorized WebSocket connection attempt from %s", flask.request.remote_addr)
        disconnect()
        return False

    # Expose for testing
    _app.handle_connect = handle_connect

    @socketio.on('disconnect')
    def handle_disconnect():
        """Handle WebSocket disconnection."""
        logger.debug("WebSocket client disconnected")

    # ========================================================================
    # Security: Authentication & Rate Limiting
    # ========================================================================

    @auth.verify_password
    def verify_password(username, password):
        """Verify dashboard credentials."""
        # Skip auth if no password is configured (development mode)
        if not cfg.DASHBOARD_PASSWORD:
            return username or "dev"

        if username == cfg.DASHBOARD_USER:
            # Compare with hashed password if it looks hashed, otherwise direct compare
            stored_password = cfg.DASHBOARD_PASSWORD
            if stored_password.startswith("pbkdf2:") or stored_password.startswith("scrypt:"):
                return username if check_password_hash(stored_password, password) else None
            else:
                # S3: use hmac.compare_digest for plaintext password comparison
                return username if hmac.compare_digest(str(password), str(stored_password)) else None
        return None

    # Initialize CSRF protection (API/telemetry routes exempted below after import)
    from flask_wtf.csrf import CSRFProtect
    if os.environ.get("FLASK_TESTING"):
        _app.config["WTF_CSRF_ENABLED"] = False
    csrf = CSRFProtect(_app)
    _app.extensions["csrf"] = csrf

    # Initialize rate limiter
    limiter.init_app(_app)
    if cfg.RATE_LIMIT_ENABLED:
        limiter._default_limits = [  # type: ignore[attr-defined]
            "200 per day", "50 per hour"
        ]
    limiter._enabled = cfg.RATE_LIMIT_ENABLED  # type: ignore[attr-defined]

    _PUBLIC_PREFIXES = ("/health", "/readiness", "/ready", _STATIC_PREFIX, "/clear-cache")
    _PUBLIC_EXACT = frozenset({"/health", "/healthz", "/ready", "/readiness", "/clear-cache"})

    def _is_public_path(path):
        """Check if a request path is public (no auth required)."""
        if path in _PUBLIC_EXACT:
            return True
        if any(path.startswith(p) for p in _PUBLIC_PREFIXES):
            return True
        if path.startswith("/api/telemetry/upload") or path.startswith("/torque/upload"):
            return True
        return path == "/api/errors/report"

    @_app.before_request
    def require_auth_globally():
        """Require authentication for all routes except public endpoints."""
        from flask import request as req
        if not cfg.DASHBOARD_PASSWORD:
            return None
        if _is_public_path(req.path):
            return None
        return auth.login_required(lambda: None)()

    @_app.before_request
    def inject_request_id():
        """Inject unique request ID and start timer for distributed tracing."""
        import time
        import uuid

        from flask import g, request

        # Check if client provided X-Request-ID header, otherwise generate new one
        request_id = request.headers.get("X-Request-ID") or str(uuid.uuid4())
        g.request_id = request_id
        g.request_start_time = time.monotonic()

    @_app.errorhandler(413)
    def request_entity_too_large(error):
        """Handle requests that exceed MAX_CONTENT_LENGTH."""
        max_size_mb = cfg.MAX_CONTENT_LENGTH / (1024 * 1024)
        return jsonify({
            "error": "Request entity too large",
            "message": f"Request body exceeds maximum allowed size of {max_size_mb:.1f} MB",
            "max_size_bytes": cfg.MAX_CONTENT_LENGTH
        }), 413

    _CSP_HEADER = (
        "default-src 'self'; "
        "script-src 'self' cdn.jsdelivr.net cdn.socket.io unpkg.com; "
        "style-src 'self' 'unsafe-inline' cdn.jsdelivr.net unpkg.com; "
        "img-src 'self' data: *.tile.openstreetmap.org unpkg.com; "
        "connect-src 'self' ws: wss:; "
        "font-src 'self'; "
        "frame-ancestors 'none'"
    )

    def _set_cache_headers(response, path):
        """Set appropriate cache headers based on request path."""
        if path.startswith(_STATIC_PREFIX):
            response.headers["Cache-Control"] = "public, max-age=31536000, immutable"
        elif path.startswith("/api/"):
            response.headers["Cache-Control"] = "no-store"

    def _set_security_headers(response):
        """Set common security headers."""
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["X-Frame-Options"] = "SAMEORIGIN"
        response.headers["Referrer-Policy"] = "strict-origin-when-cross-origin"
        response.headers["Permissions-Policy"] = "geolocation=(), microphone=(), camera=()"
        response.headers["Content-Security-Policy"] = _CSP_HEADER
        if not _app.debug:
            response.headers["Strict-Transport-Security"] = "max-age=31536000; includeSubDomains"

    def _add_tracing_headers(response, path):
        """Add request ID and response time to headers."""
        from flask import g, request as _req
        if hasattr(g, "request_id"):
            response.headers["X-Request-ID"] = g.request_id
        if hasattr(g, "request_start_time"):
            import time
            duration_ms = (time.monotonic() - g.request_start_time) * 1000
            response.headers["X-Response-Time"] = f"{duration_ms:.1f}ms"
            if duration_ms > 500 and not path.startswith(_STATIC_PREFIX):
                logger.warning(
                    "Slow request: %s %s took %.1fms (status %d)",
                    _req.method, path,
                    duration_ms, response.status_code,
                )

    @_app.after_request
    def add_security_headers(response):
        """Add security headers and request ID to all responses."""
        from flask import request
        _set_cache_headers(response, request.path)
        _set_security_headers(response)
        _add_tracing_headers(response, request.path)
        return response

    # Initialize database
    from database import init_app as init_db
    init_db(_app)

    from routes.battery import battery_bp  # noqa: E402
    from routes.charging import charging_bp  # noqa: E402
    from routes.telemetry import telemetry_bp  # noqa: E402
    from routes.trips import trips_bp  # noqa: E402
    from routes import register_blueprints  # noqa: E402

    @trips_bp.after_request
    def cache_efficiency(response):
        """Apply caching to efficiency summary endpoint."""
        from flask import request

        if request.endpoint == "trips.get_efficiency_summary":
            response.cache_control.max_age = 30
        elif request.endpoint == "trips.get_soc_analysis":
            response.cache_control.max_age = 60
        return response

    @battery_bp.after_request
    def cache_battery(response):
        """Apply caching to battery endpoints (data changes slowly)."""
        from flask import request

        if request.endpoint == "battery.get_battery_health":
            response.cache_control.max_age = 300  # 5 minutes
        elif request.endpoint == "battery.get_cell_voltages":
            response.cache_control.max_age = 60  # 1 minute
        return response

    @charging_bp.after_request
    def cache_charging(response):
        """Apply caching to charging summary endpoint."""
        from flask import request

        if request.endpoint == "charging.get_charging_summary":
            response.cache_control.max_age = 300  # 5 minutes
        return response

    # Register all blueprints
    register_blueprints(_app)

    # Apply rate limiting exemption to torque upload endpoint
    limiter.exempt(telemetry_bp)

    # Exempt telemetry and API blueprints from CSRF (they use token auth)
    csrf.exempt(telemetry_bp)

    # Initialize structured error tracking (404/500/unhandled exception handlers)
    from utils.error_tracking import init_error_handlers  # noqa: E402
    init_error_handlers(_app)

    # ========================================================================
    # Frontend Error Reporting Endpoint
    # ========================================================================

    @_app.route("/api/errors/report", methods=["POST"])
    def report_frontend_error():
        """
        Receive error reports from the frontend.

        Expects JSON with: message, source, lineno, colno, stack, userAgent, url
        """
        from flask import request as req
        from utils.error_tracking import track_error

        data = req.get_json(silent=True)

        # S6: Basic validation — require JSON body with an 'error' or 'message' field
        if not data or (not data.get("error") and not data.get("message")):
            return {"error": "JSON body with 'error' or 'message' field required"}, 400

        message = data.get("message") or data.get("error", "Unknown frontend error")

        fe_error = RuntimeError(f"[Frontend] {message}")
        track_error(
            fe_error,
            endpoint="/api/errors/report",
            method="POST",
            status_code=0,
            request_context={
                "source": data.get("source"),
                "lineno": data.get("lineno"),
                "colno": data.get("colno"),
                "stack": data.get("stack", "")[:2000],
                "page_url": data.get("url"),
                "user_agent": data.get("userAgent", "")[:200],
                "origin": "frontend",
            },
        )

        logger.warning("Frontend error reported: %s (source=%s)", message, data.get("source"))
        return {"status": "ok"}, 200

    # ========================================================================

    # C5: Health check, clear-cache, cache stats/invalidate, and readiness
    # endpoints have been moved to routes/system.py (system_bp).

    # R3: Initialize background scheduler inside factory with proper guarding
    if not os.environ.get("FLASK_TESTING") and not _app.config.get("TESTING"):
        # Guard against double-init when using Flask reloader
        if os.environ.get("WERKZEUG_RUN_MAIN") == "true" or not _app.debug:
            from services.scheduler import init_scheduler, shutdown_scheduler
            init_scheduler()
            atexit.register(shutdown_scheduler)

    return _app


# Module-level app for backward compatibility (gunicorn, scripts, etc.)
app = create_app()
socketio = app.extensions["socketio"]

# ============================================================================
# Main
# ============================================================================

if __name__ == "__main__":
    socketio.run(app, host=Config.FLASK_HOST, port=Config.FLASK_PORT, debug=Config.DEBUG)
