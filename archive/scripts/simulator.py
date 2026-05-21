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
    python simulator.py --profile commute    # Use a predefined trip profile
    python simulator.py --charge             # Simulate a charging session
    python simulator.py --interactive        # Interactive mode with keyboard control
"""

import argparse
import math
import random
import sys
import time
import uuid
from datetime import datetime, timezone
from typing import Any, Dict, Optional

import requests

# ============================================================================
# Trip Profiles
# ============================================================================

TRIP_PROFILES: Dict[str, Dict[str, Any]] = {
    "commute": {
        "name": "Morning Commute",
        "description": "Typical 15-mile suburban commute with traffic",
        "duration_minutes": 35,
        "start_soc": 95,
        "start_fuel": 75,
        "ambient_temp": 65,
        "speed_pattern": "city",
        "electric_only": True,
    },
    "highway": {
        "name": "Highway Trip",
        "description": "60-mile highway trip at higher speeds",
        "duration_minutes": 60,
        "start_soc": 100,
        "start_fuel": 80,
        "ambient_temp": 70,
        "speed_pattern": "highway",
        "electric_only": False,
    },
    "cold": {
        "name": "Cold Weather Trip",
        "description": "Winter driving with reduced EV range",
        "duration_minutes": 40,
        "start_soc": 100,
        "start_fuel": 85,
        "ambient_temp": 25,
        "speed_pattern": "mixed",
        "electric_only": False,
    },
    "mountain": {
        "name": "Mountain Drive",
        "description": "Hilly terrain with regenerative braking",
        "duration_minutes": 45,
        "start_soc": 100,
        "start_fuel": 90,
        "ambient_temp": 55,
        "speed_pattern": "mountain",
        "electric_only": False,
    },
    "errands": {
        "name": "Running Errands",
        "description": "Short stops with multiple starts",
        "duration_minutes": 50,
        "start_soc": 80,
        "start_fuel": 70,
        "ambient_temp": 72,
        "speed_pattern": "stop_and_go",
        "electric_only": True,
    },
    "road_trip": {
        "name": "Road Trip",
        "description": "Long distance highway driving",
        "duration_minutes": 120,
        "start_soc": 100,
        "start_fuel": 100,
        "ambient_temp": 75,
        "speed_pattern": "highway",
        "electric_only": False,
    },
}


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
        speed_pattern: str = "mixed",
    ):
        self.server_url = server_url.rstrip("/")
        self.duration_minutes = duration_minutes
        self.start_soc = start_soc
        self.start_fuel = start_fuel
        self.speed_multiplier = speed_multiplier
        self.gas_at_mile = gas_at_mile
        self.electric_only = electric_only
        self.ambient_temp = ambient_temp
        self.speed_pattern = speed_pattern

        # Cold weather reduces EV range
        self.cold_weather_factor = 1.0
        if ambient_temp < 50:
            self.cold_weather_factor = 1.3 + (50 - ambient_temp) * 0.01

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

        # GPS simulation (starts in San Francisco area)
        self.latitude = 37.7749 + random.uniform(-0.01, 0.01)
        self.longitude = -122.4194 + random.uniform(-0.01, 0.01)

        # For mountain pattern - track elevation changes
        self.elevation_trend = 0  # -1=descending, 0=flat, 1=ascending

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

            # Cold weather reduces range
            efficiency_factor *= self.cold_weather_factor

            # Mountain mode: going uphill uses more, downhill regenerates
            if self.speed_pattern == "mountain":
                if self.elevation_trend > 0:  # Uphill
                    efficiency_factor *= 1.4
                elif self.elevation_trend < 0:  # Downhill - regen
                    efficiency_factor *= 0.5

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
            "eml": "simulator@volttracker.local",
            "v": "1.0",
            "session": self.session_id,
            "id": "simulator",
            "time": str(timestamp_ms),
            # GPS
            "kff1006": f"{self.latitude:.6f}",
            "kff1005": f"{self.longitude:.6f}",
            "kff1001": f"{self.current_speed:.1f}",
            # Engine
            "kc": f"{self.current_rpm:.0f}",
            "k11": f"{random.uniform(0, 30):.1f}",  # Throttle position
            # Temperatures (send in Celsius, server converts)
            "k5": f"{(self.ambient_temp - 32) * 5/9 + random.uniform(-2, 2):.1f}",  # Coolant
            "kf": f"{(self.ambient_temp - 32) * 5/9 + random.uniform(-5, 5):.1f}",  # Intake
            "kff1010": f"{(self.ambient_temp - 32) * 5/9:.1f}",  # Ambient
            # Fuel
            "k22002f": f"{self.current_fuel:.1f}",
            # Battery
            "k22005b": f"{self.current_soc:.1f}",
            "k42": f"{12.5 + random.uniform(-0.5, 0.5):.1f}",  # 12V battery
            # Odometer
            "kff1271": f"{self.odometer:.1f}",
        }

    def _calculate_speed(self, progress: float) -> float:
        """Calculate target speed based on trip progress and speed pattern."""

        if self.speed_pattern == "highway":
            return self._highway_speed(progress)
        elif self.speed_pattern == "city":
            return self._city_speed(progress)
        elif self.speed_pattern == "mountain":
            return self._mountain_speed(progress)
        elif self.speed_pattern == "stop_and_go":
            return self._stop_and_go_speed(progress)
        else:  # 'mixed' or default
            return self._mixed_speed(progress)

    def _mixed_speed(self, progress: float) -> float:
        """Default mixed city/highway speed pattern."""
        if progress < 0.1:
            return 35 * (progress / 0.1)
        elif progress < 0.2:
            return 35 + 20 * ((progress - 0.1) / 0.1)
        elif progress < 0.8:
            base = 55
            variation = math.sin(progress * 20) * 15
            return base + variation
        elif progress < 0.95:
            return 55 * (1 - (progress - 0.8) / 0.15)
        else:
            return 5 * (1 - (progress - 0.95) / 0.05)

    def _highway_speed(self, progress: float) -> float:
        """Highway driving - higher sustained speeds."""
        if progress < 0.05:
            return 45 * (progress / 0.05)
        elif progress < 0.1:
            return 45 + 25 * ((progress - 0.05) / 0.05)
        elif progress < 0.9:
            # Cruise at highway speed with small variations
            base = 70
            variation = math.sin(progress * 30) * 5
            return base + variation
        elif progress < 0.97:
            return 70 * (1 - (progress - 0.9) / 0.07)
        else:
            return 10 * (1 - (progress - 0.97) / 0.03)

    def _city_speed(self, progress: float) -> float:
        """City driving - lower speeds, more variation."""
        if progress < 0.05:
            return 25 * (progress / 0.05)
        elif progress < 0.9:
            # Frequent speed changes simulating traffic lights
            cycle_pos = (progress * 50) % 1.0
            if cycle_pos < 0.7:
                base = 30 + math.sin(progress * 100) * 10
            else:
                # Slowing for light
                base = 15 * ((1.0 - cycle_pos) / 0.3)
            return max(0, base)
        else:
            return 25 * (1 - (progress - 0.9) / 0.1)

    def _mountain_speed(self, progress: float) -> float:
        """Mountain driving - variable speeds with elevation changes."""
        # Update elevation trend periodically
        cycle = int(progress * 10) % 4
        if cycle == 0:
            self.elevation_trend = 1  # Uphill
        elif cycle == 1:
            self.elevation_trend = 0  # Flat
        elif cycle == 2:
            self.elevation_trend = -1  # Downhill
        else:
            self.elevation_trend = 0  # Flat

        if progress < 0.05:
            return 35 * (progress / 0.05)
        elif progress < 0.9:
            base = 40
            # Slower uphill, faster downhill
            if self.elevation_trend > 0:
                base = 30
            elif self.elevation_trend < 0:
                base = 50
            variation = math.sin(progress * 15) * 8
            return base + variation
        else:
            return 35 * (1 - (progress - 0.9) / 0.1)

    def _stop_and_go_speed(self, progress: float) -> float:
        """Stop and go pattern for errands."""
        # Multiple stops during trip
        num_stops = 5
        segment = 1.0 / num_stops

        segment_progress = (progress % segment) / segment

        if segment_progress < 0.1:
            # Starting from stop
            return 25 * (segment_progress / 0.1)
        elif segment_progress < 0.7:
            # Driving
            return 25 + math.sin(segment_progress * 20) * 10
        elif segment_progress < 0.85:
            # Slowing down
            return 35 * (1 - (segment_progress - 0.7) / 0.15)
        else:
            # Stopped (parking, etc.)
            return 0

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
            response = requests.post(f"{self.server_url}/torque/upload", data=data, timeout=5)
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

        print(status, end="", flush=True)

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


# ============================================================================
# Charging Simulator
# ============================================================================


class ChargingSimulator:
    """Simulates a Chevy Volt charging session."""

    BATTERY_CAPACITY_KWH = 18.4  # usable kWh

    # Charging rates (kW)
    L1_RATE_KW = 1.4  # 120V, 12A
    L2_RATE_KW = 3.6  # 240V, 15A (typical home)
    L2_HIGH_RATE_KW = 7.2  # 240V, 30A

    def __init__(
        self,
        server_url: str = "http://localhost:8080",
        charge_type: str = "L2",
        start_soc: float = 20.0,
        target_soc: float = 100.0,
        speed_multiplier: float = 1.0,
        location: str = "Home",
    ):
        self.server_url = server_url.rstrip("/")
        self.charge_type = charge_type
        self.start_soc = start_soc
        self.target_soc = target_soc
        self.speed_multiplier = speed_multiplier
        self.location = location

        # Determine charge rate
        if charge_type == "L1":
            self.charge_rate_kw = self.L1_RATE_KW
        elif charge_type == "L2":
            self.charge_rate_kw = self.L2_RATE_KW
        else:
            self.charge_rate_kw = self.L2_HIGH_RATE_KW

        # Session state
        self.current_soc = start_soc
        self.kwh_added = 0.0
        self.start_time = None

    def run(self):
        """Run the charging simulation."""
        # Calculate estimated charge time
        soc_to_add = self.target_soc - self.start_soc
        kwh_to_add = (soc_to_add / 100) * self.BATTERY_CAPACITY_KWH
        hours_to_charge = kwh_to_add / self.charge_rate_kw
        minutes_to_charge = int(hours_to_charge * 60)

        print("━" * 60)
        print("  VoltTracker Charging Simulator")
        print("━" * 60)
        print(f"  Server:       {self.server_url}")
        print(f"  Charge Type:  {self.charge_type} ({self.charge_rate_kw} kW)")
        print(f"  Start SOC:    {self.start_soc:.0f}%")
        print(f"  Target SOC:   {self.target_soc:.0f}%")
        print(f"  Location:     {self.location}")
        print(f"  Est. Time:    {minutes_to_charge} minutes")
        print(f"  Speed:        {self.speed_multiplier}x")
        print("━" * 60)
        print("\nCharging... (Ctrl+C to stop)\n")

        self.start_time = datetime.now(timezone.utc)
        interval = 10.0 / self.speed_multiplier  # Update every 10 simulated seconds
        updates = 0
        last_status_time = time.time()

        try:
            while self.current_soc < self.target_soc:
                # Calculate SOC increase per 10-second interval
                kwh_per_interval = self.charge_rate_kw * (10 / 3600)
                soc_increase = (kwh_per_interval / self.BATTERY_CAPACITY_KWH) * 100

                self.current_soc = min(self.target_soc, self.current_soc + soc_increase)
                self.kwh_added += kwh_per_interval
                updates += 1

                # Status update every 5 real seconds
                if time.time() - last_status_time >= 5:
                    self._print_status()
                    last_status_time = time.time()

                time.sleep(interval)

        except KeyboardInterrupt:
            print("\n\nCharging interrupted.")

        self._complete_session()

    def _print_status(self):
        """Print current charging status."""
        status = (
            f"\r  🔌 {self.charge_type} | "
            f"SOC: {self.current_soc:5.1f}% | "
            f"Added: {self.kwh_added:5.2f} kWh | "
            f"Rate: {self.charge_rate_kw} kW"
        )
        print(status, end="", flush=True)

    def _complete_session(self):
        """Submit the charging session to the server."""
        end_time = datetime.now(timezone.utc)

        # Post charging session to server
        session_data = {
            "start_time": self.start_time.isoformat(),
            "end_time": end_time.isoformat(),
            "start_soc": self.start_soc,
            "end_soc": self.current_soc,
            "kwh_added": round(self.kwh_added, 2),
            "charge_type": self.charge_type,
            "location_name": self.location,
            "is_complete": True,
        }

        try:
            response = requests.post(f"{self.server_url}/api/charging/add", json=session_data, timeout=5)
            if response.status_code == 201:
                print("\n")
                print("━" * 60)
                print("  Charging Complete")
                print("━" * 60)
                print(f"  Final SOC:    {self.current_soc:.1f}%")
                print(f"  kWh Added:    {self.kwh_added:.2f}")
                print("  Session saved to database")
                print("━" * 60)
            else:
                print(f"\n\nWarning: Failed to save session: {response.text}")
        except requests.RequestException as e:
            print(f"\n\nWarning: Could not save session: {e}")


def list_profiles():
    """Print available trip profiles."""
    print("\n━" * 60)
    print("  Available Trip Profiles")
    print("━" * 60)
    for name, profile in TRIP_PROFILES.items():
        print(f"\n  {name}:")
        print(f"    {profile['name']}")
        print(f"    {profile['description']}")
        print(
            f"    Duration: {profile['duration_minutes']} min, "
            f"SOC: {profile['start_soc']}%, "
            f"Temp: {profile['ambient_temp']}°F"
        )
    print("\n" + "━" * 60)
    print("\nUsage: python simulator.py --profile <name>\n")


def main():
    parser = argparse.ArgumentParser(
        description="VoltTracker Data Simulator",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python simulator.py                      # Default 30-minute trip
  python simulator.py --profile commute    # Use commute profile
  python simulator.py --profile highway    # Highway trip profile
  python simulator.py --list-profiles      # List all profiles
  python simulator.py --charge             # Simulate L2 charging
  python simulator.py --charge --type L1   # Simulate L1 charging
  python simulator.py --duration 60        # 60-minute trip
  python simulator.py --speed fast         # 10x speed
  python simulator.py --gas-at-mile 15     # Force gas mode at mile 15
  python simulator.py --electric-only      # Pure EV trip
        """,
    )

    parser.add_argument("--url", default="http://localhost:8080", help="Server URL (default: http://localhost:8080)")

    # Trip profiles
    parser.add_argument("--profile", choices=list(TRIP_PROFILES.keys()), help="Use a predefined trip profile")
    parser.add_argument("--list-profiles", action="store_true", help="List available trip profiles")

    # Charging mode
    parser.add_argument("--charge", action="store_true", help="Simulate a charging session instead of a trip")
    parser.add_argument("--type", choices=["L1", "L2"], default="L2", help="Charging type: L1 (120V) or L2 (240V)")
    parser.add_argument("--target-soc", type=float, default=100.0, help="Target SOC for charging (default: 100)")
    parser.add_argument("--location", default="Home", help="Charging location name (default: Home)")

    # Trip settings
    parser.add_argument("--duration", type=int, default=30, help="Trip duration in minutes (default: 30)")
    parser.add_argument("--soc", type=float, default=100.0, help="Starting SOC percentage (default: 100)")
    parser.add_argument("--fuel", type=float, default=80.0, help="Starting fuel percentage (default: 80)")
    parser.add_argument(
        "--speed",
        choices=["normal", "fast", "faster"],
        default="normal",
        help="Simulation speed: normal (1x), fast (10x), faster (60x)",
    )
    parser.add_argument("--gas-at-mile", type=float, help="Force gas mode at this mileage")
    parser.add_argument("--electric-only", action="store_true", help="Never switch to gas mode")
    parser.add_argument("--temp", type=float, default=70.0, help="Ambient temperature in Fahrenheit (default: 70)")

    args = parser.parse_args()

    # List profiles and exit
    if args.list_profiles:
        list_profiles()
        sys.exit(0)

    # Map speed names to multipliers
    speed_map = {
        "normal": 1.0,
        "fast": 10.0,
        "faster": 60.0,
    }

    # Check server connectivity
    try:
        response = requests.get(f"{args.url}/api/status", timeout=5)
        if response.status_code != 200:
            print(f"Warning: Server returned status {response.status_code}")
    except requests.RequestException:
        print(f"Error: Cannot connect to server at {args.url}")
        print("Make sure the server is running with: ./scripts/dev.sh start")
        sys.exit(1)

    # Charging mode
    if args.charge:
        charging_sim = ChargingSimulator(
            server_url=args.url,
            charge_type=args.type,
            start_soc=args.soc,
            target_soc=args.target_soc,
            speed_multiplier=speed_map[args.speed],
            location=args.location,
        )
        charging_sim.run()
        return

    # Apply profile settings if specified
    duration = args.duration
    start_soc = args.soc
    start_fuel = args.fuel
    ambient_temp = args.temp
    electric_only = args.electric_only
    speed_pattern = "mixed"

    if args.profile:
        profile = TRIP_PROFILES[args.profile]
        print(f"\n📋 Using profile: {profile['name']}")
        print(f"   {profile['description']}\n")

        duration = profile["duration_minutes"]
        start_soc = profile["start_soc"]
        start_fuel = profile["start_fuel"]
        ambient_temp = profile["ambient_temp"]
        electric_only = profile.get("electric_only", False)
        speed_pattern = profile.get("speed_pattern", "mixed")

    simulator = VoltSimulator(
        server_url=args.url,
        duration_minutes=duration,
        start_soc=start_soc,
        start_fuel=start_fuel,
        speed_multiplier=speed_map[args.speed],
        gas_at_mile=args.gas_at_mile,
        electric_only=electric_only,
        ambient_temp=ambient_temp,
        speed_pattern=speed_pattern,
    )

    simulator.run()


if __name__ == "__main__":
    main()
