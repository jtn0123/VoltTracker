"""
Redis caching utilities for VoltTracker.

Provides decorators and helpers for caching expensive queries and computations.

Features:
- Query result caching with automatic invalidation
- Cache warming for frequently accessed data
- Tag-based cache invalidation
- Configurable TTL (time-to-live)
- Metrics and hit/miss tracking
"""

import hashlib
import json
import logging
import pickle
from functools import wraps
from typing import Any, Optional, Callable, List, Union
from datetime import timedelta

logger = logging.getLogger(__name__)

# Global Redis connection (lazy-loaded)
_redis_cache = None


def get_redis_cache():
    """
    Get or create Redis connection for caching.

    Returns:
        Redis connection instance or None if Redis unavailable
    """
    global _redis_cache

    if _redis_cache is None:
        try:
            from redis import Redis
            from config import Config

            # Use cache DB (DB 0 by default)
            redis_url = Config.REDIS_URL
            _redis_cache = Redis.from_url(redis_url, decode_responses=False)

            # Test connection
            _redis_cache.ping()
            logger.info(f"Connected to Redis for caching: {redis_url}")

        except Exception as e:
            logger.warning(f"Redis cache unavailable: {e}. Caching disabled.")
            _redis_cache = None

    return _redis_cache


def generate_cache_key(prefix: str, *args, **kwargs) -> str:
    """
    Generate a cache key from arguments.

    Args:
        prefix: Key prefix (e.g., "trip:summary")
        *args: Positional arguments
        **kwargs: Keyword arguments

    Returns:
        Cache key string

    Example:
        >>> key = generate_cache_key("trip:detail", trip_id=123)
        >>> print(key)
        trip:detail:c4ca4238a0b923820dcc509a6f75849b
    """
    # Create a stable representation of args/kwargs
    key_parts = [str(prefix)]

    if args:
        key_parts.extend([str(arg) for arg in args])

    if kwargs:
        # Sort kwargs for stable hashing
        sorted_kwargs = sorted(kwargs.items())
        key_parts.append(json.dumps(sorted_kwargs, sort_keys=True))

    # Hash the combined parts for consistent key length
    key_string = ":".join(key_parts)
    key_hash = hashlib.md5(key_string.encode()).hexdigest()

    return f"{prefix}:{key_hash}"


def cache_result(
    prefix: str,
    ttl: int = 300,
    tags: Optional[List[str]] = None,
    key_func: Optional[Callable] = None
):
    """
    Decorator to cache function results in Redis.

    Args:
        prefix: Cache key prefix
        ttl: Time to live in seconds (default: 300 = 5 minutes)
        tags: Optional tags for cache invalidation
        key_func: Optional custom key generation function

    Returns:
        Decorated function

    Example:
        @cache_result("trip:summary", ttl=600, tags=["trips"])
        def get_trip_summary(start_date, end_date):
            # Expensive query here
            return summary_data

        # First call: cache miss, executes function
        data = get_trip_summary("2024-01-01", "2024-01-31")

        # Second call: cache hit, returns cached data
        data = get_trip_summary("2024-01-01", "2024-01-31")
    """
    def decorator(func):
        @wraps(func)
        def wrapper(*args, **kwargs):
            redis = get_redis_cache()

            # If Redis unavailable, execute function directly
            if redis is None:
                return func(*args, **kwargs)

            # Generate cache key
            if key_func:
                cache_key = key_func(*args, **kwargs)
            else:
                cache_key = generate_cache_key(prefix, *args, **kwargs)

            try:
                # Try to get cached result
                cached_value = redis.get(cache_key)
                if cached_value is not None:
                    logger.debug(f"Cache hit: {cache_key}")
                    return pickle.loads(cached_value)

                logger.debug(f"Cache miss: {cache_key}")

                # Execute function
                result = func(*args, **kwargs)

                # Cache the result
                try:
                    serialized = pickle.dumps(result)
                    redis.setex(cache_key, ttl, serialized)

                    # Add to tag sets if provided
                    if tags:
                        for tag in tags:
                            tag_key = f"tag:{tag}"
                            redis.sadd(tag_key, cache_key)
                            # Set TTL on tag set slightly longer than cache entries
                            redis.expire(tag_key, ttl + 60)

                except Exception as e:
                    logger.warning(f"Failed to cache result for {cache_key}: {e}")

                return result

            except Exception as e:
                logger.error(f"Cache error for {cache_key}: {e}")
                # Fall back to executing function
                return func(*args, **kwargs)

        # Add cache invalidation method to the wrapper
        def invalidate(*args, **kwargs):
            """Invalidate cache for specific arguments."""
            redis = get_redis_cache()
            if redis is None:
                return

            if key_func:
                cache_key = key_func(*args, **kwargs)
            else:
                cache_key = generate_cache_key(prefix, *args, **kwargs)

            try:
                redis.delete(cache_key)
                logger.info(f"Invalidated cache: {cache_key}")
            except Exception as e:
                logger.warning(f"Failed to invalidate cache {cache_key}: {e}")

        wrapper.invalidate = invalidate
        return wrapper

    return decorator


def invalidate_cache_by_tag(tag: str) -> int:
    """
    Invalidate all cache entries with a specific tag.

    Args:
        tag: The tag to invalidate

    Returns:
        Number of cache entries invalidated

    Example:
        # Invalidate all trip-related caches
        count = invalidate_cache_by_tag("trips")
        print(f"Invalidated {count} cache entries")
    """
    redis = get_redis_cache()
    if redis is None:
        return 0

    tag_key = f"tag:{tag}"
    try:
        # Get all cache keys for this tag
        cache_keys = redis.smembers(tag_key)
        if not cache_keys:
            return 0

        # Delete all cache entries
        deleted = redis.delete(*cache_keys)

        # Delete the tag set itself
        redis.delete(tag_key)

        logger.info(f"Invalidated {deleted} cache entries for tag '{tag}'")
        return deleted

    except Exception as e:
        logger.error(f"Failed to invalidate cache by tag '{tag}': {e}")
        return 0


def invalidate_cache_pattern(pattern: str) -> int:
    """
    Invalidate all cache entries matching a pattern.

    Args:
        pattern: Redis key pattern (e.g., "trip:*", "summary:*")

    Returns:
        Number of cache entries invalidated

    Example:
        # Invalidate all trip caches
        count = invalidate_cache_pattern("trip:*")
    """
    redis = get_redis_cache()
    if redis is None:
        return 0

    try:
        # Use SCAN to find matching keys (safer than KEYS on large datasets)
        deleted = 0
        cursor = 0

        while True:
            cursor, keys = redis.scan(cursor, match=pattern, count=100)
            if keys:
                deleted += redis.delete(*keys)

            if cursor == 0:
                break

        logger.info(f"Invalidated {deleted} cache entries matching '{pattern}'")
        return deleted

    except Exception as e:
        logger.error(f"Failed to invalidate cache pattern '{pattern}': {e}")
        return 0


def cache_warm_up():
    """
    Warm up the cache with frequently accessed data.

    Call this on application startup or after cache invalidation.
    """
    logger.info("Starting cache warm-up...")

    # Import here to avoid circular dependencies
    from database import SessionLocal
    from utils.time_utils import days_ago

    db = SessionLocal()
    try:
        # Warm up trip summaries for common date ranges
        from services.trip_service import get_trip_summary_stats

        # Last 7 days
        start_7 = days_ago(7)
        logger.debug("Warming up 7-day trip summary")
        get_trip_summary_stats(db, start_7, None)

        # Last 30 days
        start_30 = days_ago(30)
        logger.debug("Warming up 30-day trip summary")
        get_trip_summary_stats(db, start_30, None)

        # Last 90 days
        start_90 = days_ago(90)
        logger.debug("Warming up 90-day trip summary")
        get_trip_summary_stats(db, start_90, None)

        logger.info("Cache warm-up completed successfully")

    except Exception as e:
        logger.error(f"Cache warm-up failed: {e}")

    finally:
        db.close()


def get_cache_stats() -> dict:
    """
    Get cache statistics (hit rate, memory usage, etc.).

    Returns:
        Dict with cache statistics

    Example:
        >>> stats = get_cache_stats()
        >>> print(f"Hit rate: {stats['hit_rate']}%")
    """
    redis = get_redis_cache()
    if redis is None:
        return {"status": "unavailable"}

    try:
        info = redis.info("stats")
        memory = redis.info("memory")

        return {
            "status": "available",
            "keyspace_hits": info.get("keyspace_hits", 0),
            "keyspace_misses": info.get("keyspace_misses", 0),
            "hit_rate": (
                round(
                    info["keyspace_hits"] / (info["keyspace_hits"] + info["keyspace_misses"]) * 100,
                    2
                )
                if (info.get("keyspace_hits", 0) + info.get("keyspace_misses", 0)) > 0
                else 0
            ),
            "used_memory_human": memory.get("used_memory_human"),
            "used_memory_peak_human": memory.get("used_memory_peak_human"),
            "connected_clients": redis.info("clients").get("connected_clients", 0),
        }

    except Exception as e:
        logger.error(f"Failed to get cache stats: {e}")
        return {"status": "error", "error": str(e)}


# Convenience decorators for common TTLs
cache_5min = lambda prefix, tags=None: cache_result(prefix, ttl=300, tags=tags)
cache_15min = lambda prefix, tags=None: cache_result(prefix, ttl=900, tags=tags)
cache_1hour = lambda prefix, tags=None: cache_result(prefix, ttl=3600, tags=tags)
cache_1day = lambda prefix, tags=None: cache_result(prefix, ttl=86400, tags=tags)
