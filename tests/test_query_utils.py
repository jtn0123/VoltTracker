"""
Tests for query utilities.

Tests query optimization helpers including:
- Eager loading relationships
- TripQueryBuilder fluent API
- Query optimization patterns
- Batch relationship loading
"""

import os
import sys
import uuid
from datetime import datetime, timedelta, timezone

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "receiver"))

from models import Base, Trip  # noqa: E402
from utils.query_utils import (  # noqa: E402
    TripQueryBuilder,
    batch_load_relationships,
    eager_load_charging_session_relationships,
    eager_load_trip_relationships,
    optimize_trip_list_query,
)


@pytest.fixture
def db_session():
    """Create an in-memory SQLite database for testing."""
    from sqlalchemy import create_engine
    from sqlalchemy.orm import sessionmaker

    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine)
    session = Session()
    yield session
    session.close()


@pytest.fixture
def sample_trips(db_session):
    """Create sample trips for testing."""
    trips = []
    # Use naive datetime to match database storage
    now = datetime.now()

    trip_data = [
        {
            "start_time": now - timedelta(days=5),
            "is_closed": True,
            "deleted_at": None,
            "distance_miles": 25.5,
            "kwh_per_mile": 0.30,
            "gas_mode_entered": False,
            "ambient_temp_avg_f": 68.0,
            "extreme_weather": False,
        },
        {
            "start_time": now - timedelta(days=4),
            "is_closed": True,
            "deleted_at": None,
            "distance_miles": 15.2,
            "kwh_per_mile": 0.35,
            "gas_mode_entered": True,
            "ambient_temp_avg_f": 45.0,
            "extreme_weather": False,
        },
        {
            "start_time": now - timedelta(days=3),
            "is_closed": False,  # Active trip
            "deleted_at": None,
            "distance_miles": 10.0,
            "kwh_per_mile": 0.28,
            "gas_mode_entered": False,
            "ambient_temp_avg_f": 72.0,
            "extreme_weather": False,
        },
        {
            "start_time": now - timedelta(days=2),
            "is_closed": True,
            "deleted_at": now - timedelta(days=1),  # Soft deleted
            "distance_miles": 30.0,
            "kwh_per_mile": 0.32,
            "gas_mode_entered": False,
            "ambient_temp_avg_f": 55.0,
            "extreme_weather": False,
        },
        {
            "start_time": now - timedelta(days=1),
            "is_closed": True,
            "deleted_at": None,
            "distance_miles": 5.5,
            "kwh_per_mile": 0.40,
            "gas_mode_entered": False,
            "ambient_temp_avg_f": 20.0,
            "extreme_weather": True,
        },
    ]

    for data in trip_data:
        trip = Trip(
            session_id=uuid.uuid4(),
            start_time=data["start_time"],
            end_time=data["start_time"] + timedelta(hours=1),
            is_closed=data["is_closed"],
            deleted_at=data["deleted_at"],
            distance_miles=data["distance_miles"],
            kwh_per_mile=data["kwh_per_mile"],
            gas_mode_entered=data["gas_mode_entered"],
            ambient_temp_avg_f=data["ambient_temp_avg_f"],
            extreme_weather=data["extreme_weather"],
        )
        trips.append(trip)
        db_session.add(trip)

    db_session.commit()
    return trips


class TestEagerLoadFunctions:
    """Tests for eager loading helper functions."""

    def test_eager_load_trip_relationships(self, db_session, sample_trips):
        """Eager load trip relationships adds options to query."""
        query = db_session.query(Trip)
        optimized_query = eager_load_trip_relationships(query)

        # Query should still be executable
        results = optimized_query.all()
        assert len(results) == 5

    def test_eager_load_charging_session_relationships(self, db_session):
        """Eager load charging session relationships returns query."""
        from models import ChargingSession

        query = db_session.query(ChargingSession)
        optimized_query = eager_load_charging_session_relationships(query)

        # Should return a valid query object
        assert optimized_query is not None

    def test_optimize_trip_list_query_without_relationships(self, db_session, sample_trips):
        """Optimize trip list query without relationships."""
        query = db_session.query(Trip)
        optimized = optimize_trip_list_query(query, include_relationships=False)

        results = optimized.all()
        assert len(results) == 5

    def test_optimize_trip_list_query_with_relationships(self, db_session, sample_trips):
        """Optimize trip list query with relationships."""
        query = db_session.query(Trip)
        optimized = optimize_trip_list_query(query, include_relationships=True)

        results = optimized.all()
        assert len(results) == 5


class TestTripQueryBuilder:
    """Tests for TripQueryBuilder fluent API."""

    def test_builder_initialization(self, db_session):
        """Builder initializes with database session."""
        builder = TripQueryBuilder(db_session)
        assert builder.db == db_session
        assert builder.query is not None

    def test_closed_only_filter(self, db_session, sample_trips):
        """closed_only filters for closed trips."""
        builder = TripQueryBuilder(db_session)
        trips = builder.closed_only().all()

        # Should get 4 closed trips (excluding the active one)
        assert len(trips) == 4
        assert all(trip.is_closed for trip in trips)

    def test_active_only_filter(self, db_session, sample_trips):
        """active_only filters for active trips."""
        builder = TripQueryBuilder(db_session)
        trips = builder.active_only().all()

        # Should get 1 active trip
        assert len(trips) == 1
        assert not trips[0].is_closed

    def test_not_deleted_filter(self, db_session, sample_trips):
        """not_deleted excludes soft-deleted trips."""
        builder = TripQueryBuilder(db_session)
        trips = builder.not_deleted().all()

        # Should exclude the 1 soft-deleted trip
        assert len(trips) == 4
        assert all(trip.deleted_at is None for trip in trips)

    def test_date_range_with_start_date(self, db_session, sample_trips):
        """date_range with start_date filters correctly."""
        now = datetime.now()
        start = now - timedelta(days=3)

        builder = TripQueryBuilder(db_session)
        trips = builder.date_range(start_date=start).all()

        # Should get trips from last 3 days
        assert len(trips) <= 5
        assert all(trip.start_time >= start for trip in trips)

    def test_date_range_with_end_date(self, db_session, sample_trips):
        """date_range with end_date filters correctly."""
        now = datetime.now()
        end = now - timedelta(days=3)

        builder = TripQueryBuilder(db_session)
        trips = builder.date_range(end_date=end).all()

        assert all(trip.start_time <= end for trip in trips)

    def test_date_range_with_both_dates(self, db_session, sample_trips):
        """date_range with both start and end dates."""
        now = datetime.now()
        start = now - timedelta(days=5)
        end = now - timedelta(days=2)

        builder = TripQueryBuilder(db_session)
        trips = builder.date_range(start_date=start, end_date=end).all()

        assert all(start <= trip.start_time <= end for trip in trips)

    def test_gas_mode_enabled(self, db_session, sample_trips):
        """gas_mode filters for gas trips."""
        builder = TripQueryBuilder(db_session)
        trips = builder.gas_mode(enabled=True).all()

        # Should get 1 gas trip
        assert len(trips) == 1
        assert trips[0].gas_mode_entered

    def test_gas_mode_disabled(self, db_session, sample_trips):
        """gas_mode with enabled=False filters for EV trips."""
        builder = TripQueryBuilder(db_session)
        trips = builder.gas_mode(enabled=False).all()

        # Should get EV-only trips
        assert all(not trip.gas_mode_entered for trip in trips)

    def test_min_distance_filter(self, db_session, sample_trips):
        """min_distance filters correctly."""
        builder = TripQueryBuilder(db_session)
        trips = builder.min_distance(15.0).all()

        # Should only get trips >= 15 miles
        assert all(trip.distance_miles >= 15.0 for trip in trips)

    def test_max_distance_filter(self, db_session, sample_trips):
        """max_distance filters correctly."""
        builder = TripQueryBuilder(db_session)
        trips = builder.max_distance(20.0).all()

        # Should only get trips <= 20 miles
        assert all(trip.distance_miles <= 20.0 for trip in trips)

    def test_min_efficiency_filter(self, db_session, sample_trips):
        """min_efficiency filters correctly."""
        builder = TripQueryBuilder(db_session)
        trips = builder.min_efficiency(0.32).all()

        # Should only get trips with kwh_per_mile >= 0.32
        assert all(trip.kwh_per_mile >= 0.32 for trip in trips)

    def test_temperature_range_with_min(self, db_session, sample_trips):
        """temperature_range with min_temp filters correctly."""
        builder = TripQueryBuilder(db_session)
        trips = builder.temperature_range(min_temp=50.0).all()

        assert all(trip.ambient_temp_avg_f >= 50.0 for trip in trips)

    def test_temperature_range_with_max(self, db_session, sample_trips):
        """temperature_range with max_temp filters correctly."""
        builder = TripQueryBuilder(db_session)
        trips = builder.temperature_range(max_temp=60.0).all()

        assert all(trip.ambient_temp_avg_f <= 60.0 for trip in trips)

    def test_temperature_range_with_both(self, db_session, sample_trips):
        """temperature_range with both min and max."""
        builder = TripQueryBuilder(db_session)
        trips = builder.temperature_range(min_temp=40.0, max_temp=70.0).all()

        assert all(40.0 <= trip.ambient_temp_avg_f <= 70.0 for trip in trips)

    def test_extreme_weather_filter(self, db_session, sample_trips):
        """extreme_weather filters for extreme conditions."""
        builder = TripQueryBuilder(db_session)
        trips = builder.extreme_weather().all()

        # Should get 1 extreme weather trip
        assert len(trips) == 1
        assert trips[0].extreme_weather

    def test_with_relationships(self, db_session, sample_trips):
        """with_relationships enables eager loading."""
        builder = TripQueryBuilder(db_session)
        builder.with_relationships()

        assert builder._include_relationships is True

    def test_order_by_start_time_desc(self, db_session, sample_trips):
        """order_by_start_time descending."""
        builder = TripQueryBuilder(db_session)
        trips = builder.order_by_start_time(desc=True).all()

        # Should be newest first
        for i in range(len(trips) - 1):
            assert trips[i].start_time >= trips[i + 1].start_time

    def test_order_by_start_time_asc(self, db_session, sample_trips):
        """order_by_start_time ascending."""
        builder = TripQueryBuilder(db_session)
        trips = builder.order_by_start_time(desc=False).all()

        # Should be oldest first
        for i in range(len(trips) - 1):
            assert trips[i].start_time <= trips[i + 1].start_time

    def test_order_by_distance_desc(self, db_session, sample_trips):
        """order_by_distance descending."""
        builder = TripQueryBuilder(db_session)
        trips = builder.order_by_distance(desc=True).all()

        # Should be longest first
        for i in range(len(trips) - 1):
            assert trips[i].distance_miles >= trips[i + 1].distance_miles

    def test_order_by_distance_asc(self, db_session, sample_trips):
        """order_by_distance ascending."""
        builder = TripQueryBuilder(db_session)
        trips = builder.order_by_distance(desc=False).all()

        # Should be shortest first
        for i in range(len(trips) - 1):
            assert trips[i].distance_miles <= trips[i + 1].distance_miles

    def test_build_method(self, db_session, sample_trips):
        """build returns query object."""
        builder = TripQueryBuilder(db_session)
        query = builder.closed_only().build()

        # Should return a query object
        assert query is not None
        results = query.all()
        assert len(results) == 4

    def test_build_with_relationships(self, db_session, sample_trips):
        """build with relationships enables eager loading."""
        builder = TripQueryBuilder(db_session)
        query = builder.with_relationships().build()

        results = query.all()
        assert len(results) == 5

    def test_all_method(self, db_session, sample_trips):
        """all method executes query and returns results."""
        builder = TripQueryBuilder(db_session)
        trips = builder.closed_only().all()

        assert isinstance(trips, list)
        assert len(trips) == 4

    def test_first_method(self, db_session, sample_trips):
        """first method returns first result."""
        builder = TripQueryBuilder(db_session)
        trip = builder.order_by_start_time(desc=True).first()

        assert trip is not None
        assert isinstance(trip, Trip)

    def test_first_method_empty_result(self, db_session):
        """first method returns None for empty result."""
        builder = TripQueryBuilder(db_session)
        trip = builder.min_distance(1000.0).first()

        assert trip is None

    def test_count_method(self, db_session, sample_trips):
        """count method returns count of matching records."""
        builder = TripQueryBuilder(db_session)
        count = builder.closed_only().count()

        assert count == 4

    def test_paginate_first_page(self, db_session, sample_trips):
        """paginate returns correct page of results."""
        builder = TripQueryBuilder(db_session)
        trips = builder.order_by_start_time().paginate(page=1, per_page=2)

        assert len(trips) == 2

    def test_paginate_second_page(self, db_session, sample_trips):
        """paginate returns correct second page."""
        builder = TripQueryBuilder(db_session)
        trips = builder.order_by_start_time().paginate(page=2, per_page=2)

        assert len(trips) == 2

    def test_paginate_last_page(self, db_session, sample_trips):
        """paginate handles last page with fewer items."""
        builder = TripQueryBuilder(db_session)
        trips = builder.order_by_start_time().paginate(page=3, per_page=2)

        # Should have 1 trip on page 3 (5 total trips, 2 per page)
        assert len(trips) == 1

    def test_chained_filters(self, db_session, sample_trips):
        """Multiple filters can be chained together."""
        builder = TripQueryBuilder(db_session)
        trips = (
            builder.closed_only()
            .not_deleted()
            .min_distance(10.0)
            .order_by_start_time(desc=True)
            .all()
        )

        # Should get closed, not deleted, >= 10 miles
        assert all(trip.is_closed for trip in trips)
        assert all(trip.deleted_at is None for trip in trips)
        assert all(trip.distance_miles >= 10.0 for trip in trips)

    def test_complex_query_chain(self, db_session, sample_trips):
        """Complex query with multiple filters and ordering."""
        now = datetime.now()
        start = now - timedelta(days=5)

        builder = TripQueryBuilder(db_session)
        trips = (
            builder.closed_only()
            .not_deleted()
            .date_range(start_date=start)
            .gas_mode(enabled=False)
            .min_distance(5.0)
            .temperature_range(min_temp=60.0)
            .with_relationships()
            .order_by_distance(desc=True)
            .paginate(page=1, per_page=10)
        )

        # All filters should be applied
        assert all(trip.is_closed for trip in trips)
        assert all(trip.deleted_at is None for trip in trips)
        assert all(trip.start_time >= start for trip in trips)
        assert all(not trip.gas_mode_entered for trip in trips)
        assert all(trip.distance_miles >= 5.0 for trip in trips)
        assert all(trip.ambient_temp_avg_f >= 60.0 for trip in trips)


class TestBatchLoadRelationships:
    """Tests for batch_load_relationships function."""

    def test_batch_load_empty_list(self):
        """batch_load_relationships handles empty list."""
        result = batch_load_relationships([], "soc_transitions")
        assert result == []

    def test_batch_load_with_items(self, db_session, sample_trips):
        """batch_load_relationships loads relationships."""
        # Get trips from database
        trips = db_session.query(Trip).limit(3).all()

        # Batch load relationships
        loaded_trips = batch_load_relationships(trips, "soc_transitions")

        # Should return same number of items
        assert len(loaded_trips) == 3

    def test_batch_load_returns_list(self, db_session, sample_trips):
        """batch_load_relationships returns list of items."""
        trips = db_session.query(Trip).limit(2).all()
        loaded_trips = batch_load_relationships(trips, "soc_transitions")

        assert isinstance(loaded_trips, list)
        assert all(isinstance(trip, Trip) for trip in loaded_trips)
