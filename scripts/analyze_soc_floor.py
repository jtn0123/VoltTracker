#!/usr/bin/env python3
"""
SOC Floor Analysis Utility

Standalone script to analyze battery State of Charge (SOC) at gas engine
activation to track potential battery degradation over time.

Usage:
    python analyze_soc_floor.py --db postgresql://user:pass@host:5432/volt_tracker
    python analyze_soc_floor.py --csv transitions.csv
"""

import argparse
import statistics
from datetime import datetime
from typing import List, Optional
import json


def load_from_database(db_url: str) -> List[dict]:
    """Load SOC transitions from PostgreSQL database."""
    try:
        from sqlalchemy import create_engine, text
    except ImportError:
        print("Error: SQLAlchemy not installed. Run: pip install sqlalchemy psycopg2-binary")
        return []

    engine = create_engine(db_url)

    with engine.connect() as conn:
        result = conn.execute(text("""
            SELECT
                timestamp,
                soc_at_transition,
                ambient_temp_f,
                odometer_miles
            FROM soc_transitions
            ORDER BY timestamp
        """))

        return [dict(row._mapping) for row in result]


def load_from_csv(csv_path: str) -> List[dict]:
    """Load SOC transitions from CSV file."""
    import csv

    transitions = []
    with open(csv_path, 'r') as f:
        reader = csv.DictReader(f)
        for row in reader:
            transitions.append({
                'timestamp': datetime.fromisoformat(row['timestamp']),
                'soc_at_transition': float(row['soc_at_transition']),
                'ambient_temp_f': float(row['ambient_temp_f']) if row.get('ambient_temp_f') else None,
                'odometer_miles': float(row['odometer_miles']) if row.get('odometer_miles') else None,
            })

    return transitions


def analyze_soc_data(transitions: List[dict]) -> dict:
    """
    Analyze SOC transition data for patterns and trends.

    Returns detailed analysis including:
    - Basic statistics (mean, median, std dev)
    - Temperature correlation
    - Time-based trend analysis
    - Battery health indicators
    """
    if not transitions:
        return {'error': 'No data to analyze'}

    soc_values = [t['soc_at_transition'] for t in transitions if t.get('soc_at_transition')]

    if not soc_values:
        return {'error': 'No valid SOC values found'}

    # Basic statistics
    analysis = {
        'count': len(soc_values),
        'mean': round(statistics.mean(soc_values), 2),
        'median': round(statistics.median(soc_values), 2),
        'min': round(min(soc_values), 2),
        'max': round(max(soc_values), 2),
        'std_dev': round(statistics.stdev(soc_values), 2) if len(soc_values) > 1 else 0,
    }

    # Temperature correlation
    cold_socs = []
    cool_socs = []
    warm_socs = []
    hot_socs = []

    for t in transitions:
        soc = t.get('soc_at_transition')
        temp = t.get('ambient_temp_f')
        if soc and temp:
            if temp < 32:
                cold_socs.append(soc)
            elif temp < 50:
                cool_socs.append(soc)
            elif temp < 80:
                warm_socs.append(soc)
            else:
                hot_socs.append(soc)

    analysis['temperature_analysis'] = {
        'freezing_below_32f': {
            'count': len(cold_socs),
            'avg_soc': round(statistics.mean(cold_socs), 2) if cold_socs else None
        },
        'cold_32_to_50f': {
            'count': len(cool_socs),
            'avg_soc': round(statistics.mean(cool_socs), 2) if cool_socs else None
        },
        'moderate_50_to_80f': {
            'count': len(warm_socs),
            'avg_soc': round(statistics.mean(warm_socs), 2) if warm_socs else None
        },
        'hot_above_80f': {
            'count': len(hot_socs),
            'avg_soc': round(statistics.mean(hot_socs), 2) if hot_socs else None
        }
    }

    # Time-based trend analysis
    if len(transitions) >= 10:
        # Split into quarters
        quarter_size = len(transitions) // 4

        quarters = []
        for i in range(4):
            start = i * quarter_size
            end = (i + 1) * quarter_size if i < 3 else len(transitions)
            quarter_socs = [t['soc_at_transition'] for t in transitions[start:end] if t.get('soc_at_transition')]
            if quarter_socs:
                quarters.append({
                    'quarter': i + 1,
                    'count': len(quarter_socs),
                    'avg_soc': round(statistics.mean(quarter_socs), 2)
                })

        analysis['trend_by_quarter'] = quarters

        # Overall trend
        first_half = soc_values[:len(soc_values)//2]
        second_half = soc_values[len(soc_values)//2:]

        first_avg = statistics.mean(first_half)
        second_avg = statistics.mean(second_half)
        trend_change = second_avg - first_avg

        analysis['overall_trend'] = {
            'first_half_avg': round(first_avg, 2),
            'second_half_avg': round(second_avg, 2),
            'change': round(trend_change, 2),
            'direction': 'increasing' if trend_change > 0.5 else 'decreasing' if trend_change < -0.5 else 'stable',
            'interpretation': get_trend_interpretation(trend_change)
        }

    # Battery health assessment
    analysis['health_assessment'] = assess_battery_health(soc_values, analysis.get('overall_trend'))

    return analysis


def get_trend_interpretation(change: float) -> str:
    """Get human-readable interpretation of SOC floor trend."""
    if change > 2:
        return "SOC floor is rising significantly. This may indicate battery capacity degradation."
    elif change > 0.5:
        return "SOC floor is slightly rising. Monitor for continued increase."
    elif change < -2:
        return "SOC floor is dropping. This is unusual and may indicate sensor issues."
    elif change < -0.5:
        return "SOC floor is slightly dropping. Battery performance may be improving in warmer weather."
    else:
        return "SOC floor is stable. Battery is performing consistently."


def assess_battery_health(soc_values: List[float], trend: Optional[dict]) -> dict:
    """Assess overall battery health based on SOC floor patterns."""
    avg_soc = statistics.mean(soc_values)

    health = {
        'status': 'unknown',
        'notes': []
    }

    # Check average SOC floor
    if avg_soc < 15:
        health['status'] = 'excellent'
        health['notes'].append(f"Average SOC floor of {avg_soc:.1f}% indicates full battery utilization.")
    elif avg_soc < 18:
        health['status'] = 'good'
        health['notes'].append(f"Average SOC floor of {avg_soc:.1f}% is within normal range.")
    elif avg_soc < 22:
        health['status'] = 'fair'
        health['notes'].append(f"Average SOC floor of {avg_soc:.1f}% suggests some capacity reduction.")
    else:
        health['status'] = 'degraded'
        health['notes'].append(f"Average SOC floor of {avg_soc:.1f}% indicates significant capacity loss.")

    # Check trend
    if trend:
        if trend['direction'] == 'increasing' and trend['change'] > 2:
            health['notes'].append("Rising trend suggests ongoing degradation.")
            if health['status'] == 'excellent':
                health['status'] = 'good'
            elif health['status'] == 'good':
                health['status'] = 'fair'

    # Check variability
    if len(soc_values) > 5:
        std_dev = statistics.stdev(soc_values)
        if std_dev > 5:
            health['notes'].append(f"High variability (std dev: {std_dev:.1f}%) may indicate temperature sensitivity.")

    return health


def print_analysis(analysis: dict):
    """Print analysis results in a readable format."""
    print("\n" + "=" * 60)
    print("SOC FLOOR ANALYSIS REPORT")
    print("=" * 60)

    if 'error' in analysis:
        print(f"\nError: {analysis['error']}")
        return

    print(f"\nData Points: {analysis['count']}")
    print(f"\nBasic Statistics:")
    print(f"  Mean SOC:   {analysis['mean']}%")
    print(f"  Median SOC: {analysis['median']}%")
    print(f"  Min SOC:    {analysis['min']}%")
    print(f"  Max SOC:    {analysis['max']}%")
    print(f"  Std Dev:    {analysis['std_dev']}%")

    if 'temperature_analysis' in analysis:
        print(f"\nTemperature Analysis:")
        temp_data = analysis['temperature_analysis']
        for key, data in temp_data.items():
            if data['count'] > 0:
                label = key.replace('_', ' ').title()
                print(f"  {label}: {data['avg_soc']}% avg ({data['count']} readings)")

    if 'overall_trend' in analysis:
        trend = analysis['overall_trend']
        print(f"\nTrend Analysis:")
        print(f"  First Half Avg:  {trend['first_half_avg']}%")
        print(f"  Second Half Avg: {trend['second_half_avg']}%")
        print(f"  Change:          {trend['change']:+.2f}% ({trend['direction']})")
        print(f"  Interpretation:  {trend['interpretation']}")

    if 'health_assessment' in analysis:
        health = analysis['health_assessment']
        print(f"\nBattery Health Assessment:")
        print(f"  Status: {health['status'].upper()}")
        for note in health['notes']:
            print(f"  - {note}")

    print("\n" + "=" * 60)


def main():
    parser = argparse.ArgumentParser(description='Analyze Volt SOC floor data')
    parser.add_argument('--db', help='Database connection URL')
    parser.add_argument('--csv', help='CSV file path')
    parser.add_argument('--json', action='store_true', help='Output as JSON')

    args = parser.parse_args()

    if not args.db and not args.csv:
        # Default to database from environment
        import os
        db_url = os.environ.get('DATABASE_URL')
        if db_url:
            args.db = db_url
        else:
            parser.error('Either --db or --csv must be provided, or set DATABASE_URL environment variable')

    # Load data
    if args.db:
        transitions = load_from_database(args.db)
    else:
        transitions = load_from_csv(args.csv)

    if not transitions:
        print("No SOC transition data found.")
        return

    # Analyze
    analysis = analyze_soc_data(transitions)

    # Output
    if args.json:
        print(json.dumps(analysis, indent=2, default=str))
    else:
        print_analysis(analysis)


if __name__ == '__main__':
    main()
