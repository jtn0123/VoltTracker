import os


class Config:
    """Application configuration from environment variables."""

    # Database
    DATABASE_URL = os.environ.get(
        'DATABASE_URL',
        'postgresql://volt:changeme@localhost:5432/volt_tracker'
    )

    # Flask
    SECRET_KEY = os.environ.get('SECRET_KEY', 'dev-secret-key-change-in-production')
    FLASK_ENV = os.environ.get('FLASK_ENV', 'production')
    DEBUG = FLASK_ENV == 'development'

    # Logging
    LOG_LEVEL = os.environ.get('LOG_LEVEL', 'INFO')

    # Volt-specific constants
    TANK_CAPACITY_GALLONS = 9.3122  # Gen 2 Volt tank capacity
    BATTERY_CAPACITY_KWH = 18.4  # Gen 2 Volt usable battery capacity
    SOC_GAS_THRESHOLD = 25.0  # SOC below this triggers gas mode detection
    RPM_THRESHOLD = 500  # RPM above this indicates engine running
    TRIP_TIMEOUT_SECONDS = 120  # 2 minutes of no data = trip closed

    # Fuel sensor smoothing
    FUEL_SMOOTHING_WINDOW = 10  # Number of readings for median filter

    # API Configuration
    FLASK_HOST = os.environ.get('FLASK_HOST', '0.0.0.0')
    FLASK_PORT = int(os.environ.get('FLASK_PORT', 8080))
    CACHE_TIMEOUT_SECONDS = int(os.environ.get('CACHE_TIMEOUT', 60))
    API_DEFAULT_PER_PAGE = int(os.environ.get('API_DEFAULT_PER_PAGE', 50))
    API_MAX_PER_PAGE = int(os.environ.get('API_MAX_PER_PAGE', 100))

    # Validation Thresholds
    MIN_MPG = float(os.environ.get('MIN_MPG', 10))
    MAX_MPG = float(os.environ.get('MAX_MPG', 100))
    MIN_KWH_PER_MILE = float(os.environ.get('MIN_KWH_PER_MILE', 0.1))
    MAX_KWH_PER_MILE = float(os.environ.get('MAX_KWH_PER_MILE', 1.0))
    FUEL_TANK_MIN = float(os.environ.get('FUEL_TANK_MIN', 0))
    FUEL_TANK_MAX = float(os.environ.get('FUEL_TANK_MAX', 20))
