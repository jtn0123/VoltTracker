"""
Tests for job queue infrastructure.
"""

import pytest
from unittest.mock import MagicMock, patch, PropertyMock
from datetime import datetime, timedelta


class TestGetRedisConnection:
    """Tests for get_redis_connection function."""

    def test_get_redis_connection_creates_connection(self, app):
        """Test that get_redis_connection creates a Redis connection."""
        from utils import job_queue

        # Reset the global connection
        job_queue._redis_conn = None

        with patch.object(job_queue, "Config") as mock_config:
            mock_config.REDIS_URL = "redis://localhost:6379/0"
            mock_config.REDIS_QUEUE_DB = 1

            with patch.object(job_queue.Redis, "from_url") as mock_redis:
                mock_redis.return_value = MagicMock()

                conn = job_queue.get_redis_connection()

                assert conn is not None
                mock_redis.assert_called_once()
                # Check URL was modified to use queue DB
                call_args = mock_redis.call_args[0][0]
                assert "/1" in call_args

        job_queue._redis_conn = None

    def test_get_redis_connection_returns_cached_instance(self, app):
        """Test that subsequent calls return cached connection."""
        from utils import job_queue

        mock_conn = MagicMock()
        job_queue._redis_conn = mock_conn

        with patch.object(job_queue.Redis, "from_url") as mock_redis:
            conn = job_queue.get_redis_connection()

            assert conn is mock_conn
            mock_redis.assert_not_called()

        # Cleanup
        job_queue._redis_conn = None

    def test_get_redis_connection_handles_url_without_db(self, app):
        """Test handling Redis URL without DB suffix."""
        from utils import job_queue

        job_queue._redis_conn = None

        with patch.object(job_queue, "Config") as mock_config:
            mock_config.REDIS_URL = "redis://localhost:6379"
            mock_config.REDIS_QUEUE_DB = 2

            with patch.object(job_queue.Redis, "from_url") as mock_redis:
                mock_redis.return_value = MagicMock()

                job_queue.get_redis_connection()

                call_args = mock_redis.call_args[0][0]
                assert call_args.endswith("/2")

        job_queue._redis_conn = None


class TestGetJobQueue:
    """Tests for get_job_queue function."""

    def test_get_job_queue_returns_queue(self, app):
        """Test get_job_queue returns a Queue instance."""
        from utils import job_queue

        with patch.object(job_queue, "get_redis_connection") as mock_get_conn:
            mock_conn = MagicMock()
            mock_get_conn.return_value = mock_conn

            with patch.object(job_queue, "Queue") as mock_queue_class:
                mock_queue = MagicMock()
                mock_queue_class.return_value = mock_queue

                result = job_queue.get_job_queue("default")

                mock_queue_class.assert_called_once_with("default", connection=mock_conn)
                assert result is mock_queue

    def test_get_job_queue_different_queues(self, app):
        """Test getting different queue types."""
        from utils import job_queue

        with patch.object(job_queue, "get_redis_connection") as mock_get_conn:
            mock_get_conn.return_value = MagicMock()

            with patch.object(job_queue, "Queue") as mock_queue_class:
                for queue_name in ["default", "high", "low"]:
                    job_queue.get_job_queue(queue_name)

                    # Verify Queue was called with correct queue name
                    assert mock_queue_class.call_args[0][0] == queue_name


class TestEnqueueJob:
    """Tests for enqueue_job function."""

    def test_enqueue_job_basic(self, app):
        """Test basic job enqueueing."""
        from utils import job_queue

        def sample_task():
            pass

        with patch.object(job_queue, "get_job_queue") as mock_get_queue:
            mock_queue = MagicMock()
            mock_job = MagicMock()
            mock_job.id = "test-job-123"
            mock_queue.enqueue.return_value = mock_job
            mock_get_queue.return_value = mock_queue

            result = job_queue.enqueue_job(sample_task)

            assert result is mock_job
            mock_queue.enqueue.assert_called_once()

    def test_enqueue_job_with_args(self, app):
        """Test job enqueueing with arguments."""
        from utils import job_queue

        def task_with_args(a, b):
            return a + b

        with patch.object(job_queue, "get_job_queue") as mock_get_queue:
            mock_queue = MagicMock()
            mock_job = MagicMock()
            mock_job.id = "test-job-456"
            mock_queue.enqueue.return_value = mock_job
            mock_get_queue.return_value = mock_queue

            job_queue.enqueue_job(task_with_args, 1, 2, queue_name="high")

            mock_get_queue.assert_called_with("high")
            # Check args were passed
            call_args = mock_queue.enqueue.call_args
            assert 1 in call_args[0]
            assert 2 in call_args[0]

    def test_enqueue_job_with_kwargs(self, app):
        """Test job enqueueing with keyword arguments."""
        from utils import job_queue

        def task_with_kwargs(x=None, y=None):
            return x, y

        with patch.object(job_queue, "get_job_queue") as mock_get_queue:
            mock_queue = MagicMock()
            mock_job = MagicMock()
            mock_job.id = "test-job-789"
            mock_queue.enqueue.return_value = mock_job
            mock_get_queue.return_value = mock_queue

            job_queue.enqueue_job(task_with_kwargs, x=10, y=20, job_timeout=600)

            call_kwargs = mock_queue.enqueue.call_args[1]
            assert call_kwargs["x"] == 10
            assert call_kwargs["y"] == 20
            assert call_kwargs["job_timeout"] == 600

    def test_enqueue_job_with_custom_job_id(self, app):
        """Test job enqueueing with custom job ID."""
        from utils import job_queue

        def my_task():
            pass

        with patch.object(job_queue, "get_job_queue") as mock_get_queue:
            mock_queue = MagicMock()
            mock_job = MagicMock()
            mock_job.id = "custom-id-123"
            mock_queue.enqueue.return_value = mock_job
            mock_get_queue.return_value = mock_queue

            job_queue.enqueue_job(my_task, job_id="custom-id-123")

            call_kwargs = mock_queue.enqueue.call_args[1]
            assert call_kwargs["job_id"] == "custom-id-123"


class TestGetJobStatus:
    """Tests for get_job_status function."""

    def test_get_job_status_success(self, app):
        """Test getting status of an existing job."""
        from utils import job_queue

        mock_job = MagicMock()
        mock_job.id = "job-123"
        mock_job.get_status.return_value = "finished"
        mock_job.result = {"output": "success"}
        mock_job.created_at = datetime(2024, 1, 1, 10, 0, 0)
        mock_job.started_at = datetime(2024, 1, 1, 10, 0, 1)
        mock_job.ended_at = datetime(2024, 1, 1, 10, 0, 5)
        mock_job.exc_info = None
        mock_job.meta = {}

        with patch.object(job_queue.Job, "fetch") as mock_fetch:
            mock_fetch.return_value = mock_job

            with patch.object(job_queue, "get_redis_connection"):
                status = job_queue.get_job_status("job-123")

        assert status is not None
        assert status["id"] == "job-123"
        assert status["status"] == "finished"
        assert status["result"] == {"output": "success"}

    def test_get_job_status_not_found(self, app):
        """Test getting status of non-existent job."""
        from utils import job_queue

        with patch.object(job_queue.Job, "fetch") as mock_fetch:
            mock_fetch.side_effect = Exception("Job not found")

            with patch.object(job_queue, "get_redis_connection"):
                status = job_queue.get_job_status("nonexistent-job")

        assert status is None


class TestCancelJob:
    """Tests for cancel_job function."""

    def test_cancel_job_success(self, app):
        """Test successfully canceling a job."""
        from utils import job_queue

        mock_job = MagicMock()

        with patch.object(job_queue.Job, "fetch") as mock_fetch:
            mock_fetch.return_value = mock_job

            with patch.object(job_queue, "get_redis_connection"):
                result = job_queue.cancel_job("job-to-cancel")

        assert result is True
        mock_job.cancel.assert_called_once()

    def test_cancel_job_failure(self, app):
        """Test canceling a non-existent job."""
        from utils import job_queue

        with patch.object(job_queue.Job, "fetch") as mock_fetch:
            mock_fetch.side_effect = Exception("Job not found")

            with patch.object(job_queue, "get_redis_connection"):
                result = job_queue.cancel_job("nonexistent-job")

        assert result is False


class TestGetQueueStats:
    """Tests for get_queue_stats function."""

    def test_get_queue_stats(self, app):
        """Test getting queue statistics."""
        from utils import job_queue

        mock_queue = MagicMock()
        mock_queue.__len__ = MagicMock(return_value=5)
        mock_queue.started_job_registry.count = 2
        mock_queue.finished_job_registry.count = 100
        mock_queue.failed_job_registry.count = 3
        mock_queue.deferred_job_registry.count = 1
        mock_queue.scheduled_job_registry.count = 0

        with patch.object(job_queue, "get_job_queue") as mock_get_queue:
            mock_get_queue.return_value = mock_queue

            stats = job_queue.get_queue_stats("default")

        assert stats["name"] == "default"
        assert stats["queued_jobs"] == 5
        assert stats["started_jobs"] == 2
        assert stats["finished_jobs"] == 100
        assert stats["failed_jobs"] == 3


class TestGetAllQueueStats:
    """Tests for get_all_queue_stats function."""

    def test_get_all_queue_stats(self, app):
        """Test getting stats for all queues."""
        from utils import job_queue

        with patch.object(job_queue, "get_queue_stats") as mock_get_stats:
            mock_get_stats.side_effect = [
                {"name": "default", "queued_jobs": 10},
                {"name": "high", "queued_jobs": 5},
                {"name": "low", "queued_jobs": 20},
            ]

            all_stats = job_queue.get_all_queue_stats()

        assert "default" in all_stats
        assert "high" in all_stats
        assert "low" in all_stats
        assert all_stats["default"]["queued_jobs"] == 10


class TestStartWorkerInfo:
    """Tests for start_worker_info function."""

    def test_start_worker_info_returns_instructions(self, app):
        """Test that start_worker_info returns useful instructions."""
        from utils.job_queue import start_worker_info

        info = start_worker_info()

        assert "rq worker" in info
        assert "redis" in info
        assert "default" in info
        assert "high" in info
        assert "low" in info


class TestCleanupOldJobs:
    """Tests for cleanup_old_jobs function."""

    def test_cleanup_old_jobs(self, app):
        """Test cleaning up old completed jobs."""
        from utils import job_queue

        # Create mock jobs - one old, one recent
        old_job = MagicMock()
        old_job.ended_at = datetime.utcnow() - timedelta(days=10)

        recent_job = MagicMock()
        recent_job.ended_at = datetime.utcnow() - timedelta(days=2)

        mock_queue = MagicMock()
        mock_queue.finished_job_registry.get_job_ids.return_value = ["old-job", "recent-job"]

        with patch.object(job_queue, "get_job_queue") as mock_get_queue:
            mock_get_queue.return_value = mock_queue

            with patch.object(job_queue.Job, "fetch") as mock_fetch:

                def fetch_side_effect(job_id, connection):
                    if job_id == "old-job":
                        return old_job
                    return recent_job

                mock_fetch.side_effect = fetch_side_effect

                with patch.object(job_queue, "get_redis_connection"):
                    cleaned = job_queue.cleanup_old_jobs(days=7)

        # Old job should be deleted (called 3 times - once per queue: default, high, low)
        assert old_job.delete.call_count == 3
        # Recent job should not be deleted
        recent_job.delete.assert_not_called()
        # 3 old jobs cleaned (one per queue)
        assert cleaned == 3

    def test_cleanup_old_jobs_handles_errors(self, app):
        """Test cleanup handles individual job errors gracefully."""
        from utils import job_queue

        mock_queue = MagicMock()
        mock_queue.finished_job_registry.get_job_ids.return_value = ["error-job"]

        with patch.object(job_queue, "get_job_queue") as mock_get_queue:
            mock_get_queue.return_value = mock_queue

            with patch.object(job_queue.Job, "fetch") as mock_fetch:
                mock_fetch.side_effect = Exception("Job fetch error")

                with patch.object(job_queue, "get_redis_connection"):
                    # Should not raise
                    cleaned = job_queue.cleanup_old_jobs()

        assert cleaned == 0
