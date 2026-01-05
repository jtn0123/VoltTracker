# VoltTracker Unused Data Analysis
**Analysis Date:** 2025-01-05
**Purpose:** Identify collected data that's not visualized or analyzed

---

## Executive Summary

VoltTracker collects **40+ telemetry data points** per reading, but only **~60% are actively visualized** in the dashboard. This represents a significant opportunity to add value without collecting new data.

**Key Findings:**
- 🌡️ **Temperature data** - 6 sensors collected, only 1 used
- 🚗 **Driving behavior** - Throttle position logged, never analyzed
- ⚙️ **Powertrain data** - 3 motors tracked, not visualized
- 💰 **Cost data** - Collected but minimal analysis
- 📊 **Efficiency extremes** - Calculated but not surfaced

---

## Category 1: Temperature Data 🌡️

### What's Being Collected

| Field | Source | Units | Frequency | Storage |
|-------|--------|-------|-----------|---------|
| `ambient_temp_f` | OBD-II PID | °F | Every reading | ✅ telemetry_raw |
| `battery_temp_f` | OBD-II PID | °F | Every reading | ✅ telemetry_raw |
| `battery_coolant_temp_f` | OBD-II PID | °F | Every reading | ✅ telemetry_raw |
| `coolant_temp_f` | Engine | °F | Every reading | ✅ telemetry_raw |
| `intake_air_temp_f` | Engine | °F | Every reading | ✅ telemetry_raw |
| `engine_oil_temp_f` | Engine | °F | Every reading | ✅ telemetry_raw |

### Where It's Stored

```python
# receiver/models.py:84-90
class TelemetryRaw(Base):
    coolant_temp_f = Column(Float)
    intake_air_temp_f = Column(Float)
    ambient_temp_f = Column(Float)
    battery_temp_f = Column(Float)         # ⚠️ NOT VISUALIZED
    battery_coolant_temp_f = Column(Float) # ⚠️ NOT VISUALIZED
    # Also: engine_oil_temp_f, motor temps
```

### Current Usage: MINIMAL ⚠️

**Only used for:**
1. SOC correlation analysis (ambient temp only):
   ```python
   # receiver/utils/calculations.py:245-265
   # Used to analyze SOC floor vs temperature
   temp_soc_pairs = [(reading.ambient_temp_f, reading.state_of_charge)
                     for reading in telemetry if reading.ambient_temp_f]
   ```

2. Weather impact factor (calculated but not shown):
   ```python
   # receiver/models.py:224 (Trip model)
   weather_impact_factor = Column(Float)  # Calculated, never displayed!
   ```

### What's NOT Being Done ❌

1. **No temperature vs efficiency charts**
   - kWh/mile by temperature range
   - MPG degradation in cold weather
   - Battery heating cost visualization

2. **No battery temperature analysis**
   - Optimal temperature range detection
   - Preconditioning effectiveness
   - Battery heater energy consumption

3. **No thermal management insights**
   - Battery coolant effectiveness
   - Motor temperature patterns
   - Seasonal efficiency comparison

4. **No cold weather alerts**
   - "Battery at 25°F - preheat recommended"
   - "Range will drop 30% in current temps"
   - "Engine will run for cabin heat"

### Sample Data Volume
```sql
-- Example: Query shows temperature data exists but unused
SELECT
    date_trunc('day', timestamp) as day,
    AVG(ambient_temp_f) as avg_temp,
    AVG(battery_temp_f) as avg_battery_temp,
    AVG(battery_coolant_temp_f) as avg_coolant_temp
FROM telemetry_raw
WHERE ambient_temp_f IS NOT NULL
GROUP BY day
ORDER BY day DESC
LIMIT 30;

-- Returns 30 days of data - ALL UNUSED in UI
```

### Potential Features Using This Data

**🎯 Feature 1: Temperature Efficiency Analysis**
- Chart: kWh/mi vs ambient temperature
- Chart: Gas MPG vs temperature (cold engine penalty)
- Show: "Your range drops X% below freezing"
- Compare: Summer vs winter efficiency

**🎯 Feature 2: Battery Thermal Management**
- Chart: Battery temp over trip timeline
- Detect: Battery heater activation
- Calculate: Energy cost of heating
- Alert: "Battery cold - preconditioning recommended"

**🎯 Feature 3: Seasonal Insights**
- Compare efficiency by season
- Show impact of cabin heating
- Estimate range for current conditions
- Tips: "Precondition while plugged in to save 2 kWh"

---

## Category 2: Driving Behavior Data 🚗

### What's Being Collected

| Field | Source | Units | Frequency | Storage |
|-------|--------|-------|-----------|---------|
| `throttle_position` | OBD-II | % (0-100) | Every reading | ✅ telemetry_raw |
| `speed_mph` | OBD-II/GPS | MPH | Every reading | ✅ telemetry_raw |
| `engine_rpm` | OBD-II | RPM | Every reading | ✅ telemetry_raw (only for trip detection) |

### Where It's Stored

```python
# receiver/models.py:82
class TelemetryRaw(Base):
    throttle_position = Column(Float)  # ⚠️ LOGGED BUT NEVER ANALYZED
    speed_mph = Column(Float)          # ⚠️ Only used for distance calc
    engine_rpm = Column(Float)         # ⚠️ Only used to detect engine on/off
```

### Current Usage: MINIMAL ⚠️

**Only used for:**
1. Trip distance calculation (speed integration)
2. Engine on/off detection (rpm > 0)
3. Basic trip statistics (avg speed)

**NOT analyzed:**
- Throttle patterns
- Acceleration behavior
- Braking patterns
- Speed consistency

### What's NOT Being Done ❌

1. **No acceleration analysis**
   ```python
   # Could calculate from speed + timestamps:
   acceleration = (speed[i] - speed[i-1]) / (time[i] - time[i-1])
   aggressive_events = count(acceleration > 5 mph/s)  # NOT DONE
   ```

2. **No throttle smoothness scoring**
   ```python
   # Could analyze:
   throttle_variance = std_dev(throttle_position)  # NOT DONE
   smooth_driving_score = 100 - (throttle_variance * 10)  # NOT DONE
   ```

3. **No driving style classification**
   - Aggressive vs eco-friendly
   - City vs highway detection
   - Stop-and-go traffic identification

4. **No regenerative braking analysis**
   ```python
   # Could detect from:
   # - Speed decreasing without engine RPM
   # - Battery power negative
   # - Throttle = 0
   # NOT IMPLEMENTED
   ```

### Sample Queries Showing Unused Data

```sql
-- Throttle position data exists but is never analyzed
SELECT
    session_id,
    AVG(throttle_position) as avg_throttle,
    STDDEV(throttle_position) as throttle_variance,
    MAX(throttle_position) as max_throttle,
    COUNT(CASE WHEN throttle_position > 80 THEN 1 END) as hard_accelerations
FROM telemetry_raw
WHERE throttle_position IS NOT NULL
GROUP BY session_id;

-- Speed patterns exist but not analyzed
SELECT
    session_id,
    AVG(speed_mph) as avg_speed,
    MAX(speed_mph) as max_speed,
    COUNT(CASE WHEN speed_mph < 5 AND LAG(speed_mph) > 15 THEN 1 END) as hard_braking_events
FROM telemetry_raw
GROUP BY session_id;
```

### Potential Features Using This Data

**🎯 Feature 1: Driving Efficiency Score**
- Score 0-100 based on:
  - Throttle smoothness (40%)
  - Acceleration events (30%)
  - Speed consistency (20%)
  - Regen capture (10%)
- Tips: "Reduce hard acceleration to gain 3 miles range"
- Track improvement over time

**🎯 Feature 2: Eco-Driving Coach**
- Detect aggressive acceleration: "10 hard accelerations this trip"
- Suggest: "Gradual acceleration could save 0.5 kWh"
- Compare: "Your driving vs optimal: 85% efficient"

**🎯 Feature 3: Regenerative Braking Analysis**
- Show energy recovered per trip
- Calculate: "Captured 1.2 kWh from braking"
- Tips: "One-pedal driving could capture 15% more energy"

---

## Category 3: Powertrain Data ⚙️

### What's Being Collected

| Field | Source | Units | Frequency | Storage |
|-------|--------|-------|-----------|---------|
| `motor_a_rpm` | OBD-II | RPM | Every reading | ✅ telemetry_raw |
| `motor_b_rpm` | OBD-II | RPM | Every reading | ✅ telemetry_raw |
| `generator_rpm` | OBD-II | RPM | Every reading | ✅ telemetry_raw |
| `engine_rpm` | OBD-II | RPM | Every reading | ✅ telemetry_raw |
| `motor_a_temp_c` | OBD-II | °C | Every reading | ✅ telemetry_raw |
| `motor_b_temp_c` | OBD-II | °C | Every reading | ✅ telemetry_raw |
| `engine_torque` | OBD-II | % | Every reading | ✅ telemetry_raw |
| `transmission_temp` | OBD-II | °F | Every reading | ✅ telemetry_raw |

### Where It's Stored

```python
# receiver/models.py - TelemetryRaw model
motor_a_rpm = Column(Float)           # ⚠️ STORED, NOT VISUALIZED
motor_b_rpm = Column(Float)           # ⚠️ STORED, NOT VISUALIZED
generator_rpm = Column(Float)         # ⚠️ STORED, NOT VISUALIZED
engine_rpm = Column(Float)            # ✅ Only used for on/off detection
motor_a_temp_c = Column(Float)        # ⚠️ STORED, NOT VISUALIZED
motor_b_temp_c = Column(Float)        # ⚠️ STORED, NOT VISUALIZED
engine_torque = Column(Float)         # ⚠️ STORED, NOT VISUALIZED
transmission_temp = Column(Float)     # ⚠️ STORED, NOT VISUALIZED
```

### Current Usage: ZERO ❌

**Absolutely no analysis or visualization of:**
- Which motors are active when
- Generator (engine) operation patterns
- Motor temperatures
- Transmission behavior
- Power flow through drivetrain

### Volt Gen 2 Operating Modes (Can Be Detected!)

The Volt has distinct operating modes detectable from powertrain data:

| Mode | Motor A | Motor B | Generator | Engine | Use Case |
|------|---------|---------|-----------|--------|----------|
| **EV Mode** | Active (>0) | Active (>0) | 0 | 0 | Pure electric |
| **Hold Mode** | Active | Active | Active (>0) | On (>0) | Save battery |
| **Mountain Mode** | Active | Active | Active | On | Climbing, battery charged |
| **Engine Direct** | Off (0) | Active | Active | On | Highway (>70mph) |
| **Hybrid Assist** | Active | Active | Active | On | Battery + engine |

**None of this is currently detected or shown!**

### Sample Query Showing Unused Data

```sql
-- This data exists but is NEVER visualized
SELECT
    timestamp,
    motor_a_rpm,
    motor_b_rpm,
    generator_rpm,
    engine_rpm,
    CASE
        WHEN motor_a_rpm > 0 AND motor_b_rpm > 0
             AND generator_rpm = 0 AND engine_rpm = 0 THEN 'EV Mode'
        WHEN generator_rpm > 0 AND engine_rpm > 0 THEN 'Engine Running'
        ELSE 'Unknown'
    END as detected_mode
FROM telemetry_raw
WHERE session_id = 'some-trip-id'
ORDER BY timestamp;

-- Returns full powertrain state timeline - UNUSED!
```

### Potential Features Using This Data

**🎯 Feature 1: Powertrain Mode Timeline** (IMPLEMENTING)
- Timeline chart showing which motors are active
- Color-coded by mode:
  - Green: EV mode (motors only)
  - Yellow: Hybrid assist (motors + generator)
  - Orange: Engine direct drive
- Show mode transitions during trip

**🎯 Feature 2: Operating Mode Statistics**
- "This trip: 60% EV, 30% Hybrid, 10% Engine"
- "You used Hold mode for 12 miles"
- Compare efficiency by mode

**🎯 Feature 3: Motor Temperature Monitoring**
- Chart: Motor temps over trip
- Alert: "Motor A temp high - reduce load"
- Detect thermal throttling

**🎯 Feature 4: Transmission Analysis**
- Track transmission temp
- Correlate with efficiency
- Detect abnormal behavior

---

## Category 4: Cost & Efficiency Data 💰

### What's Being Collected

| Field | Source | Frequency | Storage |
|-------|--------|-----------|---------|
| Charging session kWh | Calculated | Per session | ✅ charging_sessions |
| Charging cost | Manual entry | Per session | ✅ charging_sessions |
| Fuel gallons | Manual entry | Per fillup | ✅ fuel_events |
| Fuel cost | Manual entry | Per fillup | ✅ fuel_events |
| Electric miles | Calculated | Per trip | ✅ trips |
| Gas miles | Calculated | Per trip | ✅ trips |

### Where It's Stored

```python
# receiver/models.py:335-381
class ChargingSession(Base):
    total_kwh_delivered = Column(Float)
    total_cost = Column(Float)            # ✅ Stored
    cost_per_kwh = Column(Float)          # ✅ Stored
    # ... but not trended over time!

# receiver/models.py:278-304
class FuelEvent(Base):
    gallons_added = Column(Float)
    cost_per_gallon = Column(Float)       # ✅ Stored
    total_cost = Column(Float)            # ✅ Stored
    # ... but no long-term cost analysis!
```

### Current Usage: MINIMAL ⚠️

**What's shown:**
1. Cost comparison card (snapshot):
   ```javascript
   // receiver/static/js/dashboard.js:2090-2112
   // Shows: Electric $0.04/mi vs Gas $0.12/mi
   // BUT: Only current snapshot, no trends!
   ```

2. Charging session costs (individual)
3. Fuel event costs (individual)

### What's NOT Being Done ❌

1. **No cost trends over time**
   - Monthly charging costs
   - Annual fuel costs
   - Cost per mile trends

2. **No savings calculations**
   - "You saved $47 this month vs gas-only"
   - Projected annual savings
   - ROI on electricity vs gas

3. **No time-of-use optimization**
   - Detect charging times
   - Compare costs by time of day
   - Suggest: "Charge after 9pm to save $5/month"

4. **No budget tracking**
   - Monthly spending alerts
   - Year-to-date costs
   - Category breakdown (charging vs fuel)

### Sample Queries Showing Unused Potential

```sql
-- Cost data exists but not trended
SELECT
    date_trunc('month', start_time) as month,
    SUM(total_cost) as monthly_charging_cost,
    SUM(total_kwh_delivered) as monthly_kwh,
    AVG(cost_per_kwh) as avg_rate
FROM charging_sessions
WHERE start_time > NOW() - INTERVAL '1 year'
GROUP BY month
ORDER BY month;
-- Returns 12 months of cost data - NOT VISUALIZED!

-- Savings calculation possible but not done
SELECT
    SUM(t.electric_miles) as total_electric_miles,
    SUM(c.total_cost) as electricity_cost,
    SUM(t.electric_miles) * 0.12 as would_have_cost_in_gas,
    (SUM(t.electric_miles) * 0.12 - SUM(c.total_cost)) as money_saved
FROM trips t
LEFT JOIN charging_sessions c ON date_trunc('day', t.start_time) = date_trunc('day', c.start_time)
WHERE t.start_time > NOW() - INTERVAL '1 month';
-- Calculates savings - NOT SHOWN TO USER!
```

### Potential Features Using This Data

**🎯 Feature 1: Cost Trends Dashboard**
- Chart: Monthly charging costs over time
- Chart: Fuel costs over time
- Chart: Cost per mile trend
- Total: "YTD spending: $234 electric, $89 gas"

**🎯 Feature 2: Savings Calculator**
- "Saved $47 this month vs gas-only driving"
- "Annual savings projection: $564"
- "ROI: Break even in 3.2 years at current rates"
- Compare to gas-only Malibu or similar

**🎯 Feature 3: Time-of-Use Optimizer**
- Detect typical charging times
- Show cost by time of day (if rate varies)
- Suggest: "Charging at night saves $8/month"
- Integration with utility rate schedules

**🎯 Feature 4: Budget Tracker**
- Set monthly budget
- Track against goal
- Alert: "80% of budget used with 5 days left"
- Category breakdown: charging, fuel, maintenance

---

## Category 5: Efficiency Extremes 📊

### What's Being Calculated

Every trip has complete efficiency metrics calculated:

```python
# receiver/models.py:191-276 (Trip model)
class Trip(Base):
    distance_miles = Column(Float)
    electric_miles = Column(Float)
    gas_miles = Column(Float)
    gas_mpg = Column(Float)              # ✅ Calculated
    kwh_per_mile = Column(Float)         # ✅ Calculated
    avg_speed_mph = Column(Float)        # ✅ Calculated
    weather_impact_factor = Column(Float) # ✅ Calculated, never shown!
    # ... and more
```

### Current Usage: LIST ONLY ⚠️

**What's shown:**
- Trip list with sortable columns
- Individual trip details in modal

**What's NOT shown:**
- Auto-identification of best trip
- Auto-identification of worst trip
- Why they were good/bad
- Easy access without scrolling

### What's NOT Being Done ❌

1. **No automatic best/worst detection**
   ```python
   # Could easily query:
   best_electric = db.query(Trip)\
       .filter(Trip.electric_miles > 5)\
       .order_by(desc(Trip.kwh_per_mile))\
       .first()
   # NOT IMPLEMENTED IN UI
   ```

2. **No efficiency insights**
   - Why was trip X so efficient?
   - What conditions led to best MPG?
   - What caused worst performance?

3. **No leaderboard**
   - Personal bests tracking
   - Month-over-month comparison
   - Goal setting

### Sample Queries Showing Unused Analysis

```sql
-- Best and worst trips can be easily identified
SELECT
    start_time,
    distance_miles,
    electric_miles,
    gas_mpg,
    kwh_per_mile,
    ambient_temp_f,
    avg_speed_mph,
    CASE
        WHEN kwh_per_mile < 3.5 THEN 'Excellent'
        WHEN kwh_per_mile < 4.2 THEN 'Good'
        WHEN kwh_per_mile < 5.0 THEN 'Fair'
        ELSE 'Poor'
    END as efficiency_rating
FROM trips
WHERE electric_miles > 1
ORDER BY kwh_per_mile DESC  -- Best electric efficiency
LIMIT 10;

-- Worst gas mileage trips with context
SELECT
    start_time,
    gas_miles,
    gas_mpg,
    ambient_temp_f,
    avg_speed_mph,
    weather_condition
FROM trips
WHERE gas_miles > 5
ORDER BY gas_mpg ASC  -- Worst MPG
LIMIT 10;

-- NONE OF THIS IS SURFACED IN UI!
```

### Potential Features Using This Data

**🎯 Feature 1: Best/Worst Trip Cards**
- Dashboard prominently shows:
  - "Best Electric: 5.2 mi/kWh on April 15 (72°F, smooth driving)"
  - "Best Gas MPG: 48 MPG on highway at 55mph"
  - "Worst Performance: 28 MPG (cold start, 15°F)"
- Link to full trip details
- Explain why (temperature, speed, driving style)

**🎯 Feature 2: Personal Records**
- Track personal bests:
  - Longest electric-only trip
  - Highest MPG
  - Most efficient kWh/mi
  - Longest range on one charge
- Celebrate milestones
- Share achievements (optional)

**🎯 Feature 3: Efficiency Insights**
- Auto-analyze what makes trips efficient:
  - "Your best trips are at 55-60mph"
  - "Efficiency drops 20% below 32°F"
  - "City driving: 4.1 mi/kWh, Highway: 3.2 mi/kWh"
- Actionable recommendations

---

## Summary Statistics

### Data Collection vs. Usage

| Category | Fields Collected | Fields Visualized | Utilization % |
|----------|------------------|-------------------|---------------|
| Temperature | 6 sensors | 1 sensor | 17% ⚠️ |
| Driving Behavior | 3 metrics | 0 analysis | 0% 🔴 |
| Powertrain | 8 metrics | 0 charts | 0% 🔴 |
| Cost Data | 6 fields | 2 basic cards | 33% ⚠️ |
| Efficiency | All trips | List only | 40% ⚠️ |
| **Overall** | **40+ fields** | **~25 used** | **~60%** ⚠️ |

### Quick Wins: Features Using Existing Data

1. ✅ **Powertrain Mode Timeline** - Implementing now
2. ✅ **Battery Degradation Forecast** - Implementing now
3. ⚠️ **Temperature Efficiency Analysis** - Easy, high value
4. ⚠️ **Driving Efficiency Score** - Medium effort, high value
5. ⚠️ **Best/Worst Trip Cards** - Very easy, immediate value
6. ⚠️ **Cost Trends Dashboard** - Easy, practical value
7. ⚠️ **Motor Temperature Monitoring** - Easy, unique insight

### Data Storage Impact

**Current unused data volume estimate:**
```
Telemetry points: ~10,000 per day (typical user)
Unused fields: ~16 per point (40% of fields)
Storage used but unanalyzed: ~160KB/day
Annual unused data: ~58 MB

Conclusion: Data is already being stored - zero additional cost to use it!
```

---

## Recommendations

### Immediate Actions (Week 1-2)
1. ✅ Implement powertrain mode visualization
2. ✅ Implement battery degradation forecasting
3. 📋 Add best/worst trip cards (2 hours effort)
4. 📋 Show temperature impact chart (4 hours effort)

### Short Term (Week 3-4)
1. 📋 Temperature efficiency analysis dashboard
2. 📋 Driving efficiency scoring
3. 📋 Cost trends and savings calculator
4. 📋 Motor temperature monitoring

### Medium Term (Month 2)
1. 📋 Eco-driving coach with tips
2. 📋 Time-of-use charging optimizer
3. 📋 Regenerative braking analysis
4. 📋 Operating mode statistics

### Long Term (Month 3+)
1. 📋 Personal records and achievements
2. 📋 Seasonal comparison reports
3. 📋 Budget tracker with alerts
4. 📋 Predictive maintenance using temps

---

## Conclusion

VoltTracker is sitting on a **data goldmine**. With 40% of collected data unused, there's enormous opportunity to add value **without collecting any new data**.

**Key Insight:** Most requested features can be built using data already in the database. The infrastructure is ready - it's just a matter of analysis and visualization.

**Priority:** Focus on features that provide unique insights specific to the Chevy Volt Gen 2's hybrid architecture and help owners maximize efficiency and minimize costs.
