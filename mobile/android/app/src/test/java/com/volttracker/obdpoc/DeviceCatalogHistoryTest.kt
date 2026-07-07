package com.volttracker.obdpoc

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Exercises the remembered-device history, last-device JSON, and bonded-adapter discovery in
 * [DeviceCatalog]. Complements [DeviceCatalogTest], which only covers the name heuristic and the
 * invalid-address reject path of [DeviceCatalog.remember].
 */
@RunWith(RobolectricTestRunner::class)
// Pin the Robolectric SDK like the rest of the suite: SDK 34 runs on Java 17 (CI's JDK) and is
// >= S, so [DeviceCatalog.hasBluetoothConnectPermission] still exercises the permission check.
@Config(sdk = [34])
class DeviceCatalogHistoryTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var catalog: DeviceCatalog

    // Two well-formed MAC addresses accepted by BluetoothAdapter.checkBluetoothAddress (uppercase
    // hex, colon-separated). Lowercase is rejected by the framework, so keep these uppercase.
    private val macA = "AA:BB:CC:DD:EE:01"
    private val macB = "AA:BB:CC:DD:EE:02"

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("device-catalog-history-test", 0)
        prefs.edit().clear().commit()
        catalog = DeviceCatalog(context, prefs)
    }

    @Test
    fun rememberValidAddressPersistsLastDeviceAndHistory() {
        val returned = catalog.remember(macA, "  ELM327  ")

        // remember returns the trimmed address; the name is also trimmed when stored.
        assertEquals(macA, returned)
        assertEquals(macA, catalog.lastAddress())
        assertEquals("ELM327", catalog.lastName())

        // getLastDeviceJson surfaces the stored last device as {address,name}.
        val last = JSONObject(catalog.getLastDeviceJson())
        assertEquals(macA, last.getString("address"))
        assertEquals("ELM327", last.getString("name"))

        // updatedDeviceHistory recorded a single, fully-populated entry.
        val history = JSONArray(catalog.getDeviceHistoryJson())
        assertEquals(1, history.length())
        val entry = history.getJSONObject(0)
        assertEquals(macA, entry.getString("address"))
        assertEquals("ELM327", entry.getString("name"))
        assertEquals(1, entry.getInt("connectCount"))
        // A brand-new entry stamps firstSeen == lastSeen.
        assertEquals(entry.getLong("firstSeen"), entry.getLong("lastSeen"))
    }

    @Test
    fun rememberRejectsNullBlankAndLowercaseAddressesWithoutChangingLastDevice() {
        catalog.remember(macA, "ELM327")

        assertEquals("", catalog.remember(null, "ignored"))
        assertEquals("", catalog.remember("   ", "ignored"))
        assertEquals("", catalog.remember(macB.lowercase(Locale.US), "lowercase framework reject"))

        val last = JSONObject(catalog.getLastDeviceJson())
        assertEquals("invalid remembers must not replace the last adapter", macA, last.getString("address"))
        assertEquals("ELM327", last.getString("name"))
    }

    @Test
    fun repeatRememberIncrementsConnectCountAndPreservesFirstSeen() {
        catalog.remember(macA, "ELM327")
        val firstSeen = JSONArray(catalog.getDeviceHistoryJson()).getJSONObject(0).getLong("firstSeen")

        // Robolectric's clock does not advance on its own; nudge it so lastSeen can change.
        Thread.sleep(2)
        catalog.remember(macA, "ELM327")

        val history = JSONArray(catalog.getDeviceHistoryJson())
        // Same address folds into one entry rather than appending a duplicate.
        assertEquals(1, history.length())
        val entry = history.getJSONObject(0)
        assertEquals(2, entry.getInt("connectCount"))
        // firstSeen is carried over from the prior record; lastSeen advances.
        assertEquals(firstSeen, entry.getLong("firstSeen"))
        assertTrue(entry.getLong("lastSeen") >= entry.getLong("firstSeen"))
    }

    @Test
    fun repeatRememberWithBlankNameInheritsStoredName() {
        catalog.remember(macA, "ELM327")

        // A later connect with no advertised name should keep the previously stored name.
        catalog.remember(macA, "")

        val entry = JSONArray(catalog.getDeviceHistoryJson()).getJSONObject(0)
        assertEquals("ELM327", entry.getString("name"))
        assertEquals(2, entry.getInt("connectCount"))
        // lastName is updated to the blank value passed in remember, even though history inherits.
        assertEquals("", catalog.lastName())
    }

    @Test
    fun caseInsensitiveAddressMatchFoldsIntoSameEntry() {
        // Seed history with a lowercase-address entry directly (BluetoothAdapter.checkBluetoothAddress
        // only accepts uppercase, so remember could never store a lowercase MAC itself). remember
        // then folds the uppercase form into it via updatedDeviceHistory's case-insensitive match.
        val seeded =
            JSONArray().put(
                JSONObject()
                    .put("address", macA.lowercase())
                    .put("name", "ELM327")
                    .put("firstSeen", 1_000L)
                    .put("lastSeen", 1_000L)
                    .put("connectCount", 3),
            )
        prefs.edit().putString("device_history", seeded.toString()).commit()

        catalog.remember(macA, "ELM327")

        val history = JSONArray(catalog.getDeviceHistoryJson())
        // Folded, not duplicated: one entry whose connectCount picks up from the seeded value.
        assertEquals(1, history.length())
        val entry = history.getJSONObject(0)
        assertEquals(4, entry.getInt("connectCount"))
        // firstSeen is inherited from the seeded record.
        assertEquals(1_000L, entry.getLong("firstSeen"))
    }

    @Test
    fun malformedStoredHistoryFallsBackToStoredLastDevice() {
        prefs
            .edit()
            .putString("device_history", "{not json")
            .putString(DeviceCatalog.PREF_LAST_ADDRESS, macA)
            .putString(DeviceCatalog.PREF_LAST_NAME, "Saved Adapter")
            .commit()

        val history = JSONArray(catalog.getDeviceHistoryJson())

        assertEquals(1, history.length())
        val entry = history.getJSONObject(0)
        assertEquals(macA, entry.getString("address"))
        assertEquals("Saved Adapter", entry.getString("name"))
        assertEquals(1, entry.getInt("connectCount"))
    }

    @Test
    fun malformedStoredHistoryIsReplacedOnNextRemember() {
        prefs.edit().putString("device_history", "not-json").commit()

        catalog.remember(macA, "ELM327")

        val history = JSONArray(catalog.getDeviceHistoryJson())
        assertEquals(1, history.length())
        assertEquals(macA, history.getJSONObject(0).getString("address"))
        assertEquals("ELM327", history.getJSONObject(0).getString("name"))
    }

    @Test
    fun mostRecentDevicePrependsAndDistinctDevicesAccumulate() {
        catalog.remember(macA, "ELM327")
        catalog.remember(macB, "OBDLink")

        val history = JSONArray(catalog.getDeviceHistoryJson())
        assertEquals(2, history.length())
        // The just-remembered device sits at index 0; the prior device follows.
        assertEquals(macB, history.getJSONObject(0).getString("address"))
        assertEquals(macA, history.getJSONObject(1).getString("address"))
    }

    @Test
    fun historyIsCappedAtEightDevicesKeepingTheMostRecent() {
        // Remember nine distinct adapters; history keeps the newest MAX_DEVICE_HISTORY (8).
        for (i in 1..9) {
            catalog.remember(String.format(Locale.US, "AA:BB:CC:DD:EE:%02X", i), "Adapter $i")
        }

        val history = JSONArray(catalog.getDeviceHistoryJson())
        assertEquals(8, history.length())
        // The 9th (newest) is first; the 1st (oldest) was dropped.
        assertEquals("AA:BB:CC:DD:EE:09", history.getJSONObject(0).getString("address"))
        val addresses = (0 until history.length()).map { history.getJSONObject(it).getString("address") }
        assertFalse(addresses.contains("AA:BB:CC:DD:EE:01"))
    }

    @Test
    fun getLastOrCandidateDeviceReturnsStoredLastWhenPresent() {
        catalog.remember(macA, "ELM327")

        val payload = catalog.getLastOrCandidateDevice()
        assertEquals(macA, payload.getString("address"))
        assertEquals("ELM327", payload.getString("name"))
        // The stored-last branch does not synthesise candidate-only keys.
        assertFalse(payload.has("candidate"))
    }

    @Test
    fun getLastOrCandidateDeviceReturnsBlankPayloadWhenNothingStoredAndNoCandidates() {
        // No last device and no bonded adapter granted: empty {address:"",name:""}.
        val payload = catalog.getLastOrCandidateDevice()
        assertEquals("", payload.getString("address"))
        assertEquals("", payload.getString("name"))
    }

    @Test
    fun getBondedDevicesJsonIsEmptyWithoutConnectPermission() {
        // SDK 34 requires BLUETOOTH_CONNECT; without it the catalog returns an empty array.
        registerBondedDevices(macA to "ELM327")

        assertEquals("[]", catalog.getBondedDevicesJson())
    }

    @Test
    fun getBondedDevicesJsonLabelsAndOrdersObdCandidatesFirst() {
        grantConnectPermission()
        // Mix an OBD adapter with a non-OBD peripheral; the catalog should label each correctly
        // and float OBD-likely names to the front.
        registerBondedDevices(
            macA to "AirPods Pro",
            macB to "OBDLink MX+",
        )

        val devices = JSONArray(catalog.getBondedDevicesJson())
        assertEquals(2, devices.length())

        // OBD candidate is sorted ahead of the non-candidate regardless of alphabetical order.
        val first = devices.getJSONObject(0)
        assertEquals("OBDLink MX+", first.getString("name"))
        assertEquals(macB, first.getString("address"))
        assertTrue(first.getBoolean("obdCandidate"))
        assertTrue(first.has("type"))
        assertTrue(first.has("bondState"))

        val second = devices.getJSONObject(1)
        assertEquals("AirPods Pro", second.getString("name"))
        assertFalse(second.getBoolean("obdCandidate"))
    }

    @Test
    fun getBondedDevicesJsonSortsAlphabeticallyWithinCandidateBucket() {
        grantConnectPermission()
        registerBondedDevices(
            "AA:BB:CC:DD:EE:03" to "Veepeak",
            macB to "ELM327",
            macA to "Carista",
        )

        val devices = JSONArray(catalog.getBondedDevicesJson())

        assertEquals(3, devices.length())
        assertEquals("Carista", devices.getJSONObject(0).getString("name"))
        assertEquals("ELM327", devices.getJSONObject(1).getString("name"))
        assertEquals("Veepeak", devices.getJSONObject(2).getString("name"))
    }

    @Test
    fun bondedDeviceWithBlankNameFallsBackToObdAdapterLabel() {
        grantConnectPermission()
        registerBondedDevices(macA to "   ")

        val devices = JSONArray(catalog.getBondedDevicesJson())

        assertEquals(1, devices.length())
        assertEquals("OBD adapter", devices.getJSONObject(0).getString("name"))
    }

    @Test
    fun lastOrCandidateFallsBackToBondedObdCandidateWhenNoLastDevice() {
        grantConnectPermission()
        registerBondedDevices(
            macA to "AirPods Pro",
            macB to "Veepeak OBDII",
        )

        // No stored last device, so getLastOrCandidateDevice returns the first OBD candidate,
        // which carries the candidate-only shape from getLikelyObdCandidates.
        val payload = catalog.getLastOrCandidateDevice()
        assertEquals(macB, payload.getString("address"))
        assertEquals("Veepeak OBDII", payload.getString("name"))
        assertTrue(payload.getBoolean("candidate"))
        assertEquals(0, payload.getInt("connectCount"))
        assertEquals(0L, payload.getLong("lastSeen"))
    }

    @Test
    fun lastOrCandidateIgnoresNonObdBondedDevices() {
        grantConnectPermission()
        // Only non-OBD peripherals are bonded: there is no candidate, so the blank payload wins.
        registerBondedDevices(macA to "AirPods Pro")

        val payload = catalog.getLastOrCandidateDevice()
        assertEquals("", payload.getString("address"))
        assertEquals("", payload.getString("name"))
        assertFalse(payload.has("candidate"))
    }

    @Test
    fun getDeviceHistoryJsonFallsBackToBondedCandidatesWhenHistoryEmpty() {
        grantConnectPermission()
        registerBondedDevices(
            macA to "AirPods Pro",
            macB to "ELM327",
        )

        // No persisted history and no last address: history falls back to OBD candidates.
        val history = JSONArray(catalog.getDeviceHistoryJson())
        assertEquals(1, history.length())
        val entry = history.getJSONObject(0)
        assertEquals(macB, entry.getString("address"))
        assertTrue(entry.getBoolean("candidate"))
    }

    @Test
    fun storedLastAddressYieldsDistinctFirstAndLastSeenAcrossDevices() {
        catalog.remember(macA, "ELM327")
        Thread.sleep(2)
        catalog.remember(macB, "OBDLink")

        // Sanity-check that distinct devices keep independent timestamps in history.
        val history = JSONArray(catalog.getDeviceHistoryJson())
        val newest = history.getJSONObject(0)
        val older = history.getJSONObject(1)
        assertNotEquals(newest.getLong("lastSeen"), older.getLong("lastSeen"))
    }

    /** Grants BLUETOOTH_CONNECT on the application context backing [catalog]. */
    private fun grantConnectPermission() {
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
    }

    /**
     * Registers [pairs] (address to advertised name) as the default adapter's bonded set so
     * [DeviceCatalog.getBondedDevicesJson] and the candidate helpers can read them.
     */
    private fun registerBondedDevices(vararg pairs: Pair<String, String>) {
        val adapter = BluetoothAdapter.getDefaultAdapter()!!
        val devices = LinkedHashSet<BluetoothDevice>()
        for ((address, name) in pairs) {
            val device = adapter.getRemoteDevice(address)
            shadowOf(device).setName(name)
            devices.add(device)
        }
        shadowOf(adapter).setBondedDevices(devices)
    }
}
