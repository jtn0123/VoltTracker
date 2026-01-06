"""
Flask extensions for VoltTracker.

This module initializes Flask extensions that need to be shared
across the application to avoid circular imports.
"""

from flask_limiter import Limiter
from flask_limiter.util import get_remote_address

# Initialize rate limiter (will be configured in app.py)
limiter = Limiter(
    key_func=get_remote_address,
    default_limits=["1000 per hour"],
    storage_uri="memory://",
)
