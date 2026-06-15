package com.volttracker.obdpoc

import android.content.Context
import com.volttracker.obdpoc.data.ObdLocalStore
import com.volttracker.obdpoc.location.LocationTracker
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Narrow seam between [ObdPollingEngine] (plus its collaborators) and the owning [ObdService].
 *
 * The engine subsystem runs the OBD adapter IO on the service worker thread; this interface declares
 * exactly the state it reads/mutates and the lifecycle hooks it calls back into. Decoupling against
 * the interface removes the Service to Engine compile cycle and lets the engine be exercised against
 * a small fake instead of a fully constructed [ObdService].
 */
interface EngineHost {
    /** Diagnostic record sink for the active session (JSONL field log + SQLite rows). */
    val recorder: SessionRecorder

    /** True while a logging session is running; flipped to false to stop the poll/connect loops. */
    val running: AtomicBoolean

    /** Monitor guarding adapter IO and per-command logging. */
    val ioLock: Any

    /** Local persistence store, or null before [ObdService.onCreate] has run. */
    val localStore: ObdLocalStore?

    /** GPS tracker used to stamp samples with the latest fix, or null when unavailable. */
    val locationTracker: LocationTracker?

    /** Bluetooth state/observability reporter, or null when not registered. */
    val bluetoothObservability: BluetoothStateReporter?

    /** Display name of the active adapter (used in status/notification copy). */
    var activeName: String

    /** Epoch millis the active session started at; 0 when no session is active. */
    var sessionStartedAtMs: Long

    /** True while the host app is in the foreground (gates background-sample accounting). */
    var appInForeground: Boolean

    /** True while the foreground service is active. */
    var foregroundServiceActive: Boolean

    /** Set when the user requests cancellation of an in-progress retry; cleared once observed. */
    var cancelRetryRequested: Boolean

    /** Android context used to resolve the Bluetooth adapter. */
    val androidContext: Context

    fun broadcastStatus(
        state: String?,
        detail: String?,
        blocked: Boolean,
    )

    fun broadcastTelemetry(payload: JSONObject?)

    fun updateNotification(text: String?)

    fun closeSessionLog()

    fun markSessionInactive()

    fun stopSelf()

    fun hasBluetoothConnectPermission(): Boolean

    fun hasBluetoothScanPermission(): Boolean

    fun setLastFailureClass(fc: FailureClass?)

    fun clearLastFailureClass()

    fun maybeRunVoltageProbe(engineRef: ObdPollingEngine?)

    /**
     * Hook for the opt-in auto-DTC-scan (M3): called once on the live-poll path right after a
     * successful connect. The host decides (via [AutoScanController]) whether to run a generic
     * Mode 03 read through [engineRef] and feed any newly-seen codes to the event-notification path.
     * A no-op when the feature is disabled or throttled.
     */
    fun maybeRunAutoDtcScan(engineRef: ObdPollingEngine?)
}
