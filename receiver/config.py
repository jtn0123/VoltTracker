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
    TRIP_TIMEOUT_SECONDS = 300  # 5 minutes of no data = trip closed

    # Fuel sensor smoothing
    FUEL_SMOOTHING_WINDOW = 10  # Number of readings for median filter
