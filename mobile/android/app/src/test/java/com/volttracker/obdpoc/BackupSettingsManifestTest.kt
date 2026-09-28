package com.volttracker.obdpoc

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.volttracker.obdpoc.data.VinKeyHasher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupSettingsManifestTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()
    private lateinit var databaseFile: File
    private lateinit var originalIdentitySecrets: List<String>

    @Before
    fun setUp() {
        originalIdentitySecrets = VinKeyHasher.exportSecrets(context)
        context
            .getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        databaseFile = File(context.cacheDir, "settings-manifest-${System.nanoTime()}.db")
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { db ->
            db.execSQL("CREATE TABLE sample(id INTEGER PRIMARY KEY)")
        }
    }

    @After
    fun tearDown() {
        VinKeyHasher.replaceSecretsForTest(context, originalIdentitySecrets)
        databaseFile.delete()
    }

    @Test
    fun sharedDisplayPrefsTravelInTheDashboardBlockWithNativeWinning() {
        val prefs = context.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
        val shared = SharedDisplayPrefs(prefs)
        shared.setPricePerKwh(0.14)
        shared.setUnits(SharedDisplayPrefs.Units.METRIC)
        // The WebView's copy is stale for the rate and lacks the units; its own keys still ride along.
        val dashboard = """{"schemaVersion":1,"preferences":{"pricePerKwh":0.1,"mapLayer":"satellite"}}"""

        BackupSettingsManifest.embed(context, databaseFile, dashboard, includeIdentitySecrets = false)
        val manifest = requireNotNull(BackupSettingsManifest.read(databaseFile))
        val saved = manifest.getJSONObject("dashboard").getJSONObject("preferences")
        assertEquals(0.14, saved.getDouble("pricePerKwh"), 0.0)
        assertEquals("metric", saved.getString("units"))
        assertEquals("satellite", saved.getString("mapLayer"))

        prefs.edit().clear().commit()
        BackupSettingsManifest.applyNative(context, manifest)
        assertEquals(0.14, shared.pricePerKwh(), 0.0)
        assertEquals(SharedDisplayPrefs.Units.METRIC, shared.units())
    }

    @Test
    fun aComposeStartedBackupStillCarriesTheSharedPrefs() {
        val prefs = context.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
        SharedDisplayPrefs(prefs).setGasPricePerGal(4.29)

        BackupSettingsManifest.embed(context, databaseFile, null, includeIdentitySecrets = false)
        val dashboard = requireNotNull(BackupSettingsManifest.read(databaseFile)).getJSONObject("dashboard")
        assertEquals(1, dashboard.getInt("schemaVersion"))
        assertEquals(4.29, dashboard.getJSONObject("preferences").getDouble("gasPricePerGal"), 0.0)
    }

    @Test
    fun manifestCapturesAppliesAndThenLeavesNoTransportTable() {
        val prefs = context.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
        prefs
            .edit()
            .putBoolean(AutoConnectController.PREF_AUTO_CONNECT_ENABLED, false)
            .putBoolean(DashboardExperienceHostDelegate.PREF_KEEP_SCREEN_AWAKE, true)
            .putBoolean(DashboardExperienceHostDelegate.PREF_TRIP_SUMMARY, true)
            .commit()
        EventNotificationPrefs(prefs).setLowSocEnabled(true)
        val dashboard = """{"schemaVersion":1,"preferences":{"units":"metric"}}"""

        BackupSettingsManifest.embed(context, databaseFile, dashboard, includeIdentitySecrets = false)
        val manifest = BackupSettingsManifest.read(databaseFile)
        requireNotNull(manifest)
        assertEquals("metric", manifest.getJSONObject("dashboard").getJSONObject("preferences").getString("units"))

        prefs.edit().clear().commit()
        BackupSettingsManifest.applyNative(context, manifest)
        assertFalse(prefs.getBoolean(AutoConnectController.PREF_AUTO_CONNECT_ENABLED, true))
        assertTrue(prefs.getBoolean(DashboardExperienceHostDelegate.PREF_KEEP_SCREEN_AWAKE, false))
        assertTrue(prefs.getBoolean(DashboardExperienceHostDelegate.PREF_TRIP_SUMMARY, false))
        assertTrue(EventNotificationPrefs(prefs).lowSocEnabled())

        assertTrue(BackupSettingsManifest.remove(databaseFile))
        assertNull(BackupSettingsManifest.read(databaseFile))
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM sample", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun plaintextManifestOmitsVehicleIdentitySecretsButStillApplies() {
        // E1: a plaintext backup manifest must NOT carry the VIN HMAC secrets (they would sit
        // next to vehicles.vin_hash and make the VIN brute-forceable). The rest of the settings
        // still capture and apply, and applyNative tolerates the absent key array.
        val prefs = context.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(DashboardExperienceHostDelegate.PREF_KEEP_SCREEN_AWAKE, true).commit()

        BackupSettingsManifest.embed(context, databaseFile, "{}", includeIdentitySecrets = false)
        val manifest = requireNotNull(BackupSettingsManifest.read(databaseFile))

        assertFalse(
            "plaintext manifest must not embed the VIN identity secrets",
            manifest.getJSONObject("native").has("vehicleIdentityKeys"),
        )

        prefs.edit().clear().commit()
        BackupSettingsManifest.applyNative(context, manifest)
        assertTrue(prefs.getBoolean(DashboardExperienceHostDelegate.PREF_KEEP_SCREEN_AWAKE, false))
    }

    @Test
    fun oldDatabaseOnlyBackupHasNoSettingsManifest() {
        assertNull(BackupSettingsManifest.read(databaseFile))
        assertEquals("{}", BackupSettingsManifest.dashboardPreferences(null))
    }

    @Test
    fun manifestLetsANewInstallationRecognizeRestoredVehicleHashes() {
        val vin = "synthetic-vehicle-identity"
        val donorHash = VinKeyHasher(context).hash(vin)
        // includeIdentitySecrets = true is the ENCRYPTED-container path (E1) — the only one
        // allowed to transport the VIN HMAC secrets.
        BackupSettingsManifest.embed(context, databaseFile, "{}", includeIdentitySecrets = true)
        val manifest = requireNotNull(BackupSettingsManifest.read(databaseFile))
        assertTrue(manifest.getJSONObject("native").getJSONArray("vehicleIdentityKeys").length() > 0)

        // Simulate a different installation with a fresh local primary key.
        VinKeyHasher.replaceSecretsForTest(context, emptyList())
        val destinationHasher = VinKeyHasher(context)
        assertNotEquals(donorHash, destinationHasher.hash(vin))

        BackupSettingsManifest.applyNative(context, manifest)

        assertTrue(destinationHasher.hashCandidates(vin).contains(donorHash))
        assertNotEquals(
            "the destination keeps its own non-enumerable primary key",
            donorHash,
            destinationHasher.hash(vin),
        )
    }
}
