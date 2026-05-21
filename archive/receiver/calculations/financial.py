"""
Financial Calculations

Handles cost calculations for charging and fuel:
- Charging costs
- Fuel costs
- Cost comparisons
- Cost per mile
"""

from typing import Optional

from .constants import ELECTRICITY_COST_PER_KWH, GAS_COST_PER_GALLON


def calculate_charging_cost(
    kwh_added: float,
    cost_per_kwh: float = ELECTRICITY_COST_PER_KWH
) -> Optional[float]:
    """
    Calculate cost of a charging session.

    Args:
        kwh_added: Energy added to battery (kWh)
        cost_per_kwh: Electricity rate ($/kWh)

    Returns:
        Total charging cost in dollars, or None if invalid

    Examples:
        >>> calculate_charging_cost(10.0, 0.12)
        1.2
        >>> calculate_charging_cost(15.5, 0.15)
        2.33
    """
    if kwh_added is None or kwh_added <= 0:
        return None

    if cost_per_kwh is None or cost_per_kwh <= 0:
        return None

    return round(kwh_added * cost_per_kwh, 2)


def calculate_fuel_cost(
    gallons_consumed: float,
    cost_per_gallon: float = GAS_COST_PER_GALLON
) -> Optional[float]:
    """
    Calculate cost of fuel consumed.

    Args:
        gallons_consumed: Gallons of gas used
        cost_per_gallon: Gas price ($/gallon)

    Returns:
        Total fuel cost in dollars, or None if invalid

    Examples:
        >>> calculate_fuel_cost(10.0, 3.50)
        35.0
        >>> calculate_fuel_cost(5.5, 4.00)
        22.0
    """
    if gallons_consumed is None or gallons_consumed <= 0:
        return None

    if cost_per_gallon is None or cost_per_gallon <= 0:
        return None

    return round(gallons_consumed * cost_per_gallon, 2)


def calculate_electric_cost_per_mile(
    electric_miles: float,
    kwh_used: float,
    cost_per_kwh: float = ELECTRICITY_COST_PER_KWH
) -> Optional[float]:
    """
    Calculate cost per mile for electric driving.

    Args:
        electric_miles: Miles driven on electric
        kwh_used: kWh consumed
        cost_per_kwh: Electricity rate ($/kWh)

    Returns:
        Cost per mile in dollars, or None if invalid

    Examples:
        >>> calculate_electric_cost_per_mile(40.0, 10.0, 0.12)
        0.03
        >>> calculate_electric_cost_per_mile(50.0, 15.0, 0.15)
        0.045
    """
    if electric_miles is None or electric_miles <= 0:
        return None

    total_cost = calculate_charging_cost(kwh_used, cost_per_kwh)
    if total_cost is None:
        return None

    return round(total_cost / electric_miles, 3)


def calculate_gas_cost_per_mile(
    gas_miles: float,
    gallons_consumed: float,
    cost_per_gallon: float = GAS_COST_PER_GALLON
) -> Optional[float]:
    """
    Calculate cost per mile for gas driving.

    Args:
        gas_miles: Miles driven on gas
        gallons_consumed: Gallons consumed
        cost_per_gallon: Gas price ($/gallon)

    Returns:
        Cost per mile in dollars, or None if invalid

    Examples:
        >>> calculate_gas_cost_per_mile(100.0, 2.5, 3.50)
        0.088
        >>> calculate_gas_cost_per_mile(40.0, 1.0, 4.00)
        0.1
    """
    if gas_miles is None or gas_miles <= 0:
        return None

    total_cost = calculate_fuel_cost(gallons_consumed, cost_per_gallon)
    if total_cost is None:
        return None

    return round(total_cost / gas_miles, 3)


def calculate_trip_cost(
    electric_miles: Optional[float],
    electric_kwh: Optional[float],
    gas_miles: Optional[float],
    gas_gallons: Optional[float],
    electricity_rate: float = ELECTRICITY_COST_PER_KWH,
    gas_price: float = GAS_COST_PER_GALLON
) -> dict:
    """
    Calculate comprehensive cost breakdown for a trip.

    Args:
        electric_miles: Miles driven on electric
        electric_kwh: kWh consumed from battery
        gas_miles: Miles driven on gas
        gas_gallons: Gallons of gas consumed
        electricity_rate: Electricity rate ($/kWh)
        gas_price: Gas price ($/gallon)

    Returns:
        Dictionary with cost breakdown

    Examples:
        >>> result = calculate_trip_cost(30, 9.0, 20, 0.5, 0.12, 3.50)
        >>> result['total_cost']
        2.83
        >>> result['electric_cost']
        1.08
        >>> result['gas_cost']
        1.75
    """
    electric_cost = 0.0
    gas_cost = 0.0
    total_miles = 0.0

    if electric_miles and electric_kwh:
        electric_cost = calculate_charging_cost(electric_kwh, electricity_rate) or 0.0
        total_miles += electric_miles

    if gas_miles and gas_gallons:
        gas_cost = calculate_fuel_cost(gas_gallons, gas_price) or 0.0
        total_miles += gas_miles

    total_cost = electric_cost + gas_cost
    cost_per_mile = round(total_cost / total_miles, 3) if total_miles > 0 else None

    return {
        "electric_cost": round(electric_cost, 2),
        "gas_cost": round(gas_cost, 2),
        "total_cost": round(total_cost, 2),
        "cost_per_mile": cost_per_mile,
        "electric_miles": electric_miles or 0.0,
        "gas_miles": gas_miles or 0.0,
        "total_miles": total_miles
    }


def calculate_cost_savings_vs_gas_only(
    electric_miles: float,
    electric_kwh: float,
    electricity_rate: float,
    gas_price: float,
    gas_vehicle_mpg: float = 30.0
) -> dict:
    """
    Calculate cost savings of electric vs driving same distance with gas.

    Args:
        electric_miles: Miles driven on electric
        electric_kwh: kWh consumed
        electricity_rate: Electricity rate ($/kWh)
        gas_price: Gas price ($/gallon)
        gas_vehicle_mpg: MPG of comparable gas-only vehicle

    Returns:
        Dictionary with savings comparison

    Examples:
        >>> result = calculate_cost_savings_vs_gas_only(40, 10.0, 0.12, 3.50, 30.0)
        >>> result['savings']
        3.47
    """
    # Actual cost (electric)
    electric_cost = calculate_charging_cost(electric_kwh, electricity_rate) or 0.0

    # Hypothetical cost if driven on gas
    gallons_if_gas = electric_miles / gas_vehicle_mpg
    gas_only_cost = gallons_if_gas * gas_price

    savings = gas_only_cost - electric_cost

    return {
        "electric_cost": round(electric_cost, 2),
        "gas_only_cost": round(gas_only_cost, 2),
        "savings": round(savings, 2),
        "savings_percent": round((savings / gas_only_cost) * 100, 1) if gas_only_cost > 0 else 0.0,
        "miles": electric_miles
    }


def calculate_payback_period_years(
    vehicle_price_premium: float,
    annual_miles: float,
    electric_cost_per_mile: float,
    gas_cost_per_mile: float
) -> Optional[float]:
    """
    Calculate payback period for EV/PHEV premium over gas vehicle.

    Args:
        vehicle_price_premium: Extra cost of EV/PHEV vs gas equivalent
        annual_miles: Expected annual mileage
        electric_cost_per_mile: Cost per mile for electric driving
        gas_cost_per_mile: Cost per mile for gas driving

    Returns:
        Payback period in years, or None if invalid

    Examples:
        >>> calculate_payback_period_years(5000, 12000, 0.03, 0.12)
        4.6
    """
    if annual_miles <= 0:
        return None

    annual_savings = annual_miles * (gas_cost_per_mile - electric_cost_per_mile)

    if annual_savings <= 0:
        return None  # No savings, will never pay back

    payback_years = vehicle_price_premium / annual_savings
    return round(payback_years, 1)
