"""
Database session management for VoltTracker.

Provides the database engine and session factory that can be imported
by blueprints without circular dependencies.
"""

from config import Config
from flask import g
from models import get_engine
from sqlalchemy.orm import scoped_session, sessionmaker

# Create engine and session factory
engine = get_engine(Config.DATABASE_URL)
SessionLocal = scoped_session(sessionmaker(bind=engine))


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
