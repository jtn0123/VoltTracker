# VoltTracker Logging Implementation Plan
## Complete Implementation of loggingsucks.com Best Practices

**Date:** 2026-01-06
**Goal:** Implement all production-grade logging patterns from loggingsucks.com

---

## ✅ PHASE 1: COMPLETED (Foundational Patterns)

### Wide Events / Canonical Log Lines ✅
- **Status:** IMPLEMENTED in `receiver/utils/wide_events.py`
- **What we have:** Single comprehensive event per operation
- **Evidence:** WideEvent class with context dict, single emit() call
- **Validation:** ✅ Passes loggingsucks.com core pattern

### Tail Sampling ✅
- **Status:** IMPLEMENTED
- **What we have:** Smart sampling via `should_emit()` method
  - Always logs errors (`success=False`)
  - Always logs slow requests (>1000ms default)
  - Always logs critical business events (trip_created, gas_mode_entered)
  - Samples 5% of fast successful requests
- **Validation:** ✅ Matches loggingsucks.com tail sampling pattern

### High-Cardinality Data ✅
- **Status:** IMPLEMENTED
- **What we have:** session_id, trip_id, request_id, trace_id in every event
- **Validation:** ✅ High-cardinality identifiers present

### Performance Breakdown ✅
- **Status:** IMPLEMENTED
- **What we have:** `timer()` context manager for granular timing
- **Evidence:** Tracks parse_ms, db_query_ms, db_insert_ms, fetch_weather_ms
- **Validation:** ✅ Provider-specific latencies tracked

### Feature Flags ✅
- **Status:** IMPLEMENTED
- **What we have:** `add_feature_flags()` method, Config.FEATURE_* variables
- **Evidence:** weather_integration, enhanced_route_detection, predictive_range
- **Validation:** ✅ Feature flag states captured for correlation

### Configurable Sampling ✅
- **Status:** IMPLEMENTED
- **What we have:** Environment variables for cost control
  - `LOG_SAMPLE_TELEMETRY=0.05` (5%)
  - `LOG_SAMPLE_TRIP=1.0` (100% - critical)
  - `LOG_SLOW_THRESHOLD_MS=1000`
- **Validation:** ✅ Cost optimization via configurable sampling

---

## ✅ PHASE 2: COMPLETED (Advanced Patterns)

### Error Code Taxonomy ✅
- **Status:** IMPLEMENTED in `receiver/utils/error_codes.py`
- **What we have:** 30+ structured error codes (E001-E599)
  - Categories: validation, external_api, database, parsing, business_logic, system
  - Each code has severity, description, alert flag
- **Validation:** ✅ Structured error details with provider-specific codes

### Enhanced WideEvent Error Support ✅
- **Status:** IMPLEMENTED
- **What we have:** `add_error()` supports StructuredError with auto-extraction
- **Validation:** ✅ Error type, code, message, retriability tracked

### User/Vehicle Context Method ✅
- **Status:** PARTIALLY IMPLEMENTED
- **What we have:** `add_vehicle_context()` method exists
- **What's missing:** **NOT ACTUALLY CALLED IN ROUTES** ⚠️
- **Validation:** ⚠️ Method exists but unused - NEEDS INTEGRATION

### Service Boundary Events ✅
- **Status:** IMPLEMENTED for Weather API
- **What we have:** Dedicated `external_api_weather` events with:
  - Attempt counts, latency per attempt, status codes
  - Error codes for timeouts, connection failures
  - Always emitted (100% sampling)
- **Validation:** ✅ Service boundary tracking for external APIs

---

## ❌ PHASE 3: MISSING CRITICAL PATTERNS

### 1. Service Metadata (CRITICAL MISSING) ❌
**loggingsucks.com requirement:** Every event must include service name, version, deployment_id, region

**What we're missing:**
- ❌ Service name not in events
- ❌ Application version not tracked
- ❌ Deployment ID not tracked
- ❌ Region/environment not tracked

**Impact:** Cannot correlate issues across deployments, cannot identify version-specific bugs

**Implementation Plan:**
```python
# In WideEvent.__init__():
self.context.update({
    "service": "volttracker",
    "version": Config.APP_VERSION,  # Need to add to config
    "deployment_id": Config.DEPLOYMENT_ID,  # From env var
    "environment": Config.ENVIRONMENT,  # dev/staging/prod
    "region": Config.REGION,  # e.g., "us-east-1" or "local"
})
```

---

### 2. Progressive Context Enrichment (CRITICAL MISSING) ❌
**loggingsucks.com requirement:** Enrich events throughout request lifecycle, not just at initialization

**What we're missing:**
- ❌ Events initialized but not enriched progressively
- ❌ User/vehicle context method exists but NEVER CALLED
- ❌ Business metrics added late, not throughout execution
- ❌ No middleware pattern to attach event to request context

**Current problem:**
```python
# We do this (initialization only):
event = WideEvent("telemetry_upload")
event.add_context(method=request.method)
# ... process request ...
event.emit()

# loggingsucks.com wants this (progressive enrichment):
event = WideEvent("telemetry_upload")
event.add_context(method=request.method)
event.add_user_context(...)  # Add as we learn about user
event.add_vehicle_context(...)  # Add as we query trip data
event.add_business_metric("trip_created", True)  # Add during processing
event.add_technical_metric("cache_hit", True)  # Add during execution
event.emit()
```

**Implementation Plan:**
1. Add vehicle context enrichment to telemetry routes
2. Add user/account context to trip finalization
3. Query aggregate statistics (total_trips, total_miles) and add to context
4. Calculate battery health metrics and add to context

---

### 3. Comprehensive Business Context (MISSING) ❌
**loggingsucks.com requirement:** User subscription tier, account age, lifetime value

**What we're missing:**
- ❌ Account age (days since first trip)
- ❌ Lifetime statistics (total trips, total miles driven)
- ❌ Battery health trends (capacity degradation over time)
- ❌ User tier/classification (heavy user, occasional user, new user)

**VoltTracker equivalent mapping:**
- **User subscription tier** → Vehicle usage tier (heavy/moderate/light based on trip count)
- **Account age** → Days since first trip
- **Lifetime value** → Total miles driven, total trips completed
- **Cart details** → Current trip statistics (miles, kWh used, efficiency)

**Implementation Plan:**
```python
# Calculate and add to every event:
event.add_vehicle_context(
    total_trips=get_total_trip_count(db),
    total_miles=get_total_miles_driven(db),
    account_age_days=get_days_since_first_trip(db),
    battery_capacity_kwh=get_latest_battery_capacity(db),
    avg_kwh_per_mile=get_lifetime_avg_efficiency(db),
    usage_tier=classify_user_tier(trip_count),  # "heavy"/"moderate"/"light"
)
```

---

### 4. Request Context Pattern (MISSING) ❌
**loggingsucks.com requirement:** Attach event to request context for easy access throughout handlers

**What we're missing:**
- ❌ No request context storage for WideEvent
- ❌ Each function creates its own event (no sharing)
- ❌ Cannot enrich same event from helper functions

**Implementation Plan:**
```python
# Flask middleware pattern:
@app.before_request
def attach_wide_event():
    g.wide_event = WideEvent(operation=request.endpoint)
    g.wide_event.add_context(
        method=request.method,
        path=request.path,
        remote_addr=request.remote_addr,
    )

# In route handlers:
def upload_telemetry():
    event = g.wide_event  # Reuse same event
    event.add_context(session_id=data["session_id"])
    # ... later in processing ...
    event.add_vehicle_context(...)
    # ... at completion ...
    event.emit()
```

---

### 5. Deployment/Environment Context (MISSING) ❌
**loggingsucks.com requirement:** Region, availability zone, environment tagging

**What we're missing:**
- ❌ No environment indicator (dev/staging/prod)
- ❌ No deployment timestamp
- ❌ No region/location tracking
- ❌ No container/instance ID

**Implementation Plan:**
```python
# Add to Config:
ENVIRONMENT = os.getenv("ENVIRONMENT", "development")
DEPLOYMENT_ID = os.getenv("DEPLOYMENT_ID", f"local-{socket.gethostname()}")
DEPLOYMENT_TIMESTAMP = os.getenv("DEPLOYMENT_TIMESTAMP", datetime.utcnow().isoformat())
REGION = os.getenv("REGION", "local")

# Auto-add to every WideEvent:
self.context["deployment"] = {
    "environment": Config.ENVIRONMENT,
    "deployment_id": Config.DEPLOYMENT_ID,
    "deployed_at": Config.DEPLOYMENT_TIMESTAMP,
    "region": Config.REGION,
}
```

---

### 6. Enhanced Tail Sampling for User Tiers (MISSING) ❌
**loggingsucks.com requirement:** Always sample VIP users, apply different rates per tier

**What we're missing:**
- ❌ No user tier-based sampling
- ❌ Same sampling rate for all users
- ❌ Cannot differentiate heavy users from new users in sampling

**Implementation Plan:**
```python
def should_emit(self, sample_rate: float = 0.05, slow_threshold_ms: float = 1000) -> bool:
    # Always log errors
    if not self.context.get("success", True):
        return True

    # Always log slow requests
    if self.context.get("duration_ms", 0) > slow_threshold_ms:
        return True

    # Always log critical business events
    business_metrics = self.context.get("business_metrics", {})
    if any(business_metrics.get(event) for event in CRITICAL_EVENTS):
        return True

    # NEW: Always log heavy users (100+ trips)
    vehicle_context = self.context.get("vehicle_context", {})
    if vehicle_context.get("usage_tier") == "heavy":
        return True

    # NEW: Higher sampling for new users (first 30 days)
    if vehicle_context.get("account_age_days", 999) < 30:
        sample_rate = 0.25  # 25% for new users

    # Sample successful fast requests
    return random.random() < sample_rate
```

---

## 🔧 PHASE 3: IMPLEMENTATION TASKS

### Task 1: Add Service Metadata
- [ ] Add APP_VERSION to Config (read from version.txt or env var)
- [ ] Add DEPLOYMENT_ID to Config (env var or hostname)
- [ ] Add ENVIRONMENT to Config (dev/staging/prod)
- [ ] Add REGION to Config (deployment region)
- [ ] Auto-inject service metadata in WideEvent.__init__()
- [ ] Validate service metadata appears in all events

### Task 2: Implement Progressive Context Enrichment
- [ ] Add get_vehicle_statistics() helper to calculate total trips/miles
- [ ] Add get_account_age() helper to calculate days since first trip
- [ ] Add classify_usage_tier() helper to determine heavy/moderate/light
- [ ] Call add_vehicle_context() in telemetry routes with trip statistics
- [ ] Call add_vehicle_context() in trip service with lifetime statistics
- [ ] Validate context enrichment appears in logged events

### Task 3: Add Comprehensive Business Context
- [ ] Create database queries for lifetime statistics
- [ ] Calculate account age from first trip timestamp
- [ ] Determine usage tier classification (heavy: 100+, moderate: 20-99, light: <20)
- [ ] Add battery health tracking (capacity degradation over time)
- [ ] Integrate business context into all major operations
- [ ] Validate business context aids debugging

### Task 4: Implement Request Context Pattern (Optional)
- [ ] Create Flask before_request hook to initialize WideEvent
- [ ] Store event in g.wide_event for request duration
- [ ] Update routes to reuse g.wide_event instead of creating new ones
- [ ] Ensure event emitted in after_request or teardown_request
- [ ] Validate single event per request with full enrichment

### Task 5: Add Deployment Context
- [ ] Add deployment metadata to Config
- [ ] Auto-inject in WideEvent initialization
- [ ] Validate deployment context in logs
- [ ] Test environment differentiation (dev vs prod)

### Task 6: Enhanced Tier-Based Sampling
- [ ] Implement usage_tier classification
- [ ] Add tier-based sampling logic to should_emit()
- [ ] Always sample heavy users (100%)
- [ ] Increase sampling for new users (25%)
- [ ] Validate sampling rates per tier

---

## 📊 VALIDATION CHECKLIST

### loggingsucks.com Production Readiness
- [x] One wide event per request per service
- [⚠️] 50+ contextual fields captured (currently ~20, need 30 more)
- [x] High-cardinality user/business identifiers included
- [❌] Service metadata (name, version, deployment) present ← **CRITICAL MISSING**
- [x] Error details include provider-specific codes
- [x] Feature flag states captured for correlating issues
- [x] Tail sampling implemented for cost control
- [❌] VIP/heavy user requests always sampled ← **MISSING**
- [x] Request duration tracking at service boundary
- [x] Upstream/downstream provider latencies separated
- [⚠️] Event enriched throughout request lifecycle ← **PARTIALLY**
- [x] Event emitted once at completion
- [x] High-dimensionality queryable by log storage system

### Anti-Patterns Avoided
- [x] ✅ Not logging multiple lines per request
- [x] ✅ Using structured JSON logging
- [x] ✅ Including high-cardinality data
- [❌] ❌ Missing service metadata (service-blind logging)
- [⚠️] ⚠️ Incomplete business context
- [x] ✅ Logging outcomes, not implementation details
- [x] ✅ Using tail sampling, not 100%

### Sophisticated Queries Enabled
After full implementation, we should be able to query:
- ✅ "Show all errors for session X"
- ⚠️ "Show all trips from heavy users in last 30 days" (need usage_tier)
- ❌ "Show all errors in deployment Y" (need deployment_id)
- ⚠️ "Show all new users with trip errors" (need account_age)
- ✅ "Show all slow trip finalizations (>2s)"
- ✅ "Show all weather API failures with timeouts"
- ❌ "Compare error rates between prod and staging" (need environment)

---

## 📈 IMPACT ANALYSIS

### What's Working Well (Phases 1 & 2)
1. **Wide Events:** Single comprehensive event per operation ✅
2. **Tail Sampling:** Smart cost control with error/slow preservation ✅
3. **Error Taxonomy:** Structured error codes for better alerting ✅
4. **Service Boundaries:** External API tracking with full metrics ✅
5. **Performance Tracking:** Granular latency breakdown ✅

### Critical Gaps (Phase 3)
1. **Service Metadata:** Cannot correlate issues across deployments ❌
2. **User Context:** No lifetime statistics or usage tier classification ❌
3. **Progressive Enrichment:** Context not added throughout request lifecycle ⚠️
4. **Tier-Based Sampling:** All users sampled equally (missing VIP priority) ❌
5. **Environment Tagging:** Cannot differentiate dev/staging/prod in logs ❌

### Business Value of Phase 3
- **Debugging Speed:** "Which deployment introduced this bug?" (service metadata)
- **User Segmentation:** "Are heavy users affected more than new users?" (usage tier)
- **Battery Health:** "Do degraded batteries correlate with poor efficiency?" (lifetime stats)
- **Cost Optimization:** "Reduce sampling for light users, increase for heavy users" (tier sampling)
- **Deployment Safety:** "Did prod deployment cause error spike?" (environment tags)

---

## 🎯 RECOMMENDED NEXT STEPS

### Priority 1 (Critical - Blocking Production Readiness)
1. ✅ **Add service metadata** (service, version, deployment_id, environment)
2. ✅ **Implement vehicle context enrichment** (actually call add_vehicle_context)
3. ✅ **Add lifetime statistics** (total trips, miles, account age)

### Priority 2 (High Value)
4. ✅ **Implement usage tier classification** (heavy/moderate/light users)
5. ✅ **Add tier-based sampling** (always sample heavy users)
6. ⚠️ **Add battery health tracking** (capacity degradation)

### Priority 3 (Nice to Have)
7. ⚠️ **Request context pattern** (Flask middleware for progressive enrichment)
8. ⚠️ **Deployment timestamps** (when was this version deployed)
9. ⚠️ **Instance/container ID** (for debugging specific instances)

---

## 📝 CONCLUSION

**Current Status:** 70% compliant with loggingsucks.com best practices

**Strengths:**
- Excellent foundational implementation (Wide Events, Tail Sampling, Error Taxonomy)
- Strong performance tracking and service boundary events
- Good cost optimization with configurable sampling

**Gaps:**
- Missing critical service metadata (deployment correlation impossible)
- User/vehicle context method exists but unused (context enrichment incomplete)
- No usage tier classification or tier-based sampling
- Limited lifetime statistics and business context

**Next Actions:**
Execute Phase 3 implementation tasks to achieve 100% compliance and unlock full debugging power of loggingsucks.com patterns.
