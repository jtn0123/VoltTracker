"""
Volt Efficiency Tracker - Flask Application

Receives telemetry from Torque Pro and provides API for dashboard.
"""

import logging
import atexit
from flask import Flask
from flask_caching import Cache
from flask_httpauth import HTTPBasicAuth
from flask_limiter import Limiter
from flask_limiter.util import get_remote_address
from flask_socketio import SocketIO
from werkzeug.security import check_password_hash

from config import Config
from database import init_app as init_db
from routes import register_blueprints
from services.scheduler import init_scheduler, shutdown_scheduler


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
    log_format = '%(asctime)s - %(name)s - %(levelname)s - %(message)s'
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
    if not os.environ.get('FLASK_TESTING'):
        log_dir = os.path.join(os.path.dirname(__file__), 'logs')
        os.makedirs(log_dir, exist_ok=True)
        log_file = os.path.join(log_dir, 'volttracker.log')

        file_handler = RotatingFileHandler(
            log_file,
            maxBytes=10 * 1024 * 1024,  # 10 MB
            backupCount=5,
            encoding='utf-8'
        )
        file_handler.setLevel(log_level)
        file_handler.setFormatter(formatter)
        root_logger.addHandler(file_handler)


setup_logging()
logger = logging.getLogger(__name__)

# Initialize Flask app
app = Flask(__name__)
app.config.from_object(Config)

# Initialize cache (disabled in testing mode)
cache = Cache()


def init_cache(app):
    """Initialize cache based on environment."""
    import os
    if app.config.get('TESTING') or os.environ.get('FLASK_TESTING'):
        cache.init_app(app, config={'CACHE_TYPE': 'NullCache'})
    else:
        cache.init_app(app, config={
            'CACHE_TYPE': 'SimpleCache',
            'CACHE_DEFAULT_TIMEOUT': Config.CACHE_TIMEOUT_SECONDS
        })


init_cache(app)

# Initialize SocketIO for real-time updates
socketio = SocketIO(
    app,
    cors_allowed_origins=Config.CORS_ALLOWED_ORIGINS,
    async_mode='gevent',
    logger=False,
    engineio_logger=False
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
        return username or 'dev'

    if username == Config.DASHBOARD_USER:
        # Compare with hashed password if it looks hashed, otherwise direct compare
        stored_password = Config.DASHBOARD_PASSWORD
        if stored_password.startswith('pbkdf2:') or stored_password.startswith('scrypt:'):
            return username if check_password_hash(stored_password, password) else None
        else:
            return username if password == stored_password else None
    return None


# Initialize rate limiter
limiter = Limiter(
    key_func=get_remote_address,
    app=app,
    default_limits=["200 per day", "50 per hour"] if Config.RATE_LIMIT_ENABLED else [],
    storage_uri="memory://",
    enabled=Config.RATE_LIMIT_ENABLED
)


@app.after_request
def add_security_headers(response):
    """Add security headers to all responses."""
    response.headers['X-Content-Type-Options'] = 'nosniff'
    response.headers['X-Frame-Options'] = 'SAMEORIGIN'
    response.headers['X-XSS-Protection'] = '1; mode=block'
    response.headers['Referrer-Policy'] = 'strict-origin-when-cross-origin'
    response.headers['Permissions-Policy'] = 'geolocation=(), microphone=(), camera=()'
    # Add HSTS in production (when not in debug mode)
    if not app.debug:
        response.headers['Strict-Transport-Security'] = 'max-age=31536000; includeSubDomains'
    return response


# Initialize database
init_db(app)

# Register all blueprints
register_blueprints(app)

# Apply rate limiting exemption to torque upload endpoint
# (done after blueprint registration)
from routes.telemetry import telemetry_bp
limiter.exempt(telemetry_bp)

# Apply auth to dashboard route
from routes.dashboard import dashboard_bp


@dashboard_bp.before_request
def require_auth():
    """Require authentication for dashboard if configured."""
    from flask import request
    # Only apply to the main dashboard route, not API endpoints
    if request.endpoint == 'dashboard.dashboard':
        auth_result = auth.login_required(lambda: None)()
        if auth_result is not None:
            return auth_result


# Apply caching to efficiency endpoint
from routes.trips import trips_bp


@trips_bp.after_request
def cache_efficiency(response):
    """Apply caching to efficiency summary endpoint."""
    from flask import request
    if request.endpoint == 'trips.get_efficiency_summary':
        response.cache_control.max_age = 30
    elif request.endpoint == 'trips.get_soc_analysis':
        response.cache_control.max_age = 60
    return response


# Initialize background scheduler
import os
if not os.environ.get('FLASK_TESTING'):
    scheduler = init_scheduler()
    atexit.register(shutdown_scheduler)


# ============================================================================
# Main
# ============================================================================

if __name__ == '__main__':
    socketio.run(app, host=Config.FLASK_HOST, port=Config.FLASK_PORT, debug=Config.DEBUG)
