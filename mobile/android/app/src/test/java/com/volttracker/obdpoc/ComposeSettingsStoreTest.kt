package com.volttracker.obdpoc

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.volttracker.obdpoc.ui.settings.SettingChange
import com.volttracker.obdpoc.ui.settings.SettingsUiState
import com.volttracker.obdpoc.ui.theme.AppearanceMode
import com.volttracker.obdpoc.ui.theme.DarkStyle
import com.volttracker.obdpoc.ui.theme.OledAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Compose Settings ⇄ the shared prefs file: real defaults, and every edit lands with its owner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposeSettingsStoreTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var store: ComposeSettingsStore
    private val experience = FakeExperience()
    private var permissionRequests = 0
    private var granted = true

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store =
            ComposeSettingsStore(
                prefs = prefs,
                autoConnect = AutoConnectController(prefs, DeviceCatalog(context, prefs)),
                events =
                    EventNotificationHostDelegate(
                        prefs = { EventNotificationPrefs(prefs) },
                        publishStatus = { _, _, _ -> },
                        publishAppState = {},
                        notReadyMessage = { "" },
                        hasNotificationPermission = { granted },
                        ensureNotificationPermission = { permissionRequests++ },
                    ),
                experience = experience,
                formatDateTime = { "at $it" },
            )
    }

    @Test
    fun theLastBackupLineComesFromTheBackupReceipt() {
        assertEquals(SettingsUiState.NO_BACKUP, store.read(SettingsUiState()).lastBackupLabel)
        prefs.edit {
            putLong(BackupController.PREF_LAST_BACKUP_AT_MS, 1_000L)
            putInt(BackupController.PREF_LAST_BACKUP_TRIPS, 42)
        }
        assertEquals("Last backup at 1000 · 42 trips", store.read(SettingsUiState()).lastBackupLabel)
    }

    @Test
    fun anEmptyPrefsFileReadsAsTheDefaultsTheAppActuallyUses() {
        val state = store.read(SettingsUiState(autoConnect = true, notifyNewCode = false, homeRate = 9.0))
        assertFalse("auto-connect is opt-in (AutoConnectController default)", state.autoConnect)
        assertTrue(state.notifyChargingComplete)
        assertTrue(state.notifyNewCode)
        assertFalse(state.notifyBatteryLow)
        assertEquals(20, state.batteryLowPct)
        assertFalse(state.notifyPackTempHigh)
        assertEquals(45, state.packTempHighC)
        assertFalse(state.notifyMaintenance)
        assertFalse(state.endOfDriveRecap)
        assertFalse(state.autoScanCodes)
        assertFalse(state.metricUnits)
        assertEquals(0.0, state.homeRate, 0.0)
        assertEquals("not set", state.homeRateLabel)
        assertNull(state.gasMpg)
        assertEquals("not set", state.gasMpgLabel)
        assertEquals(100, state.chargeTargetPct)
        assertEquals(AppearanceMode.SYSTEM, state.appearance)
        assertEquals(DarkStyle.OLED, state.darkStyle)
        assertEquals(OledAccent.CYAN, state.accent)
        assertFalse(state.keepScreenAwake)
        assertTrue(state.quietLiveData)
        assertEquals(1.0, state.fontScale, 0.0)
        assertFalse(state.highContrast)
        assertFalse(state.driveDetailed)
        assertFalse(state.driveEnergyFlow)
    }

    @Test
    fun readKeepsTheNonSettingsFields() {
        val state = store.read(SettingsUiState(adapterLabel = "OBDLink MX+", versionLabel = "v1"))
        assertEquals("OBDLink MX+", state.adapterLabel)
        assertEquals("v1", state.versionLabel)
    }

    @Test
    fun everyEditIsReadBack() {
        listOf(
            SettingChange.AutoConnect(true),
            SettingChange.NotifyChargeComplete(false),
            SettingChange.NotifyNewCode(false),
            SettingChange.NotifyBatteryLow(true, 15),
            SettingChange.NotifyPackTempHigh(true, 50),
            SettingChange.NotifyMaintenance(true),
            SettingChange.EndOfDriveRecap(true),
            SettingChange.AutoScanCodes(true),
            SettingChange.MetricUnits(true),
            SettingChange.HomeRate(0.14),
            SettingChange.PublicRate(0.45),
            SettingChange.GasPrice(4.29),
            SettingChange.GasMpg(32.5),
            SettingChange.ChargeTarget(80),
            SettingChange.Appearance(AppearanceMode.LIGHT),
            SettingChange.DarkTheme(DarkStyle.SADDLE),
            SettingChange.Accent(OledAccent.VIOLET),
            SettingChange.KeepScreenAwake(true),
            SettingChange.QuietLiveData(false),
            SettingChange.TextSize(1.25),
            SettingChange.HighContrast(true),
            SettingChange.DriveDetailed(true),
            SettingChange.DriveEnergyFlow(true),
        ).forEach(store::apply)
        val s = store.read(SettingsUiState())
        assertTrue(s.autoConnect)
        assertFalse(s.notifyChargingComplete)
        assertFalse(s.notifyNewCode)
        assertTrue(s.notifyBatteryLow)
        assertEquals(15, s.batteryLowPct)
        assertTrue(s.notifyPackTempHigh)
        assertEquals(50, s.packTempHighC)
        assertTrue(s.notifyMaintenance)
        assertTrue(s.endOfDriveRecap)
        assertTrue(s.autoScanCodes)
        assertTrue(s.metricUnits)
        assertEquals(0.14, s.homeRate, 0.0)
        assertEquals(0.45, s.publicRate, 0.0)
        assertEquals(4.29, s.gasPrice, 0.0)
        assertEquals(32.5, s.gasMpg)
        assertEquals(80, s.chargeTargetPct)
        assertEquals(AppearanceMode.LIGHT, s.appearance)
        assertEquals(DarkStyle.SADDLE, s.darkStyle)
        assertEquals(OledAccent.VIOLET, s.accent)
        assertTrue(s.keepScreenAwake)
        assertFalse(s.quietLiveData)
        assertEquals(1.25, s.fontScale, 0.0)
        assertTrue(s.highContrast)
        assertTrue(s.driveDetailed)
        assertTrue(s.driveEnergyFlow)
    }

    @Test
    fun editsLandInTheKeysTheServiceAndClassicDashboardRead() {
        store.apply(SettingChange.NotifyBatteryLow(true, 30))
        store.apply(SettingChange.ChargeTarget(90))
        store.apply(SettingChange.HomeRate(0.12))
        store.apply(SettingChange.AutoConnect(true))
        val alerts = EventNotificationPrefs(prefs)
        assertTrue(alerts.lowSocEnabled())
        assertEquals(30.0, alerts.lowSocThresholdPct(), 0.0)
        assertEquals(90.0, alerts.targetSocPct(), 0.0)
        assertTrue(prefs.getBoolean(AutoConnectController.PREF_AUTO_CONNECT_ENABLED, false))
        // The WebView sees the rate through the bridge snapshot.
        assertEquals(
            "0.12",
            org.json
                .JSONObject(SharedDisplayPrefs(prefs).snapshotJson())
                .get("pricePerKwh")
                .toString(),
        )
    }

    @Test
    fun clearingARateOrTheComparisonCarUnsetsIt() {
        store.apply(SettingChange.GasMpg(30.0))
        store.apply(SettingChange.GasPrice(4.0))
        store.apply(SettingChange.GasMpg(null))
        store.apply(SettingChange.GasPrice(0.0))
        val s = store.read(SettingsUiState())
        assertNull(s.gasMpg)
        assertEquals("not set", s.gasPriceLabel)
    }

    @Test
    fun turningAnAlertOnWithoutNotificationPermissionAsksForIt() {
        granted = false
        store.apply(SettingChange.NotifyNewCode(false))
        assertEquals("turning one off never asks", 0, permissionRequests)
        store.apply(SettingChange.NotifyPackTempHigh(true, 45))
        assertEquals(1, permissionRequests)
        granted = true
        store.apply(SettingChange.NotifyMaintenance(true))
        assertEquals(1, permissionRequests)
    }

    @Test
    fun keepAwakeAndRecapGoThroughTheExperienceDelegate() {
        store.apply(SettingChange.KeepScreenAwake(true))
        store.apply(SettingChange.EndOfDriveRecap(true))
        assertEquals(listOf("awake:true", "recap:true"), experience.calls)
    }

    @Test
    fun anOddStoredTextSizeSnapsToTheNearestOffered() {
        prefs.edit { putString(SharedDisplayPrefs.PREFIX + "fontScale", "1.3") }
        assertEquals(1.25, store.read(SettingsUiState()).fontScale, 0.0)
    }

    /** Records calls and writes the same keys the real delegate does. */
    private inner class FakeExperience : DashboardExperienceCommands {
        val calls = mutableListOf<String>()

        override fun getDashboardExperienceStateJson(): String = "{}"

        override fun setKeepScreenAwakeEnabled(enabled: Boolean) {
            calls += "awake:$enabled"
            prefs.edit { putBoolean(DashboardExperienceHostDelegate.PREF_KEEP_SCREEN_AWAKE, enabled) }
        }

        override fun setTripSummaryEnabled(enabled: Boolean) {
            calls += "recap:$enabled"
            prefs.edit { putBoolean(DashboardExperienceHostDelegate.PREF_TRIP_SUMMARY, enabled) }
        }

        override fun setActiveDashboardView(view: String?) = Unit
    }
}
