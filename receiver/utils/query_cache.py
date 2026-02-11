"""
Query Result Caching (P5/C1: consolidated on Redis via cache_utils).

This module now delegates to the Redis-based cache_utils module
as the single caching layer. The TTLCache in-memory implementation
has been removed to avoid cache inconsistency across workers.
"""

import functools
import logging
from typing import Callable

from utils.cache_utils import cache_result as redis_cache_result
from utils.cache_utils import invalidate_cache_pattern as _redis_invalidate_pattern
from utils.cache_utils import get_cache_stats as _redis_cache_stats

logger = logging.getLogger(__name__)

# Re-export default TTL for backwards compatibility
DEFAULT_TTL_SECONDS = 300


def cached_query(ttl: int = DEFAULT_TTL_SECONDS, key_prefix: str = ""):
    """
    Decorator to cache expensive query results via Redis.

    Delegates to cache_utils.cache_result for the actual caching.

    Usage:
        @cached_query(ttl=300, key_prefix="weather_analytics")
        def get_weather_summary(db, days=30):
            # Expensive query here
            return result
    """
    def decorator(func: Callable) -> Callable:
        func_name = f"{key_prefix}:{func.__name__}" if key_prefix else func.__name__

        @redis_cache_result(prefix=func_name, ttl=ttl, tags=[key_prefix] if key_prefix else None)
        @functools.wraps(func)
        def wrapper(*args, **kwargs):
            return func(*args, **kwargs)

        # Add cache control methods for backwards compatibility
        wrapper.cache_clear = lambda: _redis_invalidate_pattern(f"{func_name}:*")  # type: ignore[attr-defined]
        wrapper.cache_stats = _redis_cache_stats  # type: ignore[attr-defined]

        return wrapper  # type: ignore[return-value, no-any-return]

    return decorator


def clear_cache():
    """Clear all cached query results."""
    _redis_invalidate_pattern("*")
    logger.info("Query cache cleared (via Redis)")


def invalidate_cache_pattern(pattern: str):
    """Invalidate all cache keys matching pattern."""
    _redis_invalidate_pattern(f"*{pattern}*")
    logger.info(f"Invalidated cache entries matching: {pattern}")


def get_cache_stats():
    """Get cache statistics from Redis."""
    return _redis_cache_stats()
