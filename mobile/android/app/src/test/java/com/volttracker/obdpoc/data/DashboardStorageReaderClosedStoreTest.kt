package com.volttracker.obdpoc.data

import com.volttracker.obdpoc.DashboardStorageReader
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The dashboard reads SQLite synchronously from the WebView JS-bridge thread. If the store is
 * closed during teardown while a bridge read is in flight, the read must fail fast into the
 * "storage unavailable" payload the dashboard already understands — never throw into the bridge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardStorageReaderClosedStoreTest {
    private lateinit var store: ObdLocalStore
    private lateinit var reader: DashboardStorageReader

    @Before
    fun setUp() {
        store = ObdLocalStore(RuntimeEnvironment.getApplication())
        store.clearAllData()
        reader = DashboardStorageReader { store }
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun isOpenFlipsToFalseOnClose() {
        assertTrue(store.isOpen)
        store.close()
        assertFalse(store.isOpen)
    }

    @Test
    fun everyBridgeReadAfterCloseReturnsTheSafeFallbackWithoutThrowing() {
        store.close()

        for (payload in allReads()) {
            val json = JSONObject(payload)
            assertFalse("closed store must not report ok", json.optBoolean("ok", true))
            assertEquals("storage_unavailable", json.optString("error"))
            assertEquals("Local storage is not ready yet.", json.optString("message"))
        }
    }

    @Test
    fun readsWhileOpenStillServeRealPayloads() {
        val summary = JSONObject(reader.storageSummaryJson())
        assertTrue("open store serves the real summary", summary.has("sessionCount"))
        assertFalse("storage_unavailable" == summary.optString("error"))
    }

    @Test
    fun aNullStoreStillReturnsTheSafeFallback() {
        val nullReader = DashboardStorageReader { null }
        val json = JSONObject(nullReader.storageSummaryJson())
        assertEquals("storage_unavailable", json.optString("error"))
    }

    private fun allReads(): List<String> =
        listOf(
            reader.storageSummaryJson(),
            reader.storageDetailsJson(),
            reader.tripsJson(),
            reader.tripRouteJson("route-key"),
            reader.insightsJson(),
            reader.currentSessionRouteJson(),
            reader.batterySohHistoryJson(),
        )
}
