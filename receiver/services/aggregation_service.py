"""
Aggregation Service (D32)

Populates TripDailyStats, ChargingHourlyStats, and MonthlySummary tables
from raw trip and charging data. Registered with APScheduler to run daily.
"""

import logging
from datetime import date, datetime, timedelta, timezone

from database import SessionLocal
from models import (
    ChargingHourlyStats,
    ChargingSession,
    MonthlySummary,
    Trip,
    TripDailyStats,
)
from sqlalchemy.exc import IntegrityError, OperationalError

logger = logging.getLogger(__name__)


def aggregate_daily_stats(target_date: date | None = None) -> None:
    """Aggregate trip statistics for a given date (default: yesterday)."""
    db = SessionLocal()
    try:
        if target_date is None:
            target_date = (datetime.now(timezone.utc) - timedelta(days=1)).date()

        start_dt = datetime.combine(target_date, datetime.min.time()).replace(tzinfo=timezone.utc)
        end_dt = start_dt + timedelta(days=1)

        trips = (
            db.query(Trip)
            .filter(
                Trip.is_closed.is_(True),
                Trip.deleted_at.is_(None),
                Trip.start_time >= start_dt,
                Trip.start_time < end_dt,
            )
            .all()
        )

        if not trips:
            logger.debug(f"No trips found for {target_date}, skipping daily aggregation")
            return

        distances = [t.distance_miles for t in trips if t.distance_miles]
        efficiencies = [t.kwh_per_mile for t in trips if t.kwh_per_mile and t.kwh_per_mile > 0]
        mpgs = [t.gas_mpg for t in trips if t.gas_mpg and t.gas_mpg > 0]
        temps = [t.weather_temp_f for t in trips if t.weather_temp_f is not None]

        stats = db.query(TripDailyStats).filter(TripDailyStats.date == target_date).first()
        if not stats:
            stats = TripDailyStats(date=target_date)
            db.add(stats)

        stats.total_trips = len(trips)
        stats.ev_only_trips = sum(1 for t in trips if not t.gas_mode_entered)
        stats.gas_mode_trips = sum(1 for t in trips if t.gas_mode_entered)
        stats.extreme_weather_trips = sum(1 for t in trips if t.extreme_weather)

        stats.total_distance_miles = sum(distances) if distances else 0
        stats.total_electric_miles = sum(t.electric_miles or 0 for t in trips)
        stats.total_gas_miles = sum(t.gas_miles or 0 for t in trips)
        stats.avg_trip_distance = (sum(distances) / len(distances)) if distances else None

        if efficiencies:
            stats.avg_kwh_per_mile = sum(efficiencies) / len(efficiencies)
            stats.best_kwh_per_mile = min(efficiencies)
            stats.worst_kwh_per_mile = max(efficiencies)
        if mpgs:
            stats.avg_mpg = sum(mpgs) / len(mpgs)
        if temps:
            stats.avg_temp_f = sum(temps) / len(temps)
            stats.min_temp_f = min(temps)
            stats.max_temp_f = max(temps)

        stats.total_kwh_used = sum(t.electric_kwh_used or 0 for t in trips)

        db.commit()
        logger.info(f"Aggregated daily stats for {target_date}: {len(trips)} trips")

    except (IntegrityError, OperationalError) as e:
        db.rollback()
        logger.error(f"Failed to aggregate daily stats for {target_date}: {e}")
    except Exception as e:
        db.rollback()
        logger.exception(f"Unexpected error aggregating daily stats: {e}")
    finally:
        SessionLocal.remove()


def aggregate_charging_hourly(target_date: date | None = None) -> None:
    """Aggregate charging statistics by hour for a given date (default: yesterday)."""
    db = SessionLocal()
    try:
        if target_date is None:
            target_date = (datetime.now(timezone.utc) - timedelta(days=1)).date()

        start_dt = datetime.combine(target_date, datetime.min.time()).replace(tzinfo=timezone.utc)
        end_dt = start_dt + timedelta(days=1)

        sessions = (
            db.query(ChargingSession)
            .filter(
                ChargingSession.start_time >= start_dt,
                ChargingSession.start_time < end_dt,
            )
            .all()
        )

        if not sessions:
            return

        # Group by hour
        hourly: dict[datetime, list[ChargingSession]] = {}
        for s in sessions:
            hour = s.start_time.replace(minute=0, second=0, microsecond=0)
            hourly.setdefault(hour, []).append(s)

        for hour_ts, hour_sessions in hourly.items():
            stat = db.query(ChargingHourlyStats).filter(
                ChargingHourlyStats.hour_timestamp == hour_ts
            ).first()
            if not stat:
                stat = ChargingHourlyStats(hour_timestamp=hour_ts)
                db.add(stat)

            stat.total_sessions = len(hour_sessions)
            stat.completed_sessions = sum(1 for s in hour_sessions if s.is_complete)
            stat.l1_sessions = sum(1 for s in hour_sessions if s.charge_type == 'L1')
            stat.l2_sessions = sum(1 for s in hour_sessions if s.charge_type == 'L2')
            stat.dcfc_sessions = sum(1 for s in hour_sessions if s.charge_type == 'DCFC')
            kwh_values = [s.kwh_added for s in hour_sessions if s.kwh_added]
            stat.total_kwh_added = sum(kwh_values) if kwh_values else 0
            stat.avg_kwh_per_session = (sum(kwh_values) / len(kwh_values)) if kwh_values else None

        db.commit()
        logger.info(f"Aggregated charging hourly stats for {target_date}")

    except (IntegrityError, OperationalError) as e:
        db.rollback()
        logger.error(f"Failed to aggregate charging stats: {e}")
    except Exception as e:
        db.rollback()
        logger.exception(f"Unexpected error aggregating charging stats: {e}")
    finally:
        SessionLocal.remove()


def aggregate_monthly_summary(year: int | None = None, month: int | None = None) -> None:
    """Aggregate monthly summary for a given month (default: previous month)."""
    db = SessionLocal()
    try:
        if year is None or month is None:
            last_month = datetime.now(timezone.utc).replace(day=1) - timedelta(days=1)
            year = last_month.year
            month = last_month.month

        start_dt = datetime(year, month, 1, tzinfo=timezone.utc)
        if month == 12:
            end_dt = datetime(year + 1, 1, 1, tzinfo=timezone.utc)
        else:
            end_dt = datetime(year, month + 1, 1, tzinfo=timezone.utc)

        trips = (
            db.query(Trip)
            .filter(
                Trip.is_closed.is_(True),
                Trip.deleted_at.is_(None),
                Trip.start_time >= start_dt,
                Trip.start_time < end_dt,
            )
            .all()
        )

        summary = db.query(MonthlySummary).filter(
            MonthlySummary.year == year, MonthlySummary.month == month
        ).first()
        if not summary:
            summary = MonthlySummary(year=year, month=month)
            db.add(summary)

        summary.total_trips = len(trips)
        summary.total_distance_miles = sum(t.distance_miles or 0 for t in trips)
        summary.total_electric_miles = sum(t.electric_miles or 0 for t in trips)
        summary.total_gas_miles = sum(t.gas_miles or 0 for t in trips)
        if summary.total_distance_miles and summary.total_distance_miles > 0:
            summary.electric_percentage = (summary.total_electric_miles / summary.total_distance_miles) * 100

        efficiencies = [t.kwh_per_mile for t in trips if t.kwh_per_mile and t.kwh_per_mile > 0]
        if efficiencies:
            summary.avg_kwh_per_mile = sum(efficiencies) / len(efficiencies)
        mpgs = [t.gas_mpg for t in trips if t.gas_mpg and t.gas_mpg > 0]
        if mpgs:
            summary.avg_mpg = sum(mpgs) / len(mpgs)

        summary.total_kwh_used = sum(t.electric_kwh_used or 0 for t in trips)
        summary.total_gallons_used = sum(t.fuel_used_gallons or 0 for t in trips)

        # Charging sessions for this month
        charging = (
            db.query(ChargingSession)
            .filter(ChargingSession.start_time >= start_dt, ChargingSession.start_time < end_dt)
            .all()
        )
        summary.total_charging_sessions = len(charging)
        summary.total_kwh_charged = sum(c.kwh_added or 0 for c in charging)

        db.commit()
        logger.info(f"Aggregated monthly summary for {year}-{month:02d}: {len(trips)} trips")

    except (IntegrityError, OperationalError) as e:
        db.rollback()
        logger.error(f"Failed to aggregate monthly summary: {e}")
    except Exception as e:
        db.rollback()
        logger.exception(f"Unexpected error aggregating monthly summary: {e}")
    finally:
        SessionLocal.remove()


def run_all_aggregations() -> None:
    """Run all aggregation jobs. Called by APScheduler daily at midnight."""
    logger.info("Running daily aggregation jobs...")
    aggregate_daily_stats()
    aggregate_charging_hourly()
    aggregate_monthly_summary()
    logger.info("Daily aggregation jobs complete.")
