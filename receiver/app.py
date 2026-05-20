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
from version import APP_VERSION  # noqa: F401  (re-exported for backward compat)
from werkzeug.security import check_password_hash

_STATIC_PREFIX = "/static/"


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


def _init_socketio(_app, cfg):
    """Initialize SocketIO and register connection handlers.

    JTN-488: Pass ``manage_session=False`` so Flask-SocketIO does not try to
    copy the Flask user session into the Socket.IO request context. That path
    (``ctx.session = session_obj`` in flask_socketio/__init__.py) raises
    ``AttributeError: property 'session' of 'RequestContext' object has no setter``
    on Flask 3.1+, which breaks the namespace ``connect`` handler for every
    client and produces the ``POST /socket.io/...  400`` flood reported in the
    dashboard console. No handler here reads ``flask.session``, so disabling
    managed sessions is safe and surgical.
    """
    _async_mode = "threading" if os.environ.get("FLASK_TESTING") else "gevent"
    sio = SocketIO(
        _app, cors_allowed_origins=cfg.CORS_ALLOWED_ORIGINS, async_mode=_async_mode,
        logger=False, engineio_logger=False, manage_session=False,
    )
    _app.extensions["socketio"] = sio
    _app.handle_connect = _register_ws_events(sio, cfg)
    return sio


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


def _validate_ws_credentials(cfg, token, password):
    """Validate WebSocket credentials against configured secrets."""
    if cfg.WEBSOCKET_TOKEN and token:
        if hmac.compare_digest(str(token), str(cfg.WEBSOCKET_TOKEN)):
            logger.info("WebSocket authenticated with token")
            return True
        return False
    if not cfg.DASHBOARD_PASSWORD or not password:
        return False
    stored = cfg.DASHBOARD_PASSWORD
    if stored.startswith("pbkdf2:") or stored.startswith("scrypt:"):
        if check_password_hash(stored, password):
            logger.info("WebSocket authenticated with dashboard password")
            return True
    elif hmac.compare_digest(str(password), str(stored)):
        logger.info("WebSocket authenticated with dashboard password")
        return True
    return False


def _register_ws_events(sio, cfg):
    """Register WebSocket connect/disconnect handlers."""

    @sio.on('connect')
    def handle_connect(ws_auth):
        import flask
        from flask_socketio import disconnect

        if not cfg.WEBSOCKET_AUTH_ENABLED:
            logger.debug("WebSocket connection established (auth disabled)")
            return True
        if not cfg.DASHBOARD_PASSWORD and not cfg.WEBSOCKET_TOKEN:
            logger.debug("WebSocket connection established (no auth configured)")
            return True

        token, password = _extract_ws_credentials(ws_auth)
        if _validate_ws_credentials(cfg, token, password):
            logger.info("WebSocket connection established from %s", flask.request.remote_addr)
            return True

        logger.warning("Unauthorized WebSocket connection attempt from %s", flask.request.remote_addr)
        disconnect()
        return False

    @sio.on('disconnect')
    def handle_disconnect():
        logger.debug("WebSocket client disconnected")

    return handle_connect


def _check_password(stored_password, password):
    """Check a password against a stored (hashed or plain) value."""
    if stored_password.startswith("pbkdf2:") or stored_password.startswith("scrypt:"):
        return check_password_hash(stored_password, password)
    return hmac.compare_digest(str(password), str(stored_password))


def _init_auth(cfg):
    """Set up HTTP Basic Auth password verification."""

    @auth.verify_password
    def verify_password(username, password):
        if not cfg.DASHBOARD_PASSWORD:
            return username or "dev"
        if username != cfg.DASHBOARD_USER:
            return None
        return username if _check_password(cfg.DASHBOARD_PASSWORD, password) else None


def _init_csrf_and_limiter(_app, cfg):
    """Initialize CSRF protection and rate limiter."""
    from flask_wtf.csrf import CSRFProtect
    if os.environ.get("FLASK_TESTING"):
        _app.config["WTF_CSRF_ENABLED"] = False
    csrf = CSRFProtect(_app)
    _app.extensions["csrf"] = csrf

    limiter.init_app(_app)
    # flask-limiter exposes the toggle as the public ``enabled`` attribute.
    # The previous code assigned to ``limiter._enabled``, which is not a real
    # attribute, so RATE_LIMIT_ENABLED=false silently failed to disable limiting.
    limiter.enabled = cfg.RATE_LIMIT_ENABLED
    return csrf


_PUBLIC_PREFIXES = ("/health", "/readiness", "/ready", _STATIC_PREFIX, "/clear-cache")
_PUBLIC_EXACT = frozenset({
    "/health", "/healthz", "/ready", "/readiness", "/clear-cache", "/favicon.ico",
})


def _is_public_path(path):
    """Check if a request path is public (no auth required)."""
    if path in _PUBLIC_EXACT:
        return True
    if any(path.startswith(p) for p in _PUBLIC_PREFIXES):
        return True
    if path.startswith("/api/telemetry/upload") or path.startswith("/torque/upload"):
        return True
    return path == "/api/errors/report"


_CSP_HEADER = (
    "default-src 'self'; "
    "script-src 'self' 'unsafe-inline' cdn.jsdelivr.net cdn.socket.io unpkg.com; "
    "style-src 'self' 'unsafe-inline' cdn.jsdelivr.net unpkg.com; "
    "img-src 'self' data: *.tile.openstreetmap.org *.basemaps.cartocdn.com unpkg.com; "
    "connect-src 'self' ws: wss:; "
    "font-src 'self'; "
    "frame-ancestors 'none'"
)


def _register_request_hooks(_app, cfg):
    """Register before/after request hooks for auth, tracing, security."""

    @_app.before_request
    def require_auth_globally():
        from flask import request as req
        if not cfg.DASHBOARD_PASSWORD:
            return None
        if _is_public_path(req.path):
            return None
        return auth.login_required(lambda: None)()

    @_app.before_request
    def inject_request_id():
        import time
        import uuid
        from flask import g, request
        request_id = request.headers.get("X-Request-ID") or str(uuid.uuid4())
        g.request_id = request_id
        g.request_start_time = time.monotonic()

    @_app.errorhandler(413)
    def request_entity_too_large(error):
        max_size_mb = cfg.MAX_CONTENT_LENGTH / (1024 * 1024)
        return jsonify({
            "error": "Request entity too large",
            "message": f"Request body exceeds maximum allowed size of {max_size_mb:.1f} MB",
            "max_size_bytes": cfg.MAX_CONTENT_LENGTH
        }), 413

    @_app.after_request
    def add_security_headers(response):
        from flask import request
        _apply_cache_headers(response, request.path)
        _apply_security_headers(response, _app.debug)
        _apply_tracing_headers(response, request.path)
        return response


def _apply_cache_headers(response, path):
    """Set appropriate cache headers based on request path."""
    if path.startswith(_STATIC_PREFIX):
        response.headers["Cache-Control"] = "public, max-age=31536000, immutable"
    elif path.startswith("/api/"):
        response.headers["Cache-Control"] = "no-store"


def _apply_security_headers(response, is_debug):
    """Set common security headers."""
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "SAMEORIGIN"
    response.headers["Referrer-Policy"] = "strict-origin-when-cross-origin"
    response.headers["Permissions-Policy"] = "geolocation=(), microphone=(), camera=()"
    response.headers["Content-Security-Policy"] = _CSP_HEADER
    if not is_debug:
        response.headers["Strict-Transport-Security"] = "max-age=31536000; includeSubDomains"


def _apply_tracing_headers(response, path):
    """Add request ID and response time to headers."""
    from flask import g, request as _req
    if hasattr(g, "request_id"):
        response.headers["X-Request-ID"] = g.request_id
    if not hasattr(g, "request_start_time"):
        return
    import time
    duration_ms = (time.monotonic() - g.request_start_time) * 1000
    response.headers["X-Response-Time"] = f"{duration_ms:.1f}ms"
    if duration_ms > 500 and not path.startswith(_STATIC_PREFIX):
        logger.warning(
            "Slow request: %s %s took %.1fms (status %d)",
            _req.method, path, duration_ms, response.status_code,
        )


def _register_blueprint_caching(_app):
    """Register caching after-request handlers on blueprints."""
    from routes.battery import battery_bp
    from routes.charging import charging_bp
    from routes.trips import trips_bp

    _TRIPS_CACHE = {"trips.get_efficiency_summary": 30, "trips.get_soc_analysis": 60}
    _BATTERY_CACHE = {"battery.get_battery_health": 300, "battery.get_cell_voltages": 60}

    @trips_bp.after_request
    def cache_efficiency(response):
        from flask import request
        max_age = _TRIPS_CACHE.get(request.endpoint)
        if max_age:
            response.cache_control.max_age = max_age
        return response

    @battery_bp.after_request
    def cache_battery(response):
        from flask import request
        max_age = _BATTERY_CACHE.get(request.endpoint)
        if max_age:
            response.cache_control.max_age = max_age
        return response

    @charging_bp.after_request
    def cache_charging(response):
        from flask import request
        if request.endpoint == "charging.get_charging_summary":
            response.cache_control.max_age = 300
        return response


def _register_error_reporting(_app):
    """Register frontend error reporting endpoint."""

    @_app.route("/api/errors/report", methods=["POST"])
    def report_frontend_error():
        from flask import request as req
        from utils.error_tracking import track_error

        data = req.get_json(silent=True)
        if not data or (not data.get("error") and not data.get("message")):
            return {"error": "JSON body with 'error' or 'message' field required"}, 400

        message = data.get("message") or data.get("error", "Unknown frontend error")
        fe_error = RuntimeError(f"[Frontend] {message}")
        track_error(
            fe_error, endpoint="/api/errors/report", method="POST", status_code=0,
            request_context={
                "source": data.get("source"), "lineno": data.get("lineno"),
                "colno": data.get("colno"), "stack": data.get("stack", "")[:2000],
                "page_url": data.get("url"), "user_agent": data.get("userAgent", "")[:200],
                "origin": "frontend",
            },
        )
        logger.warning("Frontend error reported: %s (source=%s)", message, data.get("source"))
        return {"status": "ok"}, 200


def _init_scheduler(_app):
    """Initialize background scheduler if not in testing mode."""
    if os.environ.get("FLASK_TESTING") or _app.config.get("TESTING"):
        return
    if os.environ.get("WERKZEUG_RUN_MAIN") != "true" and _app.debug:
        return
    from services.scheduler import init_scheduler, shutdown_scheduler
    init_scheduler()
    atexit.register(shutdown_scheduler)


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
    _app.config['MAX_CONTENT_LENGTH'] = cfg.MAX_CONTENT_LENGTH

    compress.init_app(_app)
    _init_socketio(_app, cfg)
    _init_auth(cfg)
    csrf = _init_csrf_and_limiter(_app, cfg)
    _register_request_hooks(_app, cfg)

    from database import init_app as init_db
    init_db(_app)

    _register_blueprint_caching(_app)

    from routes.telemetry import telemetry_bp
    from routes import register_blueprints
    register_blueprints(_app)

    limiter.exempt(telemetry_bp)
    csrf.exempt(telemetry_bp)

    from utils.error_tracking import init_error_handlers
    init_error_handlers(_app)
    _register_error_reporting(_app)
    _init_scheduler(_app)

    return _app


# Module-level app for backward compatibility (gunicorn, scripts, etc.)
app = create_app()
socketio = app.extensions["socketio"]

# ============================================================================
# Main
# ============================================================================

if __name__ == "__main__":
    socketio.run(app, host=Config.FLASK_HOST, port=Config.FLASK_PORT, debug=Config.DEBUG)
