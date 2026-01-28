"""
Job Queue Infrastructure for VoltTracker.

Uses Redis Queue (RQ) for background job processing.

Features:
- Async weather API calls
- Async elevation API calls
- Background trip finalization
- Scheduled cleanup tasks
- Export generation
"""

import logging
from typing import Optional, Any, Dict
from redis import Redis
from rq import Queue, Worker, Retry
from rq.job import Job
from config import Config

logger = logging.getLogger(__name__)


# Global Redis connection
_redis_conn: Optional[Redis] = None
_job_queue: Optional[Queue] = None


def get_redis_connection() -> Redis:
    """
    Get or create Redis connection for job queue.

    Returns:
        Redis connection instance
    """
    global _redis_conn

    if _redis_conn is None:
        # Parse Redis URL to extract DB number for queue
        redis_url = Config.REDIS_URL
        if "/0" in redis_url:
            redis_url = redis_url.replace("/0", f"/{Config.REDIS_QUEUE_DB}")
        elif not redis_url.endswith(f"/{Config.REDIS_QUEUE_DB}"):
            # If URL doesn't have /db suffix, add it
            redis_url = f"{redis_url.rstrip('/')}/{Config.REDIS_QUEUE_DB}"

        _redis_conn = Redis.from_url(redis_url, decode_responses=False)
        logger.info(f"Connected to Redis for job queue: {redis_url}")

    return _redis_conn


def get_job_queue(queue_name: str = "default") -> Queue:
    """
    Get or create job queue.

    Args:
        queue_name: Name of the queue (default: "default")
                   Available queues: "default", "high", "low"

    Returns:
        RQ Queue instance
    """
    return Queue(queue_name, connection=get_redis_connection())


def enqueue_job(
    func: callable,
    *args,
    queue_name: str = "default",
    job_timeout: int = 300,
    retry: Optional[Retry] = None,
    job_id: Optional[str] = None,
    **kwargs
) -> Job:
    """
    Enqueue a background job.

    Args:
        func: The function to execute
        *args: Positional arguments for the function
        queue_name: Queue to use ("default", "high", "low")
        job_timeout: Timeout in seconds (default: 300 = 5 minutes)
        retry: Retry configuration (default: None = no retry)
        job_id: Custom job ID (default: auto-generated)
        **kwargs: Keyword arguments for the function

    Returns:
        RQ Job instance

    Example:
        >>> from utils.job_queue import enqueue_job
        >>> from services.weather_service import fetch_weather_for_trip
        >>> job = enqueue_job(fetch_weather_for_trip, trip_id=123, queue_name="high")
        >>> print(f"Job {job.id} enqueued")
    """
    queue = get_job_queue(queue_name)

    job = queue.enqueue(
        func,
        *args,
        **kwargs,
        job_timeout=job_timeout,
        retry=retry,
        job_id=job_id
    )

    logger.info(
        f"Enqueued job {job.id} on queue '{queue_name}': "
        f"{func.__module__}.{func.__name__}"
    )

    return job


def get_job_status(job_id: str) -> Optional[Dict[str, Any]]:
    """
    Get status of a job.

    Args:
        job_id: The job ID

    Returns:
        Dict with job status information, or None if job not found
    """
    try:
        job = Job.fetch(job_id, connection=get_redis_connection())
        return {
            "id": job.id,
            "status": job.get_status(),
            "result": job.result,
            "created_at": job.created_at,
            "started_at": job.started_at,
            "ended_at": job.ended_at,
            "exc_info": job.exc_info,
            "meta": job.meta,
        }
    except Exception as e:
        logger.warning(f"Failed to fetch job {job_id}: {e}")
        return None


def cancel_job(job_id: str) -> bool:
    """
    Cancel a queued job.

    Args:
        job_id: The job ID to cancel

    Returns:
        True if job was canceled, False otherwise
    """
    try:
        job = Job.fetch(job_id, connection=get_redis_connection())
        job.cancel()
        logger.info(f"Canceled job {job_id}")
        return True
    except Exception as e:
        logger.warning(f"Failed to cancel job {job_id}: {e}")
        return False


# ============================================================================
# Queue Monitoring
# ============================================================================

def get_queue_stats(queue_name: str = "default") -> Dict[str, Any]:
    """
    Get statistics for a queue.

    Args:
        queue_name: Name of the queue

    Returns:
        Dict with queue statistics
    """
    queue = get_job_queue(queue_name)

    return {
        "name": queue_name,
        "queued_jobs": len(queue),
        "started_jobs": queue.started_job_registry.count,
        "finished_jobs": queue.finished_job_registry.count,
        "failed_jobs": queue.failed_job_registry.count,
        "deferred_jobs": queue.deferred_job_registry.count,
        "scheduled_jobs": queue.scheduled_job_registry.count,
    }


def get_all_queue_stats() -> Dict[str, Dict[str, Any]]:
    """
    Get statistics for all queues.

    Returns:
        Dict mapping queue names to their statistics
    """
    queue_names = ["default", "high", "low"]
    return {name: get_queue_stats(name) for name in queue_names}


# ============================================================================
# Worker Management (for documentation purposes)
# ============================================================================

def start_worker_info() -> str:
    """
    Get information on how to start RQ workers.

    Returns:
        Instructions for starting workers
    """
    return """
    To start RQ workers for processing background jobs:

    # Start a worker for the default queue
    rq worker default --url redis://localhost:6379/1

    # Start a worker for high-priority jobs
    rq worker high --url redis://localhost:6379/1

    # Start a worker for low-priority jobs
    rq worker low --url redis://localhost:6379/1

    # Start a worker for multiple queues (processes high priority first)
    rq worker high default low --url redis://localhost:6379/1

    # With logging
    rq worker default --url redis://localhost:6379/1 --verbose

    # In production (with systemd, supervisor, or Docker)
    # See: https://python-rq.org/docs/workers/
    """


# ============================================================================
# Cleanup utilities
# ============================================================================

def cleanup_old_jobs(days: int = 7) -> int:
    """
    Clean up completed jobs older than specified days.

    Args:
        days: Age threshold in days (default: 7)

    Returns:
        Number of jobs cleaned up
    """
    from datetime import datetime, timedelta, timezone

    cutoff = datetime.now(timezone.utc) - timedelta(days=days)
    # Also keep a naive version for comparison with naive datetimes
    cutoff_naive = cutoff.replace(tzinfo=None)
    cleaned = 0

    for queue_name in ["default", "high", "low"]:
        queue = get_job_queue(queue_name)

        # Clean finished jobs
        for job_id in queue.finished_job_registry.get_job_ids():
            try:
                job = Job.fetch(job_id, connection=get_redis_connection())
                if job.ended_at:
                    # Handle both timezone-aware and naive datetimes
                    ended_at = job.ended_at
                    if ended_at.tzinfo is None:
                        is_old = ended_at < cutoff_naive
                    else:
                        is_old = ended_at < cutoff
                    if is_old:
                        job.delete()
                        cleaned += 1
            except Exception as e:
                logger.warning(f"Failed to clean job {job_id}: {e}")

    logger.info(f"Cleaned up {cleaned} old jobs")
    return cleaned
