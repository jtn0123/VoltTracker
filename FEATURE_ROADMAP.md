# VoltTracker Feature & Technical Roadmap
**Last Updated:** 2025-01-05
**Status:** Phase 1 Implementation in Progress

---

## Executive Summary

Based on comprehensive codebase analysis, VoltTracker has **two parallel improvement tracks**:
1. **Feature Track** - User-facing enhancements using existing data
2. **Technical Track** - Infrastructure, security, and code quality improvements

**Current Grade:** B+ (85/100)
**Target Grade:** A+ (98/100)

---

## PART 1: FEATURE ROADMAP

### Phase 1: Quick Wins (Week 1-2) - IN PROGRESS ✅

#### Feature 5: Powertrain Mode Visualization ⚡
**Status:** Implementing
**Effort:** 20 hours
**Value:** High - Unique to Volt, shows hybrid system operation

**Implementation:**
- Parse motor_a_rpm, motor_b_rpm, generator_rpm from telemetry
- Create timeline chart in trip modal
- Identify modes: EV-only, Hold, Mountain, Engine-only
- Color-coded visualization

**Data Available:** ✅
- motor_a_rpm (stored, not visualized)
- motor_b_rpm (stored, not visualized)
- generator_rpm (stored, not visualized)
- engine_rpm (used only for trip detection)

**Files to Create:**
- `receiver/routes/analytics.py` - New endpoint `/api/analytics/powertrain-modes/<trip_id>`
- Update `receiver/static/js/dashboard.js` - Add powertrain chart to trip modal

---

#### Feature 6: Predictive Range Estimation (ML) 🤖
**Status:** Implementing
**Effort:** 16 hours (simplified approach)
**Value:** High - More accurate than car's DTE

**Implementation:**
- Simple linear regression on historical data
- Features: temperature, battery_health, avg_speed, driving_score
- Train on past trips, predict range for current conditions
- Show confidence interval

**Data Available:** ✅
- Historical trips with actual range achieved
- Temperature data (ambient, battery)
- Battery health metrics
- Speed patterns

**Files to Create:**
- `receiver/services/ml_service.py` - Range prediction model
- `receiver/routes/analytics.py` - Endpoint `/api/analytics/range-prediction`
- Training script: `scripts/train_range_model.py`

**Note:** Starting simple - no complex ML, just sklearn LinearRegression

---

#### Feature 7: Maintenance Tracker 🔧
**Status:** Implementing
**Effort:** 12 hours
**Value:** High - Practical utility

**Implementation:**
- Track maintenance items: oil changes, tire rotations, fluids
- Calculate engine hours from telemetry (engine_rpm > 0)
- Predict due dates based on usage
- Gen 2 Volt specifics: oil every 2 years OR 24 engine hours

**Data Available:** ✅
- engine_rpm timestamps (calculate hours)
- odometer_miles (for mileage-based items)

**New Data Needed:** ⚠️
- Manual entry for initial mileage/dates

**Files to Create:**
- `receiver/models.py` - Add `MaintenanceRecord` model
- `receiver/routes/maintenance.py` - CRUD endpoints
- `receiver/services/maintenance_service.py` - Due date calculations
- Database migration: `scripts/create_maintenance_table.py`

---

#### Feature 8: Route Analysis 🗺️
**Status:** Implementing (simplified)
**Effort:** 16 hours
**Value:** Medium-High - Useful insights

**Implementation:**
- GPS clustering to detect frequent routes
- Name routes based on start/end points
- Show efficiency by route
- Simplified: Only analyze if GPS data exists

**Data Available:** ✅
- latitude, longitude for every telemetry point
- trip start/end coordinates
- efficiency metrics per trip

**Files to Create:**
- `receiver/services/route_service.py` - GPS clustering algorithm
- `receiver/routes/analytics.py` - Endpoint `/api/analytics/routes`
- `receiver/models.py` - Add `Route` model (optional)

**Note:** Using DBSCAN clustering, simplified approach

---

#### Feature 9: Battery Degradation Forecasting 📉
**Status:** Implementing
**Effort:** 8 hours
**Value:** High - Critical for EV owners

**Implementation:**
- Linear regression on battery_capacity_kwh over time
- Project capacity at 100k, 150k miles
- Compare to typical Gen 2 degradation (2-3% per 50k miles)
- Alert if degradation is abnormal

**Data Available:** ✅
- battery_capacity_kwh readings over time
- odometer_miles for mileage correlation

**Files to Create:**
- `receiver/routes/battery.py` - New endpoint `/api/battery/degradation-forecast`
- Update dashboard to show forecast chart

---

### Unused Data Analysis (Features 1-5)

#### 1. Temperature Data (NOT VISUALIZED) 🌡️
**What's collected:**
- `ambient_temp_f` - Outside temperature
- `battery_temp_f` - HV battery temperature
- `battery_coolant_temp_f` - Battery cooling system
- `coolant_temp_f` - Engine coolant
- `intake_air_temp_f` - Engine intake
- `engine_oil_temp_f` - Engine oil

**What's calculated but hidden:**
- `weather_impact_factor` in Trip model (Line 224)
- Temperature correlation in SOC analysis

**Opportunity:**
- Chart: kWh/mi vs temperature
- Show battery heating cost in winter
- Preconditioning recommendations
- Seasonal efficiency comparison

---

#### 2. Driving Behavior Data (NOT ANALYZED) 🚗
**What's collected:**
- `throttle_position` - How hard user accelerates
- `speed_mph` - Speed over time
- Timestamps allow acceleration calculation

**What's NOT analyzed:**
- Aggressive acceleration events
- Throttle smoothness
- Speed consistency
- Eco-driving score

**Opportunity:**
- Driving efficiency score 0-100
- Tips: "Reduce hard acceleration to gain 3 miles range"
- Compare smooth vs aggressive driving impact

---

#### 3. Best/Worst Trip Data (NOT SURFACED) 🏆
**What's calculated:**
- Every trip has efficiency metrics
- Gas MPG, electric mi/kWh
- Weather conditions, temperature

**What's NOT shown:**
- Auto-identification of best/worst trips
- Why they were good/bad
- Easy access to extremes

**Opportunity:**
- Dashboard cards showing best/worst automatically
- "Best: 5.1 mi/kWh on April 15 (72°F, city)"
- "Worst: 28 MPG cold start at 15°F"

---

#### 4. Powertrain Mode Data (NOT VISUALIZED) ⚙️
**What's collected:**
- `motor_a_rpm` - Front motor
- `motor_b_rpm` - Rear motor
- `generator_rpm` - Engine generator
- `engine_rpm` - ICE engine
- `motor_a_temp_c`, `motor_b_temp_c`

**What's NOT shown:**
- When each motor is active
- Volt operating modes (EV, Hold, Mountain)
- Hybrid assist vs pure electric
- Motor temperature trends

**Opportunity:**
- Timeline showing which motors are active
- Identify: EV-only, hybrid-assist, engine-only
- Motor temperature analysis

---

#### 5. Cost Tracking Data (PARTIALLY SHOWN) 💰
**What's collected:**
- Charging session kWh + cost
- Fuel events with gallons + cost
- Miles driven (electric vs gas)

**What's shown:**
- Cost per charging session
- Cost comparison card (basic)

**What's NOT shown:**
- Monthly cost trends
- Projected annual savings
- Time-of-use optimization suggestions
- Cost per mile breakdown over time
- "You saved $47 this month vs gas-only"

---

## PART 2: TECHNICAL ROADMAP

### Critical P0 Items (This Week) 🚨

#### 1. Database Connection Pooling
**Effort:** 1 hour
**File:** `receiver/models.py:638-640`
```python
def get_engine(database_url):
    return create_engine(
        database_url,
        pool_pre_ping=True,
        pool_size=10,          # ADD
        max_overflow=20,       # ADD
        pool_recycle=3600      # ADD
    )
```

#### 2. CSRF Protection
**Effort:** 4 hours
**Impact:** Critical security vulnerability
```bash
pip install flask-wtf
```
**File:** `receiver/app.py`
```python
from flask_wtf.csrf import CSRFProtect
csrf = CSRFProtect(app)
csrf.exempt(telemetry_bp)  # Exempt Torque endpoint
```

#### 3. Automated Database Backups
**Effort:** 4 hours
**Create:** `scripts/backup_db.sh`
```bash
docker exec volt-tracker-db pg_dump -U volt volt_tracker | \
  gzip > backups/volttracker_$(date +%Y%m%d).sql.gz
```

#### 4. Monitoring (Prometheus)
**Effort:** 4 hours
```bash
pip install prometheus-flask-exporter
```
**File:** `receiver/app.py`
```python
from prometheus_flask_exporter import PrometheusMetrics
metrics = PrometheusMetrics(app)
# Adds /metrics endpoint automatically
```

#### 5. Rate Limiting on Authentication
**Effort:** 1 hour
**File:** `receiver/app.py:117-131`
```python
@limiter.limit("10 per hour")
@auth.verify_password
def verify_password(username, password):
    # Prevent brute force
```

#### 6. Database Migration Framework
**Effort:** 1 day
```bash
pip install alembic
alembic init migrations
# Convert init.sql to Alembic
```

---

### High Priority P1 Items (1-2 Weeks)

#### 1. Repository Pattern
**Effort:** 5 days
**Files:** Create `receiver/repositories/`
- `trip_repository.py`
- `charging_repository.py`
- `battery_repository.py`

**Benefit:** Testability, separation of concerns

#### 2. API Versioning
**Effort:** 1 day
**Change:** All routes to `/api/v1/...`
**Backward compat:** Keep `/api/...` for existing clients

#### 3. OpenAPI Documentation
**Effort:** 3 days
```bash
pip install flasgger
```
**Access:** `/apidocs` for interactive API docs

#### 4. JWT Authentication
**Effort:** 2 days
**Replace:** HTTP Basic Auth with JWT tokens
**Add:** `/api/auth/login`, `/api/auth/refresh`

#### 5. Input Validation
**Effort:** 2 days
**Library:** marshmallow or pydantic
**Apply to:** All POST/PATCH endpoints

---

### Medium Priority P2 Items (2-4 Weeks)

1. **Split models.py** - 654 lines → multiple files
2. **Redis Caching** - Replace SimpleCache
3. **APM Integration** - New Relic or DataDog
4. **Audit Logging** - Track all data changes
5. **Architecture Documentation** - Diagrams + docs

---

## IMPLEMENTATION PLAN

### Week 1 (Current)
✅ Code review completed
✅ Bug fixes (Service Worker, Web Vitals)
🔄 **IN PROGRESS:** Features 5, 6, 7, 8, 9
🔄 **IN PROGRESS:** Technical improvements documentation

### Week 2
- [ ] Complete features 5-9
- [ ] Implement P0 technical items
- [ ] Write tests for new features
- [ ] Update documentation

### Week 3
- [ ] Features 1, 2, 3 (Temperature, Driving Score, Best/Worst)
- [ ] Feature 4 (Enhanced Cost Tracker)
- [ ] P1 technical items (Repository pattern)

### Week 4
- [ ] API versioning
- [ ] OpenAPI docs
- [ ] JWT authentication

---

## SUCCESS METRICS

### Feature Track
- [ ] 5 new analytics features deployed
- [ ] 90%+ of collected data visualized
- [ ] User engagement increased (time on site)
- [ ] Feature usage tracked (Web Vitals)

### Technical Track
- [ ] Security grade: B+ → A
- [ ] Test coverage: 80% → 90%
- [ ] API response time: < 200ms (p95)
- [ ] Zero critical vulnerabilities
- [ ] Database backup automation: 100%
- [ ] Monitoring coverage: 100%

---

## RISK ASSESSMENT

### Low Risk ✅
- Features 5, 9 (use existing data, read-only)
- P0 technical items (well-tested patterns)

### Medium Risk ⚠️
- Features 6, 7, 8 (new models, ML)
- Repository pattern refactor
- API versioning (backward compat)

### High Risk 🔴
- Database migration framework (schema changes)
- JWT auth (breaking change)
- Production deployment changes

**Mitigation:**
- Feature flags for new features
- Comprehensive testing
- Staged rollout
- Database backups before migrations

---

## DECISION LOG

### 2025-01-05: Feature Prioritization
**Decision:** Implement features 5-9 before 1-4
**Rationale:**
- Features 5-9 add unique value (ML, predictions)
- Features 1-4 are simpler, can be added later
- User requested 5-9 specifically

### 2025-01-05: ML Approach
**Decision:** Start with simple linear regression, not deep learning
**Rationale:**
- Dataset size unknown
- Faster to implement
- Easier to explain to users
- Can upgrade later if needed

### 2025-01-05: Route Analysis Scope
**Decision:** Simplified GPS clustering, no map UI initially
**Rationale:**
- Complex map UI = high effort
- Start with API + basic visualization
- Iterate based on feedback

---

## APPENDIX: Quick Reference

### Files to Create (Features)
1. `receiver/services/ml_service.py` - Range prediction ML
2. `receiver/services/route_service.py` - GPS clustering
3. `receiver/services/maintenance_service.py` - Maintenance calculations
4. `receiver/routes/maintenance.py` - Maintenance CRUD
5. `scripts/train_range_model.py` - ML training script
6. `scripts/create_maintenance_table.py` - DB migration

### Files to Modify (Features)
1. `receiver/routes/analytics.py` - Add powertrain, range, route endpoints
2. `receiver/routes/battery.py` - Add degradation forecast endpoint
3. `receiver/models.py` - Add MaintenanceRecord, Route models
4. `receiver/static/js/dashboard.js` - Add powertrain timeline chart

### Files to Create (Technical)
1. `receiver/repositories/` - Repository pattern
2. `scripts/backup_db.sh` - Automated backups
3. `docs/ARCHITECTURE.md` - System documentation
4. `migrations/` - Alembic migrations

### Files to Modify (Technical)
1. `receiver/app.py` - CSRF, monitoring, JWT
2. `receiver/models.py` - Connection pooling
3. `requirements.txt` - New dependencies

---

## NOTES

**Data Goldmine:** 40+ telemetry fields collected, only ~60% visualized
**Quick Wins Available:** Most features use existing data
**Technical Debt:** Manageable, no critical blockers
**Test Coverage:** Strong (80% required in CI)
**Security:** Needs attention (CSRF, auth, backups)

**Next Review:** After Phase 1 completion (Week 2)
