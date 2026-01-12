"""
Query optimization utilities for VoltTracker.

Provides helpers for:
- Eager loading relationships to avoid N+1 queries
- Optimized query builders
- Common query patterns
"""

import logging
from typing import List, Optional, Any
from sqlalchemy.orm import Query, joinedload, subqueryload, selectinload
from sqlalchemy import and_, or_

logger = logging.getLogger(__name__)


def eager_load_trip_relationships(query: Query) -> Query:
    """
    Add eager loading for Trip relationships to avoid N+1 queries.

    Use this when you'll be accessing trip relationships (soc_transitions, etc.)
    in a loop. This loads all relationships in a single query.

    Args:
        query: Base query for Trip model

    Returns:
        Query with eager loading configured

    Example:
        >>> from models import Trip
        >>> query = db.query(Trip)
        >>> query = eager_load_trip_relationships(query)
        >>> trips = query.all()
        >>> for trip in trips:
        >>>     # No N+1 query here!
        >>>     transitions = trip.soc_transitions
    """
    return query.options(
        selectinload('soc_transitions')  # Load in separate query (best for one-to-many)
    )


def eager_load_charging_session_relationships(query: Query) -> Query:
    """
    Add eager loading for ChargingSession relationships.

    Args:
        query: Base query for ChargingSession model

    Returns:
        Query with eager loading configured
    """
    # Add eager loading if ChargingSession has relationships
    return query


def optimize_trip_list_query(query: Query, include_relationships: bool = False) -> Query:
    """
    Optimize a trip list query with common patterns.

    Args:
        query: Base Trip query
        include_relationships: If True, eagerly load relationships

    Returns:
        Optimized query

    Example:
        >>> query = db.query(Trip).filter(Trip.is_closed.is_(True))
        >>> query = optimize_trip_list_query(query)
        >>> trips = query.all()  # Optimized execution
    """
    if include_relationships:
        query = eager_load_trip_relationships(query)

    return query


class TripQueryBuilder:
    """
    Fluent query builder for Trip filtering.

    Provides a clean API for building complex trip queries with proper optimization.

    Example:
        >>> builder = TripQueryBuilder(db)
        >>> trips = (builder
        >>>     .closed_only()
        >>>     .date_range(start_date, end_date)
        >>>     .gas_mode()
        >>>     .min_distance(10.0)
        >>>     .with_relationships()
        >>>     .order_by_start_time(desc=True)
        >>>     .paginate(page=1, per_page=50))
    """

    def __init__(self, db_session):
        """
        Initialize query builder.

        Args:
            db_session: SQLAlchemy database session
        """
        from models import Trip
        self.db = db_session
        self.query = db_session.query(Trip)
        self._include_relationships = False

    def closed_only(self):
        """Filter for closed trips only."""
        from models import Trip
        self.query = self.query.filter(Trip.is_closed.is_(True))
        return self

    def active_only(self):
        """Filter for active (not closed) trips only."""
        from models import Trip
        self.query = self.query.filter(Trip.is_closed.is_(False))
        return self

    def not_deleted(self):
        """Exclude soft-deleted trips."""
        from models import Trip
        self.query = self.query.filter(Trip.deleted_at.is_(None))
        return self

    def date_range(self, start_date=None, end_date=None):
        """
        Filter by date range.

        Args:
            start_date: Start datetime (optional)
            end_date: End datetime (optional)
        """
        from models import Trip
        if start_date:
            self.query = self.query.filter(Trip.start_time >= start_date)
        if end_date:
            self.query = self.query.filter(Trip.start_time <= end_date)
        return self

    def gas_mode(self, enabled: bool = True):
        """
        Filter by gas mode usage.

        Args:
            enabled: If True, only gas trips; if False, only EV trips
        """
        from models import Trip
        self.query = self.query.filter(Trip.gas_mode_entered.is_(enabled))
        return self

    def min_distance(self, miles: float):
        """Filter for trips with minimum distance."""
        from models import Trip
        self.query = self.query.filter(Trip.distance_miles >= miles)
        return self

    def max_distance(self, miles: float):
        """Filter for trips with maximum distance."""
        from models import Trip
        self.query = self.query.filter(Trip.distance_miles <= miles)
        return self

    def min_efficiency(self, kwh_per_mile: float):
        """Filter for trips with minimum efficiency (kWh/mile)."""
        from models import Trip
        self.query = self.query.filter(Trip.kwh_per_mile >= kwh_per_mile)
        return self

    def temperature_range(self, min_temp: Optional[float] = None, max_temp: Optional[float] = None):
        """
        Filter by ambient temperature range.

        Args:
            min_temp: Minimum temperature in Fahrenheit (optional)
            max_temp: Maximum temperature in Fahrenheit (optional)
        """
        from models import Trip
        if min_temp is not None:
            self.query = self.query.filter(Trip.ambient_temp_avg_f >= min_temp)
        if max_temp is not None:
            self.query = self.query.filter(Trip.ambient_temp_avg_f <= max_temp)
        return self

    def extreme_weather(self):
        """Filter for trips with extreme weather conditions."""
        from models import Trip
        self.query = self.query.filter(Trip.extreme_weather.is_(True))
        return self

    def with_relationships(self):
        """Enable eager loading of relationships (prevents N+1 queries)."""
        self._include_relationships = True
        return self

    def order_by_start_time(self, desc: bool = True):
        """
        Order by start time.

        Args:
            desc: If True, descending order (newest first); if False, ascending
        """
        from models import Trip
        from sqlalchemy import desc as sql_desc
        if desc:
            self.query = self.query.order_by(sql_desc(Trip.start_time))
        else:
            self.query = self.query.order_by(Trip.start_time)
        return self

    def order_by_distance(self, desc: bool = True):
        """Order by distance."""
        from models import Trip
        from sqlalchemy import desc as sql_desc
        if desc:
            self.query = self.query.order_by(sql_desc(Trip.distance_miles))
        else:
            self.query = self.query.order_by(Trip.distance_miles)
        return self

    def build(self) -> Query:
        """
        Build the final query with optimizations.

        Returns:
            Optimized SQLAlchemy Query object
        """
        if self._include_relationships:
            self.query = eager_load_trip_relationships(self.query)
        return self.query

    def all(self) -> List[Any]:
        """Execute query and return all results."""
        return self.build().all()

    def first(self) -> Optional[Any]:
        """Execute query and return first result."""
        return self.build().first()

    def count(self) -> int:
        """Return count of matching records."""
        return self.query.count()

    def paginate(self, page: int = 1, per_page: int = 50):
        """
        Execute query with pagination.

        Args:
            page: Page number (1-indexed)
            per_page: Results per page

        Returns:
            List of results for the requested page
        """
        offset = (page - 1) * per_page
        return self.build().offset(offset).limit(per_page).all()


def batch_load_relationships(items: List[Any], relationship_name: str) -> List[Any]:
    """
    Batch load a relationship for a list of ORM objects.

    Useful when you have a list of objects and need to load a relationship
    for all of them without N+1 queries.

    Args:
        items: List of ORM objects
        relationship_name: Name of the relationship to load

    Returns:
        The same list of items (relationships are now loaded)

    Example:
        >>> trips = db.query(Trip).all()
        >>> trips = batch_load_relationships(trips, 'soc_transitions')
        >>> for trip in trips:
        >>>     # No N+1 query!
        >>>     print(trip.soc_transitions)
    """
    if not items:
        return items

    # Get the session from the first item
    from sqlalchemy.orm import object_session
    session = object_session(items[0])
    if not session:
        logger.warning(f"No session attached to items, cannot batch load {relationship_name}")
        return items

    # Use selectinload to batch fetch the relationship
    from sqlalchemy.orm import selectinload
    ids = [item.id for item in items]

    # Get the model class from the first item
    model_class = type(items[0])

    # Re-query with eager loading
    loaded_items = (
        session.query(model_class)
        .filter(model_class.id.in_(ids))
        .options(selectinload(relationship_name))
        .all()
    )

    return loaded_items
