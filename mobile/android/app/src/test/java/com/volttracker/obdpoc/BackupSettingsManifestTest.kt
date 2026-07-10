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

        BackupSettingsManifest.embed(context, databaseFile, dashboard)
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
    fun oldDatabaseOnlyBackupHasNoSettingsManifest() {
        assertNull(BackupSettingsManifest.read(databaseFile))
        assertEquals("{}", BackupSettingsManifest.dashboardPreferences(null))
    }

    @Test
    fun manifestLetsANewInstallationRecognizeRestoredVehicleHashes() {
        val vin = "synthetic-vehicle-identity"
        val donorHash = VinKeyHasher(context).hash(vin)
        BackupSettingsManifest.embed(context, databaseFile, "{}")
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
