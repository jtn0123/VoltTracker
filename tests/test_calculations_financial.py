"""
Tests for financial calculations
"""

import pytest
from receiver.calculations.financial import (
    calculate_charging_cost,
    calculate_fuel_cost,
    calculate_electric_cost_per_mile,
    calculate_gas_cost_per_mile,
    calculate_trip_cost,
    calculate_cost_savings_vs_gas_only,
    calculate_payback_period_years,
)


class TestChargingCost:
    """Test charging cost calculations"""

    def test_calculate_charging_cost_typical(self):
        """10 kWh at $0.12/kWh = $1.20"""
        assert calculate_charging_cost(10.0, 0.12) == 1.2

    def test_calculate_charging_cost_expensive_rate(self):
        """15.5 kWh at $0.15/kWh = $2.33"""
        assert calculate_charging_cost(15.5, 0.15) == 2.33

    def test_calculate_charging_cost_none_kwh(self):
        assert calculate_charging_cost(None, 0.12) is None

    def test_calculate_charging_cost_zero_kwh(self):
        assert calculate_charging_cost(0.0, 0.12) is None

    def test_calculate_charging_cost_none_rate(self):
        assert calculate_charging_cost(10.0, None) is None

    def test_calculate_charging_cost_zero_rate(self):
        """Zero rate (free charging) should return None"""
        assert calculate_charging_cost(10.0, 0.0) is None


class TestFuelCost:
    """Test fuel cost calculations"""

    def test_calculate_fuel_cost_typical(self):
        """10 gallons at $3.50 = $35.00"""
        assert calculate_fuel_cost(10.0, 3.50) == 35.0

    def test_calculate_fuel_cost_partial(self):
        """5.5 gallons at $4.00 = $22.00"""
        assert calculate_fuel_cost(5.5, 4.00) == 22.0

    def test_calculate_fuel_cost_none_gallons(self):
        assert calculate_fuel_cost(None, 3.50) is None

    def test_calculate_fuel_cost_zero_gallons(self):
        assert calculate_fuel_cost(0.0, 3.50) is None

    def test_calculate_fuel_cost_none_price(self):
        assert calculate_fuel_cost(10.0, None) is None


class TestCostPerMile:
    """Test cost per mile calculations"""

    def test_calculate_electric_cost_per_mile(self):
        """40 mi using 10 kWh at $0.12 = $0.03/mi"""
        result = calculate_electric_cost_per_mile(40.0, 10.0, 0.12)
        assert result == 0.03

    def test_calculate_electric_cost_per_mile_expensive(self):
        """50 mi using 15 kWh at $0.15 = $0.045/mi"""
        result = calculate_electric_cost_per_mile(50.0, 15.0, 0.15)
        assert result == 0.045

    def test_calculate_electric_cost_per_mile_zero_miles(self):
        assert calculate_electric_cost_per_mile(0.0, 10.0, 0.12) is None

    def test_calculate_gas_cost_per_mile(self):
        """100 mi using 2.5 gal at $3.50 = $0.088/mi"""
        result = calculate_gas_cost_per_mile(100.0, 2.5, 3.50)
        assert result == 0.088

    def test_calculate_gas_cost_per_mile_expensive(self):
        """40 mi using 1 gal at $4.00 = $0.10/mi"""
        result = calculate_gas_cost_per_mile(40.0, 1.0, 4.00)
        assert result == 0.1


class TestTripCost:
    """Test comprehensive trip cost calculations"""

    def test_calculate_trip_cost_mixed(self):
        """30 mi electric + 20 mi gas"""
        result = calculate_trip_cost(
            electric_miles=30,
            electric_kwh=9.0,
            gas_miles=20,
            gas_gallons=0.5,
            electricity_rate=0.12,
            gas_price=3.50
        )
        assert result['electric_cost'] == 1.08
        assert result['gas_cost'] == 1.75
        assert result['total_cost'] == 2.83
        assert result['cost_per_mile'] == 0.057
        assert result['total_miles'] == 50.0

    def test_calculate_trip_cost_electric_only(self):
        """All electric trip"""
        result = calculate_trip_cost(
            electric_miles=40,
            electric_kwh=10.0,
            gas_miles=None,
            gas_gallons=None,
            electricity_rate=0.12,
            gas_price=3.50
        )
        assert result['electric_cost'] == 1.2
        assert result['gas_cost'] == 0.0
        assert result['total_cost'] == 1.2
        assert result['total_miles'] == 40.0

    def test_calculate_trip_cost_gas_only(self):
        """All gas trip"""
        result = calculate_trip_cost(
            electric_miles=None,
            electric_kwh=None,
            gas_miles=100,
            gas_gallons=2.5,
            electricity_rate=0.12,
            gas_price=3.50
        )
        assert result['electric_cost'] == 0.0
        assert result['gas_cost'] == 8.75
        assert result['total_cost'] == 8.75
        assert result['total_miles'] == 100.0

    def test_calculate_trip_cost_no_miles(self):
        """No miles driven should have None cost_per_mile"""
        result = calculate_trip_cost(
            electric_miles=0,
            electric_kwh=0,
            gas_miles=0,
            gas_gallons=0
        )
        assert result['total_cost'] == 0.0
        assert result['cost_per_mile'] is None


class TestCostSavings:
    """Test cost savings vs gas-only calculations"""

    def test_calculate_cost_savings_typical(self):
        """40 mi electric at $0.12/kWh vs 30 MPG gas at $3.50"""
        result = calculate_cost_savings_vs_gas_only(
            electric_miles=40,
            electric_kwh=10.0,
            electricity_rate=0.12,
            gas_price=3.50,
            gas_vehicle_mpg=30.0
        )
        # Electric: 10 * 0.12 = $1.20
        # Gas: 40 / 30 * 3.50 = $4.67
        # Savings: $3.47
        assert result['electric_cost'] == 1.2
        assert result['gas_only_cost'] == 4.67
        assert result['savings'] == 3.47
        assert result['savings_percent'] == 74.3

    def test_calculate_cost_savings_efficient_gas_vehicle(self):
        """Compare against efficient gas vehicle (40 MPG)"""
        result = calculate_cost_savings_vs_gas_only(
            electric_miles=40,
            electric_kwh=10.0,
            electricity_rate=0.12,
            gas_price=3.50,
            gas_vehicle_mpg=40.0
        )
        # Gas: 40 / 40 * 3.50 = $3.50
        assert result['gas_only_cost'] == 3.5
        assert result['savings'] == 2.3

    def test_calculate_cost_savings_expensive_electricity(self):
        """High electricity rates reduce savings"""
        result = calculate_cost_savings_vs_gas_only(
            electric_miles=40,
            electric_kwh=10.0,
            electricity_rate=0.30,  # Expensive
            gas_price=3.50,
            gas_vehicle_mpg=30.0
        )
        # Electric: 10 * 0.30 = $3.00
        # Savings reduced to $1.67
        assert result['electric_cost'] == 3.0
        assert result['savings'] == 1.67


class TestPaybackPeriod:
    """Test payback period calculations"""

    def test_calculate_payback_period_typical(self):
        """$5000 premium, 12k mi/yr, $0.03 electric vs $0.12 gas"""
        result = calculate_payback_period_years(
            vehicle_price_premium=5000,
            annual_miles=12000,
            electric_cost_per_mile=0.03,
            gas_cost_per_mile=0.12
        )
        # Annual savings: 12000 * (0.12 - 0.03) = $1080
        # Payback: 5000 / 1080 = 4.6 years
        assert result == 4.6

    def test_calculate_payback_period_high_mileage(self):
        """High mileage = faster payback"""
        result = calculate_payback_period_years(
            vehicle_price_premium=5000,
            annual_miles=20000,
            electric_cost_per_mile=0.03,
            gas_cost_per_mile=0.12
        )
        # Annual savings: 20000 * 0.09 = $1800
        # Payback: 5000 / 1800 = 2.8 years
        assert result == 2.8

    def test_calculate_payback_period_no_savings(self):
        """No savings = never pays back"""
        result = calculate_payback_period_years(
            vehicle_price_premium=5000,
            annual_miles=12000,
            electric_cost_per_mile=0.12,  # Same as gas
            gas_cost_per_mile=0.12
        )
        assert result is None

    def test_calculate_payback_period_electric_more_expensive(self):
        """Electric more expensive = never pays back"""
        result = calculate_payback_period_years(
            vehicle_price_premium=5000,
            annual_miles=12000,
            electric_cost_per_mile=0.15,  # More than gas
            gas_cost_per_mile=0.12
        )
        assert result is None

    def test_calculate_payback_period_zero_miles(self):
        """Zero miles = invalid"""
        result = calculate_payback_period_years(
            vehicle_price_premium=5000,
            annual_miles=0,
            electric_cost_per_mile=0.03,
            gas_cost_per_mile=0.12
        )
        assert result is None
