package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.components.unbreakableUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class UnbreakableUnitTest {
    @Test
    fun unitsKeepTheirSpacesAndSlashesTogether() {
        assertEquals("kWh/⁠100 km", unbreakableUnit("kWh/100 km"))
        assertEquals("mi/⁠kWh", unbreakableUnit("mi/kWh"))
        assertEquals("vs gas", unbreakableUnit("vs gas"))
        assertEquals("%", unbreakableUnit("%"))
    }
}
