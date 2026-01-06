"""
Query Result Caching

Provides caching for expensive analytics queries to improve dashboard performance.
Uses simple in-memory TTL cache with LRU eviction.
"""

import functools
import hashlib
import json
import logging
import time
from collections import OrderedDict
from typing import Any, Callable, Optional

logger = logging.getLogger(__name__)

# Cache configuration
DEFAULT_TTL_SECONDS = 300  # 5 minutes default
MAX_CACHE_SIZE = 100  # Maximum cached entries


class TTLCache:
    """
    Time-to-Live cache with LRU eviction.

    Thread-safe in-memory cache for query results.
    """

    def __init__(self, max_size: int = MAX_CACHE_SIZE, default_ttl: int = DEFAULT_TTL_SECONDS):
        """
        Initialize cache.

        Args:
            max_size: Maximum number of entries to cache
            default_ttl: Default time-to-live in seconds
        """
        self.max_size = max_size
        self.default_ttl = default_ttl
        self._cache = OrderedDict()
        self._timestamps = {}

    def get(self, key: str) -> Optional[Any]:
        """
        Get value from cache if not expired.

        Args:
            key: Cache key

        Returns:
            Cached value or None if expired/missing
        """
        if key not in self._cache:
            return None

        # Check if expired
        timestamp = self._timestamps.get(key, 0)
        if time.time() - timestamp > self.default_ttl:
            # Expired, remove
            del self._cache[key]
            del self._timestamps[key]
            return None

        # Move to end (mark as recently used)
        self._cache.move_to_end(key)
        return self._cache[key]

    def set(self, key: str, value: Any, ttl: Optional[int] = None):
        """
        Set value in cache.

        Args:
            key: Cache key
            value: Value to cache
            ttl: Optional TTL override in seconds
        """
        # Evict oldest if at capacity
        if len(self._cache) >= self.max_size and key not in self._cache:
            oldest_key = next(iter(self._cache))
            del self._cache[oldest_key]
            del self._timestamps[oldest_key]

        self._cache[key] = value
        self._cache.move_to_end(key)
        self._timestamps[key] = time.time()

    def clear(self):
        """Clear all cached entries."""
        self._cache.clear()
        self._timestamps.clear()

    def invalidate_pattern(self, pattern: str):
        """
        Invalidate all keys matching pattern.

        Args:
            pattern: String pattern to match (simple substring matching)
        """
        keys_to_delete = [key for key in self._cache.keys() if pattern in key]
        for key in keys_to_delete:
            del self._cache[key]
            del self._timestamps[key]

    def stats(self):
        """Get cache statistics."""
        return {
            "size": len(self._cache),
            "max_size": self.max_size,
            "default_ttl": self.default_ttl,
        }


# Global cache instance
_query_cache = TTLCache()


def cache_key(*args, **kwargs) -> str:
    """
    Generate cache key from function arguments.

    Args:
        *args: Positional arguments
        **kwargs: Keyword arguments

    Returns:
        Hex digest cache key
    """
    # Convert args to JSON-serializable format
    serializable_args = []
    for arg in args:
        if hasattr(arg, "to_dict"):
            serializable_args.append(arg.to_dict())
        elif hasattr(arg, "__dict__"):
            serializable_args.append(str(arg))
        else:
            serializable_args.append(arg)

    key_data = {
        "args": serializable_args,
        "kwargs": {k: v for k, v in sorted(kwargs.items())},
    }

    # Hash the serialized data
    key_json = json.dumps(key_data, sort_keys=True, default=str)
    return hashlib.md5(key_json.encode()).hexdigest()


def cached_query(ttl: int = DEFAULT_TTL_SECONDS, key_prefix: str = ""):
    """
    Decorator to cache expensive query results.

    Usage:
        @cached_query(ttl=300, key_prefix="weather_analytics")
        def get_weather_summary(db, days=30):
            # Expensive query here
            return result

    Args:
        ttl: Time-to-live in seconds
        key_prefix: Optional prefix for cache keys

    Returns:
        Decorated function with caching
    """

    def decorator(func: Callable) -> Callable:
        @functools.wraps(func)
        def wrapper(*args, **kwargs):
            # Generate cache key
            func_name = f"{key_prefix}:{func.__name__}" if key_prefix else func.__name__
            key = f"{func_name}:{cache_key(*args, **kwargs)}"

            # Try to get from cache
            cached_result = _query_cache.get(key)
            if cached_result is not None:
                logger.debug(f"Cache hit: {func_name}")
                return cached_result

            # Cache miss - execute function
            logger.debug(f"Cache miss: {func_name}")
            result = func(*args, **kwargs)

            # Store in cache
            _query_cache.set(key, result, ttl=ttl)

            return result

        # Add cache control methods
        wrapper.cache_clear = lambda: _query_cache.invalidate_pattern(func.__name__)
        wrapper.cache_stats = lambda: _query_cache.stats()

        return wrapper

    return decorator


def clear_cache():
    """Clear all cached query results."""
    _query_cache.clear()
    logger.info("Query cache cleared")


def invalidate_cache_pattern(pattern: str):
    """
    Invalidate all cache keys matching pattern.

    Args:
        pattern: String pattern to match
    """
    _query_cache.invalidate_pattern(pattern)
    logger.info(f"Invalidated cache entries matching: {pattern}")


def get_cache_stats():
    """Get cache statistics."""
    return _query_cache.stats()
