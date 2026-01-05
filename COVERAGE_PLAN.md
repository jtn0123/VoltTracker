## Test Coverage Improvement Plan

### Current Status: 56% → Target: 80%+

## Priority 1: Fix Existing Test Files (Immediate +15-20%)

### A. Fix Import Errors in New Service Tests

**Files with import errors:**
1. `test_battery_degradation_service.py` - trying to import `get_current_health` (doesn't exist)
2. `test_maintenance_service.py` - trying to import `add_maintenance_record`, `get_next_due_items`, `get_maintenance_history` (don't exist)
3. `test_range_prediction_service.py` - trying to import `calculate_confidence` (doesn't exist)
4. `test_route_service.py` - trying to import `process_trip_route`, `get_all_routes`, `get_route_stats` (don't exist)

**Action:** Check actual function names in each service and update test imports

```bash
# Check actual exports
grep "^def " receiver/services/battery_degradation_service.py
grep "^def " receiver/services/maintenance_service.py
grep "^def " receiver/services/range_prediction_service.py
grep "^def " receiver/services/route_service.py
```

### B. Fix Model Test Failures

**MaintenanceRecord model tests failing:**
- Uses `odometer_miles` not `mileage`
- Fix in test_models.py lines 790, 914

**Route model tests failing:**
- Uses `avg_distance_miles` not `total_distance_miles`
- Uses `last_traveled` not `last_trip_date`
- `to_dict()` returns nested structure: `{"start": {"lat": ..., "lon": ...}}`
- `trip_count` defaults to 1, not 0
- Fix in test_models.py lines 955-1148

---

## Priority 2: Add Missing Test Coverage (Immediate +15-20%)

### A. Test New Analytics API Endpoints (routes/analytics.py at 21%)
**Missing coverage lines: 38-75, 86-99, 105-116, 130-155, 162-169, 175-183, 190-204, 211-218**

Add tests for:
```python
# In test_new_analytics_endpoints.py - already created but needs to match actual endpoints
- GET /api/analytics/powertrain/<session_id>
- GET /api/analytics/powertrain/summary/<trip_id>
- GET /api/analytics/range-prediction
- GET /api/analytics/maintenance/summary
- GET /api/analytics/maintenance/engine-hours
- GET /api/analytics/routes
- GET /api/analytics/battery/degradation
```

### B. Test Battery/Cell Analysis (routes/battery.py at 32%)
**Missing coverage lines: 62-65, 74-88, 95-100, 123-136, 145-154, 164-223, 238-293**

Add `test_battery_analysis.py`:
```python
def test_cell_voltage_analysis_endpoint()
def test_battery_health_endpoint()
def test_battery_degradation_tracking()
def test_cell_balance_detection()
def test_weak_cell_identification()
```

### C. Test CSV Import (csv_importer.py at 0%)
**ALL 483 LINES UNCOVERED**

Add `test_csv_importer.py`:
```python
def test_import_telemetry_csv()
def test_import_trips_csv()
def test_import_charging_csv()
def test_handle_malformed_csv()
def test_handle_missing_columns()
def test_import_with_date_parsing()
```

### D. Test Exception Handling (exceptions.py at 25%)
**Missing coverage lines: 15-17, 20-22, 35-42, 49-56, 70-79, 86-89, 102-112, 119-126, 133-137, 144-148**

Add `test_exception_handlers.py`:
```python
def test_validation_error_handler()
def test_authentication_error_handler()
def test_not_found_error_handler()
def test_server_error_handler()
def test_custom_exception_rendering()
```

---

## Priority 3: Increase Coverage in Partially Tested Files (+10-15%)

### A. Weather Service (utils/weather.py at 27%)
**Missing coverage lines: 44-80, 104-132, 142-156, 166-184, 189-214, 227-263, 276-300, 306-308**

Expand `test_weather.py` with:
```python
def test_fetch_weather_for_trip()
def test_weather_caching()
def test_api_error_handling()
def test_weather_condition_mapping()
def test_temperature_unit_conversion()
def test_multiple_location_lookup()
```

### B. Export Functionality (routes/export.py at 44%)
**Missing coverage lines: 49, 53, 57, 122, 174-209, 226-379, 390-531**

Add `test_export.py`:
```python
def test_export_trips_csv()
def test_export_charging_csv()
def test_export_telemetry_csv()
def test_export_date_filtering()
def test_export_format_options()
def test_large_dataset_export()
```

### C. Scheduler Jobs (services/scheduler.py at 15%)
**Missing coverage lines: 29, 37-69, 74-125, 130-237, 248-254, 260-262**

Add `test_scheduler_jobs.py`:
```python
def test_close_stale_trips()
def test_detect_charging_sessions()
def test_detect_refueling()
def test_cleanup_old_data()
def test_scheduler_error_handling()
def test_job_execution_timing()
```

---

## Priority 4: Edge Cases & Integration Tests (+5-10%)

### A. Integration Test Improvements

Add `test_end_to_end.py`:
```python
def test_full_trip_workflow()
def test_charging_to_analytics_pipeline()
def test_maintenance_reminder_flow()
def test_route_detection_pipeline()
def test_battery_degradation_tracking_flow()
```

### B. Performance & Load Tests

Add `test_performance.py`:
```python
def test_large_telemetry_batch_processing()
def test_concurrent_trip_tracking()
def test_database_query_performance()
def test_analytics_calculation_speed()
```

---

## Quick Wins for Immediate Coverage Boost

### 1. **Fix the 4 broken test files** (+15-20%)
   - Update function imports to match actual service implementations
   - Fix model field names in tests
   - **Estimated time: 1-2 hours**

### 2. **Add CSV importer tests** (+7%)
   - Goes from 0% to 70%+ coverage
   - **Estimated time: 2 hours**

### 3. **Add exception handler tests** (+6%)
   - Goes from 25% to 80%+ coverage
   - **Estimated time: 1 hour**

### 4. **Complete analytics endpoint tests** (+5%)
   - Routes/analytics.py from 21% to 70%+
   - **Estimated time: 2 hours**

---

## Estimated Coverage After All Improvements

| Component | Current | After Fixes | After P2 | Final |
|-----------|---------|-------------|----------|-------|
| **Services** | 16-25% | 60-70% | 75-85% | 80%+ |
| **Routes** | 21-80% | 70-85% | 80-90% | 85%+ |
| **Utils** | 0-96% | 60-80% | 75-90% | 80%+ |
| **Models** | 93% | 95% | 97% | 98%+ |
| **Overall** | **56%** | **72%** | **82%** | **85%+** |

---

## Recommended Testing Strategy

### Phase 1 (Week 1): Fix & Core Coverage
- Fix broken test imports
- Fix model test field names
- Add CSV importer tests
- Add exception handler tests
- **Target: 72% coverage**

### Phase 2 (Week 2): Service Coverage
- Complete all new service tests
- Add analytics endpoint tests
- Add battery analysis tests
- **Target: 82% coverage**

### Phase 3 (Week 3): Polish & Edge Cases
- Add scheduler tests
- Add export tests
- Add integration tests
- Add performance tests
- **Target: 85%+ coverage**

---

## Commands to Monitor Progress

```bash
# Run tests with coverage report
SECRET_KEY=test pytest tests/ --cov=receiver --cov-report=term-missing --cov-report=html

# View HTML report
open htmlcov/index.html  # or xdg-open on Linux

# Check specific file coverage
SECRET_KEY=test pytest tests/test_battery_degradation_service.py --cov=receiver/services/battery_degradation_service --cov-report=term-missing

# Generate coverage badge
coverage-badge -o coverage.svg
```

---

## Key Metrics to Track

1. **Overall coverage** - Target: 85%+
2. **Critical path coverage** - Target: 95%+ (trip tracking, telemetry ingestion)
3. **New features coverage** - Target: 80%+ (powertrain, range, maintenance, routes, degradation)
4. **Error path coverage** - Target: 70%+ (exception handlers, edge cases)
