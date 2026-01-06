# VoltTracker Development Guidelines

## Testing

- **Test Location**: All tests must be in the `/tests` directory at the project root. Do not create test files inside the `receiver/` package.
- **Test Command**: `SECRET_KEY=test pytest tests/ --cov=receiver --cov-report=term-missing`
- **Coverage Threshold**: Minimum 80% (CI enforced), target 90%+
- **Virtual Environment**: Use `.venv` - activate with `source .venv/bin/activate`
