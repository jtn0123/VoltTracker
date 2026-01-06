"""
Volt Efficiency Tracker - Flask Application

Receives telemetry from Torque Pro and provides API for dashboard.
"""

import atexit
import logging

from config import Config
from database import init_app as init_db
from flask import Flask
from flask_caching import Cache
from flask_compress import Compress
from flask_httpauth import HTTPBasicAuth
from flask_socketio import SocketIO
from extensions import limiter
from routes import register_blueprints
from services.scheduler import init_scheduler, shutdown_scheduler
from werkzeug.security import check_password_hash


# Configure logging with rotation
def setup_logging():
    """
    Configure logging with rotating file handler and console output.

    Creates logs in ./logs directory with rotation:
    - Max 10MB per file
    - Keep 5 backup files
    - Console output for Docker compatibility
    """
    import os
    from logging.handlers import RotatingFileHandler

    log_level = getattr(logging, Config.LOG_LEVEL)
    log_format = "%(asctime)s - %(name)s - %(levelname)s - %(message)s"
    formatter = logging.Formatter(log_format)

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

# Initialize Flask app
app = Flask(__name__)
app.config.from_object(Config)

# Initialize gzip compression (60-80% smaller API responses)
compress = Compress()
compress.init_app(app)

# Initialize cache (disabled in testing mode)
cache = Cache()


def init_cache(app):
    """Initialize cache based on environment."""
    import os

    if app.config.get("TESTING") or os.environ.get("FLASK_TESTING"):
        cache.init_app(app, config={"CACHE_TYPE": "NullCache"})
    else:
        cache.init_app(app, config={"CACHE_TYPE": "SimpleCache", "CACHE_DEFAULT_TIMEOUT": Config.CACHE_TIMEOUT_SECONDS})


init_cache(app)

# Initialize SocketIO for real-time updates
socketio = SocketIO(
    app, cors_allowed_origins=Config.CORS_ALLOWED_ORIGINS, async_mode="gevent", logger=False, engineio_logger=False
)

# ============================================================================
# Security: Authentication & Rate Limiting
# ============================================================================

# Initialize HTTP Basic Auth for dashboard
auth = HTTPBasicAuth()


@auth.verify_password
def verify_password(username, password):
    """Verify dashboard credentials."""
    # Skip auth if no password is configured (development mode)
    if not Config.DASHBOARD_PASSWORD:
        return username or "dev"

    if username == Config.DASHBOARD_USER:
        # Compare with hashed password if it looks hashed, otherwise direct compare
        stored_password = Config.DASHBOARD_PASSWORD
        if stored_password.startswith("pbkdf2:") or stored_password.startswith("scrypt:"):
            return username if check_password_hash(stored_password, password) else None
        else:
            return username if password == stored_password else None
    return None


# Initialize rate limiter
limiter.init_app(app)
if Config.RATE_LIMIT_ENABLED:
    limiter._default_limits = ["200 per day", "50 per hour"]
limiter._enabled = Config.RATE_LIMIT_ENABLED


@app.before_request
def inject_request_id():
    """Inject unique request ID for distributed tracing."""
    import uuid

    from flask import g, request

    # Check if client provided X-Request-ID header, otherwise generate new one
    request_id = request.headers.get("X-Request-ID") or str(uuid.uuid4())
    g.request_id = request_id


@app.after_request
def add_security_headers(response):
    """Add security headers and request ID to all responses."""
    from flask import g

    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "SAMEORIGIN"
    response.headers["X-XSS-Protection"] = "1; mode=block"
    response.headers["Referrer-Policy"] = "strict-origin-when-cross-origin"
    response.headers["Permissions-Policy"] = "geolocation=(), microphone=(), camera=()"
    # Add HSTS in production (when not in debug mode)
    if not app.debug:
        response.headers["Strict-Transport-Security"] = "max-age=31536000; includeSubDomains"

    # Add request ID to response headers for tracing
    if hasattr(g, "request_id"):
        response.headers["X-Request-ID"] = g.request_id

    return response


# Initialize database
init_db(app)

from routes.battery import battery_bp  # noqa: E402
from routes.charging import charging_bp  # noqa: E402
from routes.dashboard import dashboard_bp  # noqa: E402

# Configure blueprint hooks before registration
from routes.telemetry import telemetry_bp  # noqa: E402
from routes.trips import trips_bp  # noqa: E402


@dashboard_bp.before_request
def require_auth():
    """Require authentication for dashboard if configured."""
    from flask import request

    # Only apply to the main dashboard route, not API endpoints
    if request.endpoint == "dashboard.dashboard":
        auth_result = auth.login_required(lambda: None)()
        if auth_result is not None:
            return auth_result


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
register_blueprints(app)

# Apply rate limiting exemption to torque upload endpoint
# (done after blueprint registration)
limiter.exempt(telemetry_bp)


# ============================================================================
# Health Check Endpoints (Kubernetes/Docker)
# ============================================================================


@app.route("/health", methods=["GET"])
@app.route("/healthz", methods=["GET"])  # Alternative naming
def health_check():
    """
    Liveness probe - basic check that app is running.

    Returns 200 if the application is alive (can handle requests).
    Does not check external dependencies like database.
    """
    return {"status": "healthy", "service": "volttracker"}, 200


@app.route("/ready", methods=["GET"])
@app.route("/readiness", methods=["GET"])  # Alternative naming
def readiness_check():
    """
    Readiness probe - check if app is ready to serve traffic.

    Tests:
    - Database connectivity
    - Scheduler status (if running)

    Returns 200 if ready, 503 if not ready.
    """
    from database import SessionLocal
    from sqlalchemy import text

    checks = {"database": False, "scheduler": False}
    errors = []

    # Check database connectivity
    try:
        db = SessionLocal()
        # Simple query to verify connection
        db.execute(text("SELECT 1"))
        db.close()
        checks["database"] = True
    except Exception as e:
        errors.append(f"Database: {str(e)[:100]}")

    # Check scheduler status (if not in testing mode)
    if not os.environ.get("FLASK_TESTING"):
        from services.scheduler import scheduler as sched

        if sched and sched.running:
            checks["scheduler"] = True
        else:
            errors.append("Scheduler: not running")
    else:
        checks["scheduler"] = True  # Skip check in testing

    # All checks must pass
    all_healthy = all(checks.values())

    response = {"status": "ready" if all_healthy else "not_ready", "checks": checks}

    if errors:
        response["errors"] = errors

    return response, 200 if all_healthy else 503


# Initialize background scheduler
import os  # noqa: E402

if not os.environ.get("FLASK_TESTING"):
    scheduler = init_scheduler()
    atexit.register(shutdown_scheduler)


# ============================================================================
# Main
# ============================================================================

if __name__ == "__main__":
    socketio.run(app, host=Config.FLASK_HOST, port=Config.FLASK_PORT, debug=Config.DEBUG)
