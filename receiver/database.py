"""
Database session management for VoltTracker.

Provides the database engine and session factory that can be imported
by blueprints without circular dependencies.
"""

import logging
import time

from config import Config
from flask import g
from models import get_engine
from sqlalchemy import event
from sqlalchemy.engine import Engine
from sqlalchemy.orm import scoped_session, sessionmaker

logger = logging.getLogger(__name__)

# Create engine and session factory
engine = get_engine(Config.DATABASE_URL)
SessionLocal = scoped_session(sessionmaker(bind=engine))

# Add slow query logging (queries >500ms)
SLOW_QUERY_THRESHOLD_MS = 500


@event.listens_for(Engine, "before_cursor_execute")
def before_cursor_execute(conn, cursor, statement, parameters, context, executemany):
    """Record query start time."""
    conn.info.setdefault("query_start_time", []).append(time.time())


@event.listens_for(Engine, "after_cursor_execute")
def after_cursor_execute(conn, cursor, statement, parameters, context, executemany):
    """Log slow queries."""
    total_time = time.time() - conn.info["query_start_time"].pop(-1)
    duration_ms = total_time * 1000

    if duration_ms > SLOW_QUERY_THRESHOLD_MS:
        # Truncate long queries for logging
        truncated_query = statement[:200] + "..." if len(statement) > 200 else statement
        logger.warning(
            f"Slow query detected: {duration_ms:.2f}ms - {truncated_query}", extra={"duration_ms": duration_ms}
        )


def get_db():
    """
    Get database session for the current request.

    Uses Flask's application context to store the session,
    ensuring proper cleanup at the end of each request.
    """
    if "db" not in g:
        g.db = SessionLocal()
    return g.db


def close_db(exception=None):
    """
    Close database session at end of request.

    Call this in teardown_appcontext.
    """
    db = g.pop("db", None)
    if db is not None:
        SessionLocal.remove()


def init_app(app):
    """
    Initialize database with Flask app.

    Registers the teardown function to close sessions.
    """
    app.teardown_appcontext(close_db)
