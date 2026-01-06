"""
Flask extensions for VoltTracker.

This module initializes Flask extensions that need to be shared
across the application to avoid circular imports.
"""

import os

from flask_limiter import Limiter
from flask_limiter.util import get_remote_address

# Rate limiting storage (Redis in production, memory for development)
RATE_LIMIT_STORAGE = os.environ.get("RATE_LIMIT_STORAGE_URI", "memory://")

# Initialize rate limiter with granular limits
limiter = Limiter(
    key_func=get_remote_address,
    default_limits=["1000 per hour", "200 per minute"],  # Global default
    storage_uri=RATE_LIMIT_STORAGE,
    strategy="fixed-window",  # Can be "fixed-window", "moving-window", or "fixed-window-elastic-expiry"
    headers_enabled=True,  # Return X-RateLimit-* headers
)


# Predefined rate limit decorators for different endpoint types
class RateLimits:
    """Common rate limit configurations for different endpoint types."""

    # Read-heavy endpoints (dashboard, analytics)
    READ_HEAVY = "500 per hour"

    # Write endpoints (POST/PUT/PATCH)
    WRITE_MODERATE = "100 per hour"

    # Expensive operations (exports, reports)
    EXPENSIVE = "20 per hour"

    # Very expensive (imports, bulk operations)
    VERY_EXPENSIVE = "5 per hour"

    # Authentication/sensitive endpoints
    AUTH_STRICT = "10 per minute"

    # Public/unauthenticated endpoints
    PUBLIC = "50 per hour"

    # Telemetry ingestion (high volume expected)
    TELEMETRY = "10000 per hour"  # Allow high volume for real-time data
