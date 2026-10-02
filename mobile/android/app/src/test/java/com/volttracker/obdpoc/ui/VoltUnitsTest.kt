package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.units.VoltUnits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoltUnitsTest {
    private val imperial = VoltUnits.Imperial
    private val metric = VoltUnits.Metric

    @Test
    fun ofPicksTheSystem() {
        assertEquals(metric, VoltUnits.of(true))
        assertEquals(imperial, VoltUnits.of(false))
    }

    @Test
    fun distanceKeepsTenthsBelowTenAndWholeUnitsAbove() {
        assertEquals("38 mi", imperial.distanceText(38.2))
        assertEquals("7.3 mi", imperial.distanceText(7.3))
        assertEquals("61 km", metric.distanceText(38.2))
        assertEquals("9.7 km", metric.distanceText(6.0))
        assertEquals("16", metric.distanceWhole(10.0))
        assertEquals("16.1", metric.distanceOneDecimal(10.0))
        assertEquals("km", metric.distanceUnit)
        assertEquals("mi", imperial.distanceUnit)
    }

    @Test
    fun speedAndTemperature() {
        assertEquals("45 mph", imperial.speedText(45.0))
        assertEquals("72 km/h", metric.speedText(45.0))
        assertEquals("74°F", imperial.tempText(74.0))
        assertEquals("23°C", metric.tempText(74.0))
        assertEquals(-40, metric.temp(-40.0))
    }

    @Test
    fun metricEfficiencyIsEnergyPerHundredKilometres() {
        assertEquals("4.0 mi/kWh", imperial.efficiencyText(4.0))
        // 4 mi/kWh = 6.437 km/kWh = 15.5 kWh per 100 km.
        assertEquals("15.5 kWh/100 km", metric.efficiencyText(4.0))
        assertEquals("15.5", metric.efficiencyValue(4.0))
        assertNull(metric.efficiencyValue(null))
        assertNull(metric.efficiencyText(0.0))
        assertNull(imperial.efficiency(Double.NaN))
    }

    @Test
    fun gasEconomyAndPriceConvertBothWays() {
        assertEquals("40.0 mpg", imperial.economyText(40.0))
        assertEquals("5.9 L/100 km", metric.economyText(40.0))
        assertNull(metric.economyValue(0.0))
        assertEquals(40.0, metric.mpgFrom(metric.economy(40.0) ?: 0.0), 1e-9)
        assertEquals(40.0, imperial.mpgFrom(40.0), 0.0)

        assertEquals(1.0, metric.gasPrice(VoltUnits.L_PER_GAL), 1e-9)
        assertEquals(4.29, metric.gasPricePerGallon(metric.gasPrice(4.29)), 1e-9)
        assertEquals("L", metric.gasVolumeUnit)
        assertEquals("gal", imperial.gasVolumeUnit)
    }

    @Test
    fun pressureConvertsBothWays() {
        assertEquals("38 psi", imperial.pressureText(38.0))
        assertEquals("262 kPa", metric.pressureText(38.0))
        assertEquals(38.0, metric.psiFrom(metric.pressureValue(38.0)), 1e-9)
        assertEquals(38.0, imperial.psiFrom(38.0), 0.0)
    }
}
