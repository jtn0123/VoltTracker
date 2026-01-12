"""
Tests for cache utilities.

Tests Redis caching decorators, cache invalidation, and cache management.
"""

import os
import sys
import pickle
from unittest.mock import MagicMock, Mock, patch

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "receiver"))

from utils.cache_utils import (  # noqa: E402
    cache_1day,
    cache_1hour,
    cache_15min,
    cache_5min,
    cache_result,
    cache_warm_up,
    generate_cache_key,
    get_cache_stats,
    get_redis_cache,
    invalidate_cache_by_tag,
    invalidate_cache_pattern,
)


class TestGenerateCacheKey:
    """Tests for generate_cache_key function."""

    def test_generate_key_with_prefix_only(self):
        """Generate key with prefix only."""
        key = generate_cache_key("test:prefix")

        assert key.startswith("test:prefix:")
        assert len(key) > len("test:prefix:")

    def test_generate_key_with_args(self):
        """Generate key with positional arguments."""
        key = generate_cache_key("user", 123, "data")

        assert key.startswith("user:")
        # Args get hashed, so just check structure
        assert len(key) > len("user:")

    def test_generate_key_with_kwargs(self):
        """Generate key with keyword arguments."""
        key = generate_cache_key("trip", trip_id=456, user_id=789)

        assert key.startswith("trip:")
        # Key should be deterministic

    def test_generate_key_is_deterministic(self):
        """Same inputs generate same key."""
        key1 = generate_cache_key("test", arg1=1, arg2=2)
        key2 = generate_cache_key("test", arg1=1, arg2=2)

        assert key1 == key2

    def test_generate_key_kwargs_order_doesnt_matter(self):
        """Kwargs in different order generate same key."""
        key1 = generate_cache_key("test", a=1, b=2, c=3)
        key2 = generate_cache_key("test", c=3, a=1, b=2)

        assert key1 == key2

    def test_generate_key_mixed_args_kwargs(self):
        """Generate key with both args and kwargs."""
        key = generate_cache_key("prefix", "arg1", 123, kwarg1="val", kwarg2=456)

        assert key.startswith("prefix:")


class TestGetRedisCache:
    """Tests for get_redis_cache function."""

    def test_get_redis_cache_creates_connection(self):
        """get_redis_cache creates Redis connection."""
        with patch("redis.Redis") as mock_redis_class:
            mock_instance = MagicMock()
            mock_redis_class.from_url.return_value = mock_instance
            mock_instance.ping.return_value = True

            # Reset global cache
            import utils.cache_utils
            utils.cache_utils._redis_cache = None

            result = get_redis_cache()

            assert result is mock_instance
            mock_redis_class.from_url.assert_called_once()
            mock_instance.ping.assert_called_once()

    def test_get_redis_cache_returns_cached_instance(self):
        """get_redis_cache returns existing instance on subsequent calls."""
        with patch("redis.Redis") as mock_redis_class:
            mock_instance = MagicMock()
            mock_redis_class.from_url.return_value = mock_instance
            mock_instance.ping.return_value = True

            import utils.cache_utils
            utils.cache_utils._redis_cache = None

            # First call
            result1 = get_redis_cache()
            # Second call
            result2 = get_redis_cache()

            assert result1 is result2
            # Should only create once
            assert mock_redis_class.from_url.call_count == 1

    def test_get_redis_cache_handles_connection_failure(self):
        """get_redis_cache handles Redis connection failure gracefully."""
        with patch("redis.Redis") as mock_redis_class:
            mock_redis_class.from_url.side_effect = Exception("Connection failed")

            import utils.cache_utils
            utils.cache_utils._redis_cache = None

            result = get_redis_cache()

            assert result is None

    def test_get_redis_cache_handles_ping_failure(self):
        """get_redis_cache handles Redis ping failure."""
        with patch("redis.Redis") as mock_redis_class:
            mock_instance = MagicMock()
            mock_redis_class.from_url.return_value = mock_instance
            mock_instance.ping.side_effect = Exception("Ping failed")

            import utils.cache_utils
            utils.cache_utils._redis_cache = None

            result = get_redis_cache()

            assert result is None


class TestCacheResultDecorator:
    """Tests for cache_result decorator."""

    def test_cache_result_no_redis(self):
        """Decorator works without Redis (fallback to direct execution)."""
        with patch("utils.cache_utils.get_redis_cache", return_value=None):
            call_count = 0

            @cache_result("test", ttl=60)
            def test_func(x):
                nonlocal call_count
                call_count += 1
                return x * 2

            result1 = test_func(5)
            result2 = test_func(5)

            assert result1 == 10
            assert result2 == 10
            # Should call function both times (no cache)
            assert call_count == 2

    def test_cache_result_cache_hit(self):
        """Decorator returns cached value on cache hit."""
        mock_redis = MagicMock()
        mock_redis.get.return_value = pickle.dumps(42)

        with patch("utils.cache_utils.get_redis_cache", return_value=mock_redis):
            call_count = 0

            @cache_result("test", ttl=60)
            def test_func(x):
                nonlocal call_count
                call_count += 1
                return x * 2

            result = test_func(21)

            assert result == 42
            # Function should not be called (cache hit)
            assert call_count == 0
            mock_redis.get.assert_called_once()

    def test_cache_result_cache_miss(self):
        """Decorator executes function and caches result on cache miss."""
        mock_redis = MagicMock()
        mock_redis.get.return_value = None

        with patch("utils.cache_utils.get_redis_cache", return_value=mock_redis):
            @cache_result("test", ttl=60)
            def test_func(x):
                return x * 2

            result = test_func(21)

            assert result == 42
            # Should cache the result
            mock_redis.setex.assert_called_once()
            call_args = mock_redis.setex.call_args
            assert call_args[0][1] == 60  # TTL
            assert pickle.loads(call_args[0][2]) == 42  # Cached value

    def test_cache_result_with_tags(self):
        """Decorator adds cache keys to tag sets."""
        mock_redis = MagicMock()
        mock_redis.get.return_value = None

        with patch("utils.cache_utils.get_redis_cache", return_value=mock_redis):
            @cache_result("test", ttl=60, tags=["trips", "summary"])
            def test_func(x):
                return x * 2

            test_func(21)

            # Should add to both tag sets
            assert mock_redis.sadd.call_count == 2
            assert mock_redis.expire.call_count == 2

    def test_cache_result_with_custom_key_func(self):
        """Decorator uses custom key function if provided."""
        mock_redis = MagicMock()
        mock_redis.get.return_value = None

        def custom_key(x):
            return f"custom:{x}"

        with patch("utils.cache_utils.get_redis_cache", return_value=mock_redis):
            @cache_result("test", ttl=60, key_func=custom_key)
            def test_func(x):
                return x * 2

            test_func(21)

            # Should use custom key
            mock_redis.get.assert_called_with("custom:21")

    def test_cache_result_invalidate_method(self):
        """Decorated function has invalidate method."""
        mock_redis = MagicMock()

        with patch("utils.cache_utils.get_redis_cache", return_value=mock_redis):
            @cache_result("test", ttl=60)
            def test_func(x):
                return x * 2

            # Invalidate cache for specific args
            test_func.invalidate(21)

            mock_redis.delete.assert_called_once()

    def test_cache_result_handles_cache_error(self):
        """Decorator handles cache errors gracefully."""
        mock_redis = MagicMock()
        mock_redis.get.side_effect = Exception("Redis error")

        with patch("utils.cache_utils.get_redis_cache", return_value=mock_redis):
            @cache_result("test", ttl=60)
            def test_func(x):
                return x * 2

            # Should still work, falling back to direct execution
            result = test_func(21)
            assert result == 42

    def test_cache_result_handles_serialization_error(self):
        """Decorator handles serialization errors gracefully."""
        mock_redis = MagicMock()
        mock_redis.get.return_value = None

        with patch("utils.cache_utils.get_redis_cache", return_value=mock_redis):
            with patch("pickle.dumps", side_effect=Exception("Cannot pickle")):
                @cache_result("test", ttl=60)
                def test_func(x):
                    return x * 2

                # Should still return result even if caching fails
                result = test_func(21)
                assert result == 42


class TestInvalidateCacheByTag:
    """Tests for invalidate_cache_by_tag function."""

    def test_invalidate_by_tag_no_redis(self):
        """invalidate_cache_by_tag returns 0 when Redis unavailable."""
        with patch("utils.cache_utils.get_redis_cache", return_value=None):
            count = invalidate_cache_by_tag("trips")
            assert count == 0

    def test_invalidate_by_tag_deletes_entries(self):
        """invalidate_cache_by_tag deletes all tagged cache entries."""
        mock_redis = MagicMock()
        mock_redis.smembers.return_value = {b"key1", b"key2", b"key3"}
        mock_redis.delete.return_value = 3

        with patch("utils.cache_utils.get_redis_cache", return_value=mock_redis):
            count = invalidate_cache_by_tag("trips")

            assert count == 3
            mock_redis.smembers.assert_called_with("tag:trips")
            # Should delete cache entries and tag set
            assert mock_redis.delete.call_count == 2

    def test_invalidate_by_tag_no_entries(self):
        """invalidate_cache_by_tag handles empty tag set."""
        mock_redis = MagicMock()
        mock_redis.smembers.return_value = set()

        with patch("utils.cache_utils.get_redis_cache", return_value=mock_redis):
            count = invalidate_cache_by_tag("nonexistent")

            assert count == 0

    def test_invalidate_by_tag_handles_error(self):
        """invalidate_cache_by_tag handles Redis errors."""
        mock_redis = MagicMock()
        mock_redis.smembers.side_effect = Exception("Redis error")

        with patch("utils.cache_utils.get_redis_cache", return_value=mock_redis):
            count = invalidate_cache_by_tag("trips")

            assert count == 0


class TestInvalidateCachePattern:
    """Tests for invalidate_cache_pattern function."""

    def test_invalidate_pattern_no_redis(self):
        """invalidate_cache_pattern returns 0 when Redis unavailable."""
        with patch("utils.cache_utils.get_redis_cache", return_value=None):
            count = invalidate_cache_pattern("trip:*")
            assert count == 0

    def test_invalidate_pattern_deletes_matching_keys(self):
        """invalidate_cache_pattern deletes all matching keys."""
        mock_redis = MagicMock()
        # Simulate SCAN returning keys in one iteration
        mock_redis.scan.return_value = (0, [b"trip:1", b"trip:2"])
        mock_redis.delete.return_value = 2

        with patch("utils.cache_utils.get_redis_cache", return_value=mock_redis):
            count = invalidate_cache_pattern("trip:*")

            assert count == 2
            mock_redis.scan.assert_called()
            mock_redis.delete.assert_called_with(b"trip:1", b"trip:2")

    def test_invalidate_pattern_multiple_scan_iterations(self):
        """invalidate_cache_pattern handles multiple SCAN iterations."""
        mock_redis = MagicMock()
        # Simulate SCAN returning keys across multiple iterations
        mock_redis.scan.side_effect = [
            (123, [b"key1", b"key2"]),  # First iteration
            (0, [b"key3"]),  # Second iteration (cursor 0 means done)
        ]
        mock_redis.delete.side_effect = [2, 1]

        with patch("utils.cache_utils.get_redis_cache", return_value=mock_redis):
            count = invalidate_cache_pattern("prefix:*")

            assert count == 3
            assert mock_redis.scan.call_count == 2

    def test_invalidate_pattern_no_matching_keys(self):
        """invalidate_cache_pattern handles no matching keys."""
        mock_redis = MagicMock()
        mock_redis.scan.return_value = (0, [])

        with patch("utils.cache_utils.get_redis_cache", return_value=mock_redis):
            count = invalidate_cache_pattern("nonexistent:*")

            assert count == 0

    def test_invalidate_pattern_handles_error(self):
        """invalidate_cache_pattern handles Redis errors."""
        mock_redis = MagicMock()
        mock_redis.scan.side_effect = Exception("Redis error")

        with patch("utils.cache_utils.get_redis_cache", return_value=mock_redis):
            count = invalidate_cache_pattern("trip:*")

            assert count == 0


class TestCacheWarmUp:
    """Tests for cache_warm_up function."""

    def test_cache_warm_up_succeeds(self):
        """cache_warm_up warms up common caches."""
        mock_db = MagicMock()
        mock_stats = MagicMock()

        with patch("database.SessionLocal", return_value=mock_db):
            # Patch the import inside the function
            with patch.dict("sys.modules", {"services.trip_service": MagicMock(get_trip_summary_stats=mock_stats)}):
                cache_warm_up()

                # Should call trip summary for 7, 30, and 90 days
                assert mock_stats.call_count == 3
                mock_db.close.assert_called_once()

    def test_cache_warm_up_handles_error(self):
        """cache_warm_up handles errors gracefully."""
        mock_db = MagicMock()
        mock_stats = MagicMock(side_effect=Exception("DB error"))

        with patch("database.SessionLocal", return_value=mock_db):
            with patch.dict("sys.modules", {"services.trip_service": MagicMock(get_trip_summary_stats=mock_stats)}):
                # Should not raise exception
                cache_warm_up()

                mock_db.close.assert_called_once()


class TestGetCacheStats:
    """Tests for get_cache_stats function."""

    def test_get_cache_stats_no_redis(self):
        """get_cache_stats returns unavailable status when Redis unavailable."""
        with patch("utils.cache_utils.get_redis_cache", return_value=None):
            stats = get_cache_stats()

            assert stats["status"] == "unavailable"

    def test_get_cache_stats_returns_statistics(self):
        """get_cache_stats returns Redis statistics."""
        mock_redis = MagicMock()
        mock_redis.info.side_effect = [
            {"keyspace_hits": 1000, "keyspace_misses": 200},  # stats
            {"used_memory_human": "10M", "used_memory_peak_human": "15M"},  # memory
            {"connected_clients": 5},  # clients
        ]

        with patch("utils.cache_utils.get_redis_cache", return_value=mock_redis):
            stats = get_cache_stats()

            assert stats["status"] == "available"
            assert stats["keyspace_hits"] == 1000
            assert stats["keyspace_misses"] == 200
            assert stats["hit_rate"] == pytest.approx(83.33, rel=0.01)
            assert stats["used_memory_human"] == "10M"
            assert stats["connected_clients"] == 5

    def test_get_cache_stats_zero_requests(self):
        """get_cache_stats handles zero hits/misses."""
        mock_redis = MagicMock()
        mock_redis.info.side_effect = [
            {"keyspace_hits": 0, "keyspace_misses": 0},  # stats
            {"used_memory_human": "1M"},  # memory
            {"connected_clients": 1},  # clients
        ]

        with patch("utils.cache_utils.get_redis_cache", return_value=mock_redis):
            stats = get_cache_stats()

            assert stats["hit_rate"] == 0

    def test_get_cache_stats_handles_error(self):
        """get_cache_stats handles Redis errors."""
        mock_redis = MagicMock()
        mock_redis.info.side_effect = Exception("Redis error")

        with patch("utils.cache_utils.get_redis_cache", return_value=mock_redis):
            stats = get_cache_stats()

            assert stats["status"] == "error"
            assert "error" in stats


class TestConvenienceDecorators:
    """Tests for convenience decorator functions."""

    def test_cache_5min_decorator(self):
        """cache_5min creates decorator with 5 minute TTL."""
        mock_redis = MagicMock()
        mock_redis.get.return_value = None

        with patch("utils.cache_utils.get_redis_cache", return_value=mock_redis):
            @cache_5min("test")
            def test_func():
                return "result"

            test_func()

            # Should use 300 second TTL (5 minutes)
            call_args = mock_redis.setex.call_args
            assert call_args[0][1] == 300

    def test_cache_15min_decorator(self):
        """cache_15min creates decorator with 15 minute TTL."""
        mock_redis = MagicMock()
        mock_redis.get.return_value = None

        with patch("utils.cache_utils.get_redis_cache", return_value=mock_redis):
            @cache_15min("test")
            def test_func():
                return "result"

            test_func()

            # Should use 900 second TTL (15 minutes)
            call_args = mock_redis.setex.call_args
            assert call_args[0][1] == 900

    def test_cache_1hour_decorator(self):
        """cache_1hour creates decorator with 1 hour TTL."""
        mock_redis = MagicMock()
        mock_redis.get.return_value = None

        with patch("utils.cache_utils.get_redis_cache", return_value=mock_redis):
            @cache_1hour("test")
            def test_func():
                return "result"

            test_func()

            # Should use 3600 second TTL (1 hour)
            call_args = mock_redis.setex.call_args
            assert call_args[0][1] == 3600

    def test_cache_1day_decorator(self):
        """cache_1day creates decorator with 1 day TTL."""
        mock_redis = MagicMock()
        mock_redis.get.return_value = None

        with patch("utils.cache_utils.get_redis_cache", return_value=mock_redis):
            @cache_1day("test")
            def test_func():
                return "result"

            test_func()

            # Should use 86400 second TTL (1 day)
            call_args = mock_redis.setex.call_args
            assert call_args[0][1] == 86400

    def test_convenience_decorators_with_tags(self):
        """Convenience decorators accept tags parameter."""
        mock_redis = MagicMock()
        mock_redis.get.return_value = None

        with patch("utils.cache_utils.get_redis_cache", return_value=mock_redis):
            @cache_5min("test", tags=["trips"])
            def test_func():
                return "result"

            test_func()

            # Should add to tag set
            mock_redis.sadd.assert_called_once()
