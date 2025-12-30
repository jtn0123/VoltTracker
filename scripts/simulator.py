#!/usr/bin/env python3
"""
VoltTracker Data Simulator

Generates realistic Chevy Volt telemetry data and sends it to the server,
mimicking what Torque Pro would send during an actual drive.

Usage:
    python simulator.py                      # Default 30-minute trip
    python simulator.py --duration 60        # 60-minute trip
    python simulator.py --speed fast         # 10x speed for quick testing
    python simulator.py --gas-at-mile 15     # Force gas mode at mile 15
    python simulator.py --electric-only      # Never switch to gas mode
    python simulator.py --url http://ip:8080 # Custom server URL
"""

import argparse
import math
import random
import requests
import sys
import time
import uuid
from datetime import datetime, timezone
from typing import Optional


class VoltSimulator:
    """Simulates a Chevy Volt Gen 2 driving session."""

    # Volt constants
    TANK_CAPACITY = 9.3122  # gallons
    BATTERY_CAPACITY_KWH = 18.4  # usable kWh
    ELECTRIC_RANGE_MILES = 53  # EPA rated
    GAS_MPG = 42  # EPA combined

    def __init__(
        self,
        server_url: str = "http://localhost:8080",
        duration_minutes: int = 30,
        start_soc: float = 100.0,
        start_fuel: float = 80.0,
        speed_multiplier: float = 1.0,
        gas_at_mile: Optional[float] = None,
        electric_only: bool = False,
        ambient_temp: float = 70.0,
    ):
        self.server_url = server_url.rstrip('/')
        self.duration_minutes = duration_minutes
        self.start_soc = start_soc
        self.start_fuel = start_fuel
        self.speed_multiplier = speed_multiplier
        self.gas_at_mile = gas_at_mile
        self.electric_only = electric_only
        self.ambient_temp = ambient_temp

        # Session state
        self.session_id = str(uuid.uuid4())
        self.odometer = 50000.0 + random.uniform(0, 1000)  # Start around 50k miles
        self.current_soc = start_soc
        self.current_fuel = start_fuel
        self.current_speed = 0.0
        self.current_rpm = 0.0
        self.total_miles = 0.0
        self.in_gas_mode = False
        self.trip_start_time = None

        # GPS simulation (stationary for simplicity)
        self.latitude = 37.7749 + random.uniform(-0.01, 0.01)  # San Francisco area
        self.longitude = -122.4194 + random.uniform(-0.01, 0.01)

    def generate_telemetry(self, elapsed_seconds: float) -> dict:
        """Generate a single telemetry data point."""

        # Simulate speed pattern (accelerate, cruise, decelerate)
        trip_progress = elapsed_seconds / (self.duration_minutes * 60)
        base_speed = self._calculate_speed(trip_progress)
        self.current_speed = base_speed + random.uniform(-2, 2)
        self.current_speed = max(0, self.current_speed)

        # Calculate distance traveled since last update
        miles_per_second = self.current_speed / 3600
        self.total_miles += miles_per_second
        self.odometer += miles_per_second

        # SOC drain based on speed (higher speed = more drain)
        if not self.in_gas_mode and self.current_soc > 0:
            # Approximate: 53 miles / 100% SOC = 0.53 miles per %
            # Adjust for speed (highway uses more energy)
            efficiency_factor = 1.0 + (self.current_speed - 35) * 0.01  # Base at 35 mph
            soc_drain = miles_per_second / 0.53 * efficiency_factor
            self.current_soc = max(0, self.current_soc - soc_drain)

        # Check for gas mode activation
        should_use_gas = self._should_use_gas()

        if should_use_gas and not self.in_gas_mode:
            self.in_gas_mode = True
            print(f"\n⛽ Gas mode activated at {self.current_soc:.1f}% SOC, {self.total_miles:.1f} miles")

        # Gas mode: engine running, fuel consumption
        if self.in_gas_mode:
            self.current_rpm = 1200 + self.current_speed * 20 + random.uniform(-100, 100)
            self.current_rpm = max(800, min(4000, self.current_rpm))

            # Fuel consumption based on MPG
            gallons_used = miles_per_second / self.GAS_MPG
            fuel_percent_used = (gallons_used / self.TANK_CAPACITY) * 100
            self.current_fuel = max(0, self.current_fuel - fuel_percent_used)
        else:
            self.current_rpm = 0

        # Build Torque-format data
        timestamp_ms = int(datetime.now(timezone.utc).timestamp() * 1000)

        return {
            'eml': 'simulator@volttracker.local',
            'v': '1.0',
            'session': self.session_id,
            'id': 'simulator',
            'time': str(timestamp_ms),

            # GPS
            'kff1006': f'{self.latitude:.6f}',
            'kff1005': f'{self.longitude:.6f}',
            'kff1001': f'{self.current_speed:.1f}',

            # Engine
            'kc': f'{self.current_rpm:.0f}',
            'k11': f'{random.uniform(0, 30):.1f}',  # Throttle position

            # Temperatures (send in Celsius, server converts)
            'k5': f'{(self.ambient_temp - 32) * 5/9 + random.uniform(-2, 2):.1f}',  # Coolant
            'kf': f'{(self.ambient_temp - 32) * 5/9 + random.uniform(-5, 5):.1f}',  # Intake
            'kff1010': f'{(self.ambient_temp - 32) * 5/9:.1f}',  # Ambient

            # Fuel
            'k22002f': f'{self.current_fuel:.1f}',

            # Battery
            'k22005b': f'{self.current_soc:.1f}',
            'k42': f'{12.5 + random.uniform(-0.5, 0.5):.1f}',  # 12V battery

            # Odometer
            'kff1271': f'{self.odometer:.1f}',
        }

    def _calculate_speed(self, progress: float) -> float:
        """Calculate target speed based on trip progress."""

        # Simple pattern: accelerate, cruise, decelerate
        if progress < 0.1:
            # Accelerating
            return 35 * (progress / 0.1)
        elif progress < 0.2:
            # Getting up to speed
            return 35 + 20 * ((progress - 0.1) / 0.1)
        elif progress < 0.8:
            # Cruising with some variation
            base = 55
            # Add some highway/city variation
            variation = math.sin(progress * 20) * 15
            return base + variation
        elif progress < 0.95:
            # Slowing down
            return 55 * (1 - (progress - 0.8) / 0.15)
        else:
            # Final stop
            return 5 * (1 - (progress - 0.95) / 0.05)

    def _should_use_gas(self) -> bool:
        """Determine if gas mode should activate."""

        if self.electric_only:
            return False

        # Manual override
        if self.gas_at_mile and self.total_miles >= self.gas_at_mile:
            return True

        # Normal SOC-based activation (typically around 15-18% but varies)
        soc_threshold = 17 + random.uniform(-2, 2)
        return self.current_soc <= soc_threshold

    def send_telemetry(self, data: dict) -> bool:
        """Send telemetry to server."""
        try:
            response = requests.post(
                f"{self.server_url}/torque/upload",
                data=data,
                timeout=5
            )
            return response.text.strip() == "OK!"
        except requests.RequestException as e:
            print(f"\n❌ Failed to send: {e}")
            return False

    def run(self):
        """Run the simulation."""

        print("━" * 60)
        print("  VoltTracker Data Simulator")
        print("━" * 60)
        print(f"  Server:      {self.server_url}")
        print(f"  Duration:    {self.duration_minutes} minutes")
        print(f"  Start SOC:   {self.start_soc:.0f}%")
        print(f"  Start Fuel:  {self.start_fuel:.0f}%")
        print(f"  Speed:       {self.speed_multiplier}x")
        print(f"  Session ID:  {self.session_id[:8]}...")
        print("━" * 60)
        print("\nStarting simulation... (Ctrl+C to stop)\n")

        self.trip_start_time = time.time()
        interval = 1.0 / self.speed_multiplier  # Seconds between updates
        updates_sent = 0
        last_status_time = time.time()

        try:
            while True:
                elapsed = (time.time() - self.trip_start_time) * self.speed_multiplier
                sim_elapsed = elapsed  # Simulated seconds

                if sim_elapsed >= self.duration_minutes * 60:
                    break

                # Generate and send telemetry
                data = self.generate_telemetry(sim_elapsed)
                if self.send_telemetry(data):
                    updates_sent += 1

                # Status update every 5 real seconds
                if time.time() - last_status_time >= 5:
                    self._print_status(sim_elapsed, updates_sent)
                    last_status_time = time.time()

                # Wait for next update
                time.sleep(interval)

        except KeyboardInterrupt:
            print("\n\nSimulation interrupted.")

        self._print_summary(updates_sent)

    def _print_status(self, elapsed_seconds: float, updates: int):
        """Print current simulation status."""

        minutes = int(elapsed_seconds / 60)
        seconds = int(elapsed_seconds % 60)
        mode = "⛽ GAS" if self.in_gas_mode else "🔋 EV"

        # Build status line
        status = (
            f"\r  {mode} | "
            f"Time: {minutes:02d}:{seconds:02d} | "
            f"Speed: {self.current_speed:5.1f} mph | "
            f"Miles: {self.total_miles:5.1f} | "
            f"SOC: {self.current_soc:5.1f}% | "
            f"Fuel: {self.current_fuel:5.1f}% | "
            f"Updates: {updates}"
        )

        print(status, end='', flush=True)

    def _print_summary(self, updates: int):
        """Print simulation summary."""

        print("\n")
        print("━" * 60)
        print("  Simulation Complete")
        print("━" * 60)
        print(f"  Total Miles:      {self.total_miles:.1f}")
        print(f"  Final SOC:        {self.current_soc:.1f}%")
        print(f"  Final Fuel:       {self.current_fuel:.1f}%")
        print(f"  Used Gas Mode:    {'Yes' if self.in_gas_mode else 'No'}")
        print(f"  Updates Sent:     {updates}")
        print("━" * 60)
        print("\nCheck dashboard at: http://localhost:8080")


def main():
    parser = argparse.ArgumentParser(
        description='VoltTracker Data Simulator',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python simulator.py                      # Default 30-minute trip
  python simulator.py --duration 60        # 60-minute trip
  python simulator.py --speed fast         # 10x speed (3 min real time = 30 min sim)
  python simulator.py --gas-at-mile 15     # Force gas mode at mile 15
  python simulator.py --electric-only      # Pure EV trip
  python simulator.py --soc 50             # Start at 50% SOC
        """
    )

    parser.add_argument(
        '--url',
        default='http://localhost:8080',
        help='Server URL (default: http://localhost:8080)'
    )
    parser.add_argument(
        '--duration',
        type=int,
        default=30,
        help='Trip duration in minutes (default: 30)'
    )
    parser.add_argument(
        '--soc',
        type=float,
        default=100.0,
        help='Starting SOC percentage (default: 100)'
    )
    parser.add_argument(
        '--fuel',
        type=float,
        default=80.0,
        help='Starting fuel percentage (default: 80)'
    )
    parser.add_argument(
        '--speed',
        choices=['normal', 'fast', 'faster'],
        default='normal',
        help='Simulation speed: normal (1x), fast (10x), faster (60x)'
    )
    parser.add_argument(
        '--gas-at-mile',
        type=float,
        help='Force gas mode at this mileage'
    )
    parser.add_argument(
        '--electric-only',
        action='store_true',
        help='Never switch to gas mode'
    )
    parser.add_argument(
        '--temp',
        type=float,
        default=70.0,
        help='Ambient temperature in Fahrenheit (default: 70)'
    )

    args = parser.parse_args()

    # Map speed names to multipliers
    speed_map = {
        'normal': 1.0,
        'fast': 10.0,
        'faster': 60.0,
    }

    simulator = VoltSimulator(
        server_url=args.url,
        duration_minutes=args.duration,
        start_soc=args.soc,
        start_fuel=args.fuel,
        speed_multiplier=speed_map[args.speed],
        gas_at_mile=args.gas_at_mile,
        electric_only=args.electric_only,
        ambient_temp=args.temp,
    )

    # Check server connectivity
    try:
        response = requests.get(f"{args.url}/api/status", timeout=5)
        if response.status_code != 200:
            print(f"Warning: Server returned status {response.status_code}")
    except requests.RequestException:
        print(f"Error: Cannot connect to server at {args.url}")
        print("Make sure the server is running with: ./scripts/dev.sh start")
        sys.exit(1)

    simulator.run()


if __name__ == '__main__':
    main()
