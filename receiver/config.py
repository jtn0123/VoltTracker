import os


class Config:
    """Application configuration from environment variables."""

    # Database
    DATABASE_URL = os.environ.get(
        'DATABASE_URL',
        'postgresql://volt:changeme@localhost:5432/volt_tracker'
    )

    # Flask
    FLASK_ENV = os.environ.get('FLASK_ENV', 'production')
    DEBUG = FLASK_ENV == 'development'

    # Secret key - required in production, uses dev default only in development
    _secret_key = os.environ.get('SECRET_KEY')
    if not _secret_key and FLASK_ENV != 'development':
        raise ValueError(
            "SECRET_KEY environment variable must be set in production. "
            "Generate one with: python -c \"import secrets; print(secrets.token_hex(32))\""
        )
    SECRET_KEY = _secret_key or 'dev-secret-key-change-in-production'

    # Logging
    LOG_LEVEL = os.environ.get('LOG_LEVEL', 'INFO')

    # Volt-specific constants
    TANK_CAPACITY_GALLONS = 9.3122  # Gen 2 Volt tank capacity
    BATTERY_CAPACITY_KWH = 18.4  # Gen 2 Volt usable battery capacity
    BATTERY_ORIGINAL_CAPACITY_KWH = 18.4  # Original capacity for degradation tracking
    BATTERY_DEGRADATION_WARNING_PERCENT = 80  # Alert if capacity falls below this %
    SOC_GAS_THRESHOLD = 25.0  # SOC below this triggers gas mode detection
    RPM_THRESHOLD = 500  # RPM above this indicates engine running
    TRIP_TIMEOUT_SECONDS = 120  # 2 minutes of no data = trip closed

    # Cost tracking
    ELECTRICITY_COST_PER_KWH = float(os.environ.get('ELECTRICITY_COST', 0.12))  # $/kWh
    GAS_COST_PER_GALLON = float(os.environ.get('GAS_COST', 3.50))  # $/gallon

    # Fuel sensor smoothing
    FUEL_SMOOTHING_WINDOW = 10  # Number of readings for median filter

    # API Configuration
    FLASK_HOST = os.environ.get('FLASK_HOST', '0.0.0.0')  # nosec B104 - intentional for server
    FLASK_PORT = int(os.environ.get('FLASK_PORT', 8080))
    CACHE_TIMEOUT_SECONDS = int(os.environ.get('CACHE_TIMEOUT', 60))
    API_DEFAULT_PER_PAGE = int(os.environ.get('API_DEFAULT_PER_PAGE', 50))
    API_MAX_PER_PAGE = int(os.environ.get('API_MAX_PER_PAGE', 100))

    # Security
    TORQUE_API_TOKEN = os.environ.get('TORQUE_API_TOKEN')  # Required in production
    DASHBOARD_USER = os.environ.get('DASHBOARD_USER', 'admin')
    DASHBOARD_PASSWORD = os.environ.get('DASHBOARD_PASSWORD')  # Required in production
    RATE_LIMIT_ENABLED = os.environ.get('RATE_LIMIT_ENABLED', 'true').lower() == 'true'

    # CORS - WebSocket allowed origins
    # Default allows local development and common private network ranges
    # Set CORS_ALLOWED_ORIGINS env var to comma-separated list for custom origins
    _default_cors = "http://localhost:*,http://127.0.0.1:*,http://192.168.*:*,http://10.*:*"
    CORS_ALLOWED_ORIGINS = os.environ.get('CORS_ALLOWED_ORIGINS', _default_cors).split(',')

    # Validation Thresholds
    MIN_MPG = float(os.environ.get('MIN_MPG', 10))
    MAX_MPG = float(os.environ.get('MAX_MPG', 100))
    MIN_KWH_PER_MILE = float(os.environ.get('MIN_KWH_PER_MILE', 0.1))
    MAX_KWH_PER_MILE = float(os.environ.get('MAX_KWH_PER_MILE', 1.0))
    FUEL_TANK_MIN = float(os.environ.get('FUEL_TANK_MIN', 0))
    FUEL_TANK_MAX = float(os.environ.get('FUEL_TANK_MAX', 20))
