package com.volttracker.obdpoc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleKeyAliasesTest {
    @Test
    fun parseReturnsEmptyForNullBlankOrMalformedJson() {
        assertEquals(emptySet<String>(), VehicleKeyAliases.parse(null))
        assertEquals(emptySet<String>(), VehicleKeyAliases.parse("  "))
        assertEquals(emptySet<String>(), VehicleKeyAliases.parse("not-json"))
        assertEquals(emptySet<String>(), VehicleKeyAliases.parse("{\"a\":1}"))
    }

    @Test
    fun parseSkipsBlankEntriesAndDeduplicates() {
        assertEquals(
            setOf("k1", "k2"),
            VehicleKeyAliases.parse("""["k1","","k2","k1"]"""),
        )
    }

    @Test
    fun serializeReturnsNullForEmptyInputSoUntouchedRowsStayNull() {
        assertNull(VehicleKeyAliases.serialize(emptyList()))
        assertNull(VehicleKeyAliases.serialize(listOf("", " ")))
    }

    @Test
    fun serializeRoundTripsDeduplicatedAndCapped() {
        val many = (1..VehicleKeyAliases.MAX_ALIASES + 5).map { "key-$it" }
        val parsed = VehicleKeyAliases.parse(VehicleKeyAliases.serialize(many + many))
        assertEquals(VehicleKeyAliases.MAX_ALIASES, parsed.size)
        assertEquals(many.take(VehicleKeyAliases.MAX_ALIASES).toSet(), parsed)
    }
}
