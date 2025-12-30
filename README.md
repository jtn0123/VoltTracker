# Volt Efficiency Tracker

A self-hosted data logging and analysis system for the 2017 Chevy Volt (Gen 2). Receives OBD-II telemetry from the Torque Pro Android app, stores it in PostgreSQL, and provides a web dashboard for analyzing fuel efficiency during gasoline operation.

## Features

- **Real-time Data Logging**: Receives telemetry from Torque Pro via HTTP POST
- **Automatic Trip Detection**: Groups data into trips, detects electric-to-gas transitions
- **Gas MPG Tracking**: Calculates fuel efficiency for gasoline-only driving
- **SOC Floor Analysis**: Tracks battery state of charge at gas activation to monitor battery health
- **Refuel Detection**: Automatically logs fuel fill-ups based on fuel level changes
- **Mobile-Responsive Dashboard**: Dark-themed UI for easy viewing

## Prerequisites

- Docker and Docker Compose
- Android phone with [Torque Pro](https://play.google.com/store/apps/details?id=org.prowl.torque)
- OBD-II Bluetooth adapter (OBDLink MX+, Veepeak, etc.)
- Network connectivity between phone and server

## Quick Start

1. **Clone the repository**
   ```bash
   git clone https://github.com/yourusername/VoltTracker.git
   cd VoltTracker
   ```

2. **Configure environment**
   ```bash
   cp .env.example .env
   # Edit .env with secure passwords
   ```

3. **Start the services**
   ```bash
   docker-compose up -d
   ```

4. **Access the dashboard**
   Open http://your-server:8080 in a browser

## Torque Pro Setup

### 1. Import Custom PIDs

The Volt uses GM-specific PIDs not available in standard OBD-II. Import our custom PID file:

1. Copy `torque-config/volt_pids.csv` to your phone
2. In Torque Pro: Settings → Manage Extra PIDs/Sensors → Import from file
3. Select `volt_pids.csv`

### 2. Configure Web Logging

1. In Torque Pro: Settings → Data Logging & Upload → Webserver URL
2. Enter: `http://your-server-ip:8080/torque/upload`
3. Enable "Upload to webserver"
4. Set upload interval to 1-5 seconds for best results

### 3. Select Data to Log

In Torque Pro's main display, add gauges for the PIDs you want to track:
- State of Charge (SOC)
- Fuel Level Percent
- Engine RPM
- Speed (GPS)
- Ambient Temperature

**Note**: All visible PIDs are included in the web upload.

## Dashboard

The web dashboard provides:

### Summary Cards
- **Lifetime Gas MPG**: Average fuel efficiency across all gas driving
- **Current Tank MPG**: Efficiency since last detected refuel
- **Avg SOC Floor**: Average battery percentage when gas engine activates
- **Total Miles Tracked**: All miles logged through the system

### MPG Trend Chart
Line chart showing gas MPG over time with selectable timeframes (7/30/90 days, all time).

### Recent Trips Table
Lists recent trips with:
- Date/time
- Total, electric, and gas miles
- Gas MPG (if applicable)
- SOC at gas transition

### SOC Floor Analysis
- Distribution histogram of SOC at gas activation
- Temperature correlation (cold vs. warm weather)
- Trend analysis for battery degradation monitoring

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/torque/upload` | POST | Receive Torque Pro data |
| `/api/trips` | GET | List trips with summaries |
| `/api/trips/<id>` | GET | Detailed trip data |
| `/api/efficiency/summary` | GET | Efficiency statistics |
| `/api/soc/analysis` | GET | SOC floor analysis |
| `/api/mpg/trend` | GET | MPG trend data |
| `/api/fuel/history` | GET | Fuel event history |
| `/api/fuel/add` | POST | Manual fuel event entry |
| `/api/status` | GET | System status |

## Database Schema

### telemetry_raw
Stores every data point received (timestamp, GPS, speed, RPM, fuel level, SOC, etc.)

### trips
Aggregated trip summaries with electric/gas miles split and MPG calculations

### fuel_events
Refueling events for tank-by-tank efficiency tracking

### soc_transitions
Records of each electric-to-gas transition for battery health monitoring

## SOC Floor Analysis

The "SOC Floor" is the battery state of charge when the Volt's gas engine activates. This is a key indicator of battery health:

- **New battery**: Engine starts at ~15% SOC
- **Degraded battery**: Engine starts at higher SOC (18-25%+)

The system tracks this over time to help identify battery degradation. The standalone analysis script provides detailed reports:

```bash
python scripts/analyze_soc_floor.py --db postgresql://volt:password@localhost:5432/volt_tracker
```

## Troubleshooting

### No data appearing
- Verify Torque Pro can reach your server: `curl http://your-server:8080/api/status`
- Check Torque Pro upload settings and ensure "Upload to webserver" is enabled
- Review container logs: `docker-compose logs receiver`

### MPG seems wrong
- Fuel level sensor can be noisy; system applies smoothing but short trips may be inaccurate
- Ensure you're driving enough gas miles (1+ miles) for reliable calculations

### Missing PIDs
- Import the custom PID file from `torque-config/volt_pids.csv`
- Some PIDs require specific ECU headers; the file includes correct header settings

### Dashboard not loading
- Verify containers are running: `docker-compose ps`
- Check database health: `docker-compose exec db pg_isready`

## Architecture

```
┌─────────────────┐     HTTP POST      ┌──────────────────┐
│   Torque Pro    │ ──────────────────▶│  Flask Receiver  │
│   (Android)     │                    │   (Port 8080)    │
└─────────────────┘                    └────────┬─────────┘
                                                │
                                                ▼
┌─────────────────┐                    ┌──────────────────┐
│   Web Browser   │ ◀──────────────────│   PostgreSQL     │
│   (Dashboard)   │     API Queries    │   (Port 5432)    │
└─────────────────┘                    └──────────────────┘
```

## Local Development

### Quick Start

```bash
# Start development environment
./scripts/dev.sh start

# Get your Mac's IP for Torque Pro
./scripts/dev.sh ip

# View logs
./scripts/dev.sh logs

# Stop everything
./scripts/dev.sh stop
```

### Phone Hotspot Setup (In-Car Testing)

To test with your real phone and Torque Pro while in the car:

```
┌─────────────────┐     WiFi Hotspot     ┌─────────────────┐
│  iPhone/Android │ ◀───────────────────▶│   MacBook       │
│   (Hotspot ON)  │                      │ (Docker running)│
│                 │                      │                 │
│  Torque Pro     │ ────HTTP POST───────▶│  Flask :8080    │
│  OBD Adapter    │                      │  PostgreSQL     │
└─────────────────┘                      └─────────────────┘
```

**Steps:**
1. Enable Personal Hotspot on your phone (Settings → Personal Hotspot)
2. Connect your Mac to the phone's WiFi hotspot
3. Run `./scripts/dev.sh start` to start the server
4. Run `./scripts/dev.sh ip` to get your Mac's IP (typically `172.20.10.x`)
5. In Torque Pro, set Webserver URL to: `http://172.20.10.2:8080/torque/upload`
6. Start driving!

**Note**: iPhone hotspots typically assign IPs in the `172.20.10.x` range. Your Mac will usually be `172.20.10.2`.

### Data Simulator

Test the system without driving using the built-in simulator:

```bash
# Default 30-minute simulated trip
./scripts/dev.sh simulate

# Fast mode (10x speed - 3 real minutes = 30 simulated)
./scripts/dev.sh simulate --speed fast

# Force gas mode at mile 15
./scripts/dev.sh simulate --gas-at-mile 15

# Start with low SOC (will trigger gas mode quickly)
./scripts/dev.sh simulate --soc 20

# Electric-only trip
./scripts/dev.sh simulate --electric-only

# See all options
python scripts/simulator.py --help
```

The simulator generates realistic Volt telemetry:
- Speed patterns (accelerate, cruise, decelerate)
- SOC drain based on speed
- Automatic gas mode when SOC depletes
- Fuel consumption in gas mode
- Random variations for realism

### Development Commands

| Command | Description |
|---------|-------------|
| `./scripts/dev.sh start` | Start all services with hot reload |
| `./scripts/dev.sh stop` | Stop all services |
| `./scripts/dev.sh restart` | Restart services |
| `./scripts/dev.sh logs` | Tail logs from all services |
| `./scripts/dev.sh ip` | Show Mac's IP for Torque Pro |
| `./scripts/dev.sh status` | Show service status |
| `./scripts/dev.sh simulate` | Run data simulator |
| `./scripts/dev.sh db` | Connect to PostgreSQL CLI |
| `./scripts/dev.sh reset` | Reset database (deletes all data) |

### Database Access

```bash
# Connect to PostgreSQL
./scripts/dev.sh db

# Example queries
SELECT * FROM trips ORDER BY start_time DESC LIMIT 5;
SELECT COUNT(*) FROM telemetry_raw;
\q  -- quit
```

### Database Migrations

The schema is initialized via `db/init.sql`. For schema changes:

```bash
# Warning: This destroys all data!
./scripts/dev.sh reset
./scripts/dev.sh start
```

## Future Roadmap

- [ ] Individual battery cell voltage tracking
- [ ] kWh/mile efficiency for electric portions
- [ ] Charging session logging
- [ ] Maintenance reminders
- [ ] CSV export functionality
- [ ] Multiple vehicle support
- [ ] Mobile app integration

## License

MIT License - See LICENSE file for details.

## Acknowledgments

- Volt community PID spreadsheets for custom PID definitions
- Torque Pro for the excellent OBD-II app
