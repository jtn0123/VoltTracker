import os
import socket
from datetime import datetime


class Config:
    """Application configuration from environment variables."""

    # Service Metadata (for loggingsucks.com service-aware logging)
    SERVICE_NAME = "volttracker"
    APP_VERSION = os.environ.get("APP_VERSION", "0.1.0-dev")
    ENVIRONMENT = os.environ.get("ENVIRONMENT", "development")
    DEPLOYMENT_ID = os.environ.get("DEPLOYMENT_ID", f"local-{socket.gethostname()}")
    DEPLOYMENT_TIMESTAMP = os.environ.get("DEPLOYMENT_TIMESTAMP", datetime.utcnow().isoformat())
    REGION = os.environ.get("REGION", "local")

    # Database
    DATABASE_URL = os.environ.get("DATABASE_URL", "postgresql://volt:changeme@localhost:5432/volt_tracker")

    # Redis Configuration (for caching and async job queue)
    REDIS_URL = os.environ.get("REDIS_URL", "redis://localhost:6379/0")
    REDIS_CACHE_DB = int(os.environ.get("REDIS_CACHE_DB", 0))  # DB 0 for cache
    REDIS_QUEUE_DB = int(os.environ.get("REDIS_QUEUE_DB", 1))  # DB 1 for job queue

    # Flask
    FLASK_ENV = os.environ.get("FLASK_ENV", "production")
    DEBUG = FLASK_ENV == "development"

    # Secret key - required in production, uses dev default only in development
    _secret_key = os.environ.get("SECRET_KEY")
    if not _secret_key and FLASK_ENV != "development":
        raise ValueError(
            "SECRET_KEY environment variable must be set in production. "
            'Generate one with: python -c "import secrets; print(secrets.token_hex(32))"'
        )
    SECRET_KEY = _secret_key or "dev-secret-key-change-in-production"

    # Logging
    LOG_LEVEL = os.environ.get("LOG_LEVEL", "INFO")

    # Wide Events Logging - Tail Sampling Configuration
    # Always log: errors, slow requests, critical business events
    # Sample rate for fast successful requests (0.0-1.0)
    LOGGING_SAMPLE_RATE_TELEMETRY = float(os.environ.get("LOG_SAMPLE_TELEMETRY", 0.05))  # 5%
    LOGGING_SAMPLE_RATE_TRIP = float(os.environ.get("LOG_SAMPLE_TRIP", 1.0))  # 100% (critical)
    LOGGING_SAMPLE_RATE_ROUTE = float(os.environ.get("LOG_SAMPLE_ROUTE", 0.10))  # 10%
    LOGGING_SLOW_THRESHOLD_MS = float(os.environ.get("LOG_SLOW_THRESHOLD_MS", 1000))  # 1s

    # Feature Flags (for A/B testing and gradual rollouts)
    FEATURE_ENHANCED_ROUTE_DETECTION = os.environ.get("FEATURE_ROUTE_DETECTION", "false").lower() == "true"
    FEATURE_WEATHER_INTEGRATION = os.environ.get("FEATURE_WEATHER", "true").lower() == "true"
    FEATURE_PREDICTIVE_RANGE = os.environ.get("FEATURE_PREDICTIVE_RANGE", "false").lower() == "true"
    FEATURE_ELEVATION_TRACKING = os.environ.get("FEATURE_ELEVATION", "true").lower() == "true"

    # Elevation API Configuration
    ELEVATION_SAMPLE_RATE = int(os.environ.get("ELEVATION_SAMPLE_RATE", 25))  # Sample 1 in N GPS points

    # Volt-specific constants
    TANK_CAPACITY_GALLONS = 9.3122  # Gen 2 Volt tank capacity
    BATTERY_CAPACITY_KWH = 18.4  # Gen 2 Volt usable battery capacity
    BATTERY_ORIGINAL_CAPACITY_KWH = 18.4  # Original capacity for degradation tracking
    BATTERY_DEGRADATION_WARNING_PERCENT = 80  # Alert if capacity falls below this %
    SOC_GAS_THRESHOLD = 25.0  # SOC below this triggers gas mode detection
    RPM_THRESHOLD = 500  # RPM above this indicates engine running
    TRIP_TIMEOUT_SECONDS = 120  # 2 minutes of no data = trip closed

    # Cost tracking
    ELECTRICITY_COST_PER_KWH = float(os.environ.get("ELECTRICITY_COST", 0.12))  # $/kWh
    GAS_COST_PER_GALLON = float(os.environ.get("GAS_COST", 3.50))  # $/gallon

    # Fuel sensor smoothing
    FUEL_SMOOTHING_WINDOW = 10  # Number of readings for median filter

    # API Configuration
    FLASK_HOST = os.environ.get("FLASK_HOST", "0.0.0.0")  # nosec B104 - intentional for server
    FLASK_PORT = int(os.environ.get("FLASK_PORT", 8080))
    CACHE_TIMEOUT_SECONDS = int(os.environ.get("CACHE_TIMEOUT", 60))
    WEATHER_CACHE_TIMEOUT_SECONDS = int(os.environ.get("WEATHER_CACHE_TIMEOUT", 3600))  # 1 hour
    WEATHER_SAMPLE_INTERVAL_MINUTES = int(os.environ.get("WEATHER_SAMPLE_INTERVAL", 15))  # Sample weather every N minutes
    API_DEFAULT_PER_PAGE = int(os.environ.get("API_DEFAULT_PER_PAGE", 50))
    API_MAX_PER_PAGE = int(os.environ.get("API_MAX_PER_PAGE", 100))
    API_TELEMETRY_LIMIT_DEFAULT = int(os.environ.get("API_TELEMETRY_LIMIT_DEFAULT", 500))
    API_TELEMETRY_LIMIT_MAX = int(os.environ.get("API_TELEMETRY_LIMIT_MAX", 2000))

    # Trip Processing Thresholds
    MIN_TRIP_MILES = float(os.environ.get("MIN_TRIP_MILES", 0.1))  # Minimum distance for valid trip

    # Charging Session Configuration
    MAX_CHARGING_CURVE_POINTS = int(os.environ.get("MAX_CHARGING_CURVE_POINTS", 1000))  # Max curve data points

    # Security
    TORQUE_API_TOKEN = os.environ.get("TORQUE_API_TOKEN")  # Required in production
    DASHBOARD_USER = os.environ.get("DASHBOARD_USER", "admin")
    DASHBOARD_PASSWORD = os.environ.get("DASHBOARD_PASSWORD")  # Required in production
    RATE_LIMIT_ENABLED = os.environ.get("RATE_LIMIT_ENABLED", "true").lower() == "true"

    # WebSocket Authentication - uses DASHBOARD_PASSWORD for auth unless separate token provided
    WEBSOCKET_AUTH_ENABLED = os.environ.get("WEBSOCKET_AUTH_ENABLED", "true").lower() == "true"
    # If not set, WebSocket auth will use DASHBOARD_PASSWORD. Set this for separate WebSocket tokens.
    WEBSOCKET_TOKEN = os.environ.get("WEBSOCKET_TOKEN")

    # API Key Management
    # Format: <key_id>:<hashed_key> (comma-separated for multiple keys)
    # Example: "key1:pbkdf2:sha256:...,key2:pbkdf2:sha256:..."
    API_KEYS = os.environ.get("API_KEYS", "")  # Optional, for advanced API key management

    # Request size limits (prevent DoS via large uploads)
    MAX_CONTENT_LENGTH = int(os.environ.get("MAX_CONTENT_LENGTH", 50 * 1024 * 1024))  # 50MB default
    MAX_CSV_FILE_SIZE = int(os.environ.get("MAX_CSV_FILE_SIZE", 25 * 1024 * 1024))  # 25MB for CSV imports
    MAX_CSV_ROWS = int(os.environ.get("MAX_CSV_ROWS", 100000))  # Max rows in CSV import

    # CORS - WebSocket allowed origins
    # Default allows local development and common private network ranges
    # Set CORS_ALLOWED_ORIGINS env var to comma-separated list for custom origins
    _default_cors = "http://localhost:*,http://127.0.0.1:*,http://192.168.*:*,http://10.*:*"
    CORS_ALLOWED_ORIGINS = os.environ.get("CORS_ALLOWED_ORIGINS", _default_cors).split(",")

    # Validation Thresholds (Volt typical: 25-50 MPG, allow 15-60 for margin)
    MIN_MPG = float(os.environ.get("MIN_MPG", 15))
    MAX_MPG = float(os.environ.get("MAX_MPG", 60))
    MIN_KWH_PER_MILE = float(os.environ.get("MIN_KWH_PER_MILE", 0.1))
    MAX_KWH_PER_MILE = float(os.environ.get("MAX_KWH_PER_MILE", 1.0))
    FUEL_TANK_MIN = float(os.environ.get("FUEL_TANK_MIN", 0))
    FUEL_TANK_MAX = float(os.environ.get("FUEL_TANK_MAX", 20))

    # Analytics Configuration
    # Temperature bands for weather analytics (Fahrenheit)
    # Format: (name, min_temp, max_temp) where None = no bound
    ANALYTICS_TEMP_BANDS = [
        ("freezing", None, 32),
        ("cold", 32, 45),
        ("cool", 45, 55),
        ("ideal", 55, 75),
        ("warm", 75, 85),
        ("hot", 85, 95),
        ("very_hot", 95, None),
    ]

    # Wind speed bands for weather analytics (MPH)
    ANALYTICS_WIND_BANDS = [
        ("calm", None, 5),
        ("light", 5, 15),
        ("moderate", 15, 25),
        ("strong", 25, None),
    ]
