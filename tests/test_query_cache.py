"""
Tests for query cache utility.

Tests the TTLCache class and caching decorator.
"""

import os
import sys
import time
from unittest.mock import patch

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "receiver"))

from utils.query_cache import (
    TTLCache,
    cache_key,
    cached_query,
    clear_cache,
    get_cache_stats,
    invalidate_cache_pattern,
)


class TestTTLCache:
    """Tests for TTLCache class."""

    def test_set_and_get_basic(self):
        """Basic set and get works."""
        cache = TTLCache(max_size=10, default_ttl=60)
        cache.set("key1", "value1")
        assert cache.get("key1") == "value1"

    def test_get_missing_key_returns_none(self):
        """Getting a missing key returns None."""
        cache = TTLCache(max_size=10, default_ttl=60)
        assert cache.get("missing") is None

    def test_expired_entry_returns_none(self):
        """Expired entries return None and are removed."""
        cache = TTLCache(max_size=10, default_ttl=1)  # 1 second TTL
        cache.set("key1", "value1")

        # Should be there immediately
        assert cache.get("key1") == "value1"

        # Wait for expiration
        time.sleep(1.1)

        # Should be None now (expired)
        assert cache.get("key1") is None

    def test_eviction_at_capacity(self):
        """Oldest entries are evicted when at capacity."""
        cache = TTLCache(max_size=3, default_ttl=60)

        # Fill cache
        cache.set("key1", "value1")
        cache.set("key2", "value2")
        cache.set("key3", "value3")

        # Add one more - should evict key1
        cache.set("key4", "value4")

        # key1 should be evicted
        assert cache.get("key1") is None
        # key2, key3, key4 should still exist
        assert cache.get("key2") == "value2"
        assert cache.get("key3") == "value3"
        assert cache.get("key4") == "value4"

    def test_clear_removes_all(self):
        """Clear removes all entries."""
        cache = TTLCache(max_size=10, default_ttl=60)
        cache.set("key1", "value1")
        cache.set("key2", "value2")

        cache.clear()

        assert cache.get("key1") is None
        assert cache.get("key2") is None
        assert len(cache._cache) == 0

    def test_invalidate_pattern(self):
        """Invalidate pattern removes matching keys."""
        cache = TTLCache(max_size=10, default_ttl=60)
        cache.set("weather:temp", 70)
        cache.set("weather:wind", 10)
        cache.set("trips:total", 50)

        cache.invalidate_pattern("weather")

        # Weather keys should be gone
        assert cache.get("weather:temp") is None
        assert cache.get("weather:wind") is None
        # Trips key should still exist
        assert cache.get("trips:total") == 50

    def test_stats_returns_info(self):
        """Stats returns cache information."""
        cache = TTLCache(max_size=10, default_ttl=300)
        cache.set("key1", "value1")
        cache.set("key2", "value2")

        stats = cache.stats()

        assert stats["size"] == 2
        assert stats["max_size"] == 10
        assert stats["default_ttl"] == 300

    def test_move_to_end_on_access(self):
        """Accessed items are moved to end (LRU behavior)."""
        cache = TTLCache(max_size=3, default_ttl=60)

        cache.set("key1", "value1")
        cache.set("key2", "value2")
        cache.set("key3", "value3")

        # Access key1 to move it to end
        cache.get("key1")

        # Now add key4 - key2 should be evicted (oldest unused)
        cache.set("key4", "value4")

        # key1 should still exist (accessed recently)
        assert cache.get("key1") == "value1"
        # key2 should be evicted
        assert cache.get("key2") is None


class TestCacheKey:
    """Tests for cache_key function."""

    def test_same_args_same_key(self):
        """Same arguments produce the same key."""
        key1 = cache_key("arg1", "arg2", kwarg1="val1")
        key2 = cache_key("arg1", "arg2", kwarg1="val1")
        assert key1 == key2

    def test_different_args_different_key(self):
        """Different arguments produce different keys."""
        key1 = cache_key("arg1", "arg2")
        key2 = cache_key("arg1", "arg3")
        assert key1 != key2

    def test_handles_dict_objects(self):
        """Handles objects with __dict__ attribute."""

        class MockObject:
            def __init__(self, value):
                self.value = value

        obj = MockObject("test")
        key = cache_key(obj)
        assert isinstance(key, str)
        assert len(key) == 32  # MD5 hex digest length

    def test_handles_to_dict_objects(self):
        """Handles objects with to_dict method."""

        class DictableObject:
            def to_dict(self):
                return {"data": "test"}

        obj = DictableObject()
        key = cache_key(obj)
        assert isinstance(key, str)
        assert len(key) == 32


class TestCachedQueryDecorator:
    """Tests for cached_query decorator."""

    def test_caches_function_result(self):
        """Decorated function results are cached."""
        call_count = {"count": 0}

        @cached_query(ttl=60, key_prefix="test")
        def expensive_function(x):
            call_count["count"] += 1
            return x * 2

        # First call - should execute function
        result1 = expensive_function(5)
        assert result1 == 10
        assert call_count["count"] == 1

        # Second call - should use cache
        result2 = expensive_function(5)
        assert result2 == 10
        assert call_count["count"] == 1  # Still 1, not 2

    def test_different_args_not_cached(self):
        """Different arguments are not cached together."""
        call_count = {"count": 0}

        @cached_query(ttl=60, key_prefix="test2")
        def expensive_function(x):
            call_count["count"] += 1
            return x * 2

        # First call with arg=5
        expensive_function(5)
        assert call_count["count"] == 1

        # Second call with different arg
        expensive_function(10)
        assert call_count["count"] == 2  # Should increment

    def test_cache_clear_method(self):
        """Decorator adds cache_clear method."""
        @cached_query(ttl=60, key_prefix="test3")
        def cached_func(x):
            return x

        # Populate cache
        cached_func(1)

        # Clear should not raise
        cached_func.cache_clear()

    def test_cache_stats_method(self):
        """Decorator adds cache_stats method."""
        @cached_query(ttl=60, key_prefix="test4")
        def cached_func(x):
            return x

        stats = cached_func.cache_stats()
        assert "size" in stats
        assert "max_size" in stats


class TestModuleFunctions:
    """Tests for module-level functions."""

    def test_clear_cache(self):
        """clear_cache clears global cache."""
        # This should not raise
        clear_cache()

    def test_get_cache_stats(self):
        """get_cache_stats returns stats dict."""
        stats = get_cache_stats()
        assert "size" in stats
        assert "max_size" in stats
        assert "default_ttl" in stats

    def test_invalidate_cache_pattern(self):
        """invalidate_cache_pattern removes matching entries."""
        # This should not raise
        invalidate_cache_pattern("test_pattern")
