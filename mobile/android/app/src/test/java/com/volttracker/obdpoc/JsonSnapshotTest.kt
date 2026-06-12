package com.volttracker.obdpoc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Covers the immutable-snapshot publication contract of [JsonSnapshot]. */
class JsonSnapshotTest {
    @Test
    fun startsEmpty() {
        assertEquals(0, JsonSnapshot().get().length())
    }

    @Test
    fun setFromStringPublishesParsedSnapshot() {
        val snapshot = JsonSnapshot()
        snapshot.setFromString("{\"state\":\"connected\",\"n\":3}")

        assertEquals("connected", snapshot.get().optString("state"))
        assertEquals(3, snapshot.get().optInt("n"))
    }

    @Test
    fun setFromStringStoresEmptyForNullBlankOrMalformedInput() {
        val snapshot = JsonSnapshot()
        snapshot.setFromString("{\"a\":1}")

        snapshot.setFromString("not json")
        assertEquals(0, snapshot.get().length())

        snapshot.setFromString(null)
        assertEquals(0, snapshot.get().length())
    }

    @Test
    fun setStoresIsolatedCopySoCallerMutationCannotLeakIn() {
        val snapshot = JsonSnapshot()
        val source = JSONObject().put("a", 1)
        snapshot.set(source)

        source.put("a", 2)
        source.put("b", "late mutation")

        assertEquals(1, snapshot.get().optInt("a"))
        assertEquals("snapshot must not see keys added after publish", 1, snapshot.get().length())
    }

    @Test
    fun setReplacesThePreviousSnapshotWholesale() {
        val snapshot = JsonSnapshot()
        snapshot.set(JSONObject().put("old", true))
        snapshot.set(JSONObject().put("new", true))

        assertEquals(1, snapshot.get().length())
        assertEquals(true, snapshot.get().optBoolean("new"))
    }
}
