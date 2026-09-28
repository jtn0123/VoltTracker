package com.volttracker.obdpoc.ui.live

import com.volttracker.obdpoc.VoltGear
import com.volttracker.obdpoc.ui.VoltAppUiState
import com.volttracker.obdpoc.ui.drive.DriveMode
import com.volttracker.obdpoc.ui.drive.DrivePhase
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.drive.TirePressures
import com.volttracker.obdpoc.ui.drive.chargeEta
import com.volttracker.obdpoc.ui.drive.chargeLevelLabel
import com.volttracker.obdpoc.ui.drive.durationLabel
import com.volttracker.obdpoc.ui.theme.AppearanceMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.util.Locale

/**
 * Folds the service's JSON telemetry/status payloads (the same ones the WebView
 * dashboard receives) into the immutable [VoltAppUiState] the Compose screens
 * render. Pure JVM — no Android dependencies — so the mapping and the trace
 * rings are unit-testable without a device.
 *
 * Field names follow the dashboard telemetry contract (see
 * `dashboard-src/js/telemetry.ts` AUTHORITATIVE_READING_KEYS): base OBD keys are
 * typed on [com.volttracker.obdpoc.TelemetryPayload]; enhanced readings ride in
 * extras under the same names the JS consumes.
 */
class LiveUiStateStore {
    private val _state = MutableStateFlow(VoltAppUiState())
    val state: StateFlow<VoltAppUiState> = _state

    private val speedTrace = ArrayDeque<Float>()
    private val powerTrace = ArrayDeque<Float>()
    private val gasTrace = ArrayDeque<Boolean>()
    private val session = DriveSessionTracker()
    private var trackedPhase = DrivePhase.PARKED
    private val socTrace = ArrayDeque<Float>()
    private var socTraceLastSampleAt = 0L

    /** `setStatus` payload: connection state, adapter, detail. */
    fun onStatus(payload: JSONObject) {
        val stateName = payload.optString("state", "").lowercase(Locale.US)
        val connected = stateName == "connected" || stateName == "demo"
        val transitioning = stateName in TRANSITION_STATES
        val adapter = payload.optString("adapter", "").ifBlank { "--" }
        val label = statusLabel(stateName, adapter)
        // A fresh link starts a fresh session: the trip and charge figures restart with it.
        if (connected && !_state.value.drive.connected) session.reset()
        _state.value =
            _state.value.let { s ->
                s.copy(
                    drive =
                        s.drive.copy(
                            connected = connected,
                            connecting = transitioning,
                            statusLabel = label,
                            adapterLabel = adapter,
                        ),
                    charge = s.charge.copy(connected = connected, statusLabel = label),
                    map = s.map.copy(connected = connected, statusLabel = label),
                    insights = s.insights.copy(connected = connected, statusLabel = label),
                    diag = s.diag.copy(connected = connected, statusLabel = label, adapterLabel = adapter),
                    settings =
                        s.settings.copy(
                            connected = connected,
                            statusLabel = label,
                            adapterLabel = adapter,
                            demoActive = demoAfterStatus(stateName, s.settings.demoActive),
                        ),
                )
            }
    }

    /** One `updateTelemetry` sample: advances the Drive screen and its traces. */
    fun onTelemetry(payload: JSONObject) {
        appendTraces(payload)
        val next = withSample(_state.value, payload)
        val demo = payload.optString("source", "") == DEMO_SOURCE
        _state.value =
            if (next.settings.demoActive == demo) next else next.copy(settings = next.settings.copy(demoActive = demo))
    }

    // The demo session reports "demo" while starting, then "connected" like a real link; its
    // samples are tagged source=demo, so a connected status keeps whatever the samples said.
    private fun demoAfterStatus(
        stateName: String,
        current: Boolean,
    ): Boolean =
        when (stateName) {
            "demo" -> true
            "connected" -> current
            else -> false
        }

    /**
     * A `backfillTelemetry` batch, oldest first: rebuilds traces without
     * animating tiles. Replaces ring history — resume replays the whole
     * service snapshot, so appending would duplicate every prior sample.
     */
    fun onTelemetryBackfill(samples: List<JSONObject>) {
        if (samples.isEmpty()) return
        speedTrace.clear()
        powerTrace.clear()
        gasTrace.clear()
        socTrace.clear()
        socTraceLastSampleAt = 0L
        samples.forEach(::appendTraces)
        samples.lastOrNull()?.let { last -> _state.value = withSample(_state.value, last) }
    }

    /**
     * Folds one sample into Drive, and mirrors the pack SOC and EV range onto the Charge hero so
     * it shows the live pack instead of its "0%" / "0 mi range" defaults.
     */
    private fun withSample(
        s: VoltAppUiState,
        t: JSONObject,
    ): VoltAppUiState {
        val drive = mapDrive(s.drive, t)
        return s.copy(
            drive = drive,
            charge = s.charge.copy(socPercent = drive.socPercent, evRangeMiles = drive.evRangeMiles),
        )
    }

    private fun appendTraces(t: JSONObject) {
        trackSession(t)
        optDouble(t, "speedKph")?.let { push(speedTrace, kmToMi(it).toFloat(), TRACE_CAP) }
        optDouble(t, "powerKw")?.let {
            push(powerTrace, it.toFloat(), POWER_TRACE_CAP)
            gasTrace.addLast(engineRunning(t, _state.value.drive.mode))
            while (gasTrace.size > POWER_TRACE_CAP) gasTrace.removeFirst()
        }
        val soc = optDouble(t, "soc")
        val at = t.optLong("updatedAt", 0L)
        // SOC moves slowly; sample it at most every SOC_SAMPLE_MS so the session
        // trace spans a useful window instead of 30 near-identical seconds.
        if (soc != null && (at - socTraceLastSampleAt >= SOC_SAMPLE_MS || socTrace.isEmpty())) {
            push(socTrace, soc.toFloat(), SOC_TRACE_CAP)
            socTraceLastSampleAt = at
        }
    }

    /** Advances the phase and the running trip/charge figures by one sample (live or replayed). */
    private fun trackSession(t: JSONObject) {
        val speedMph = optDouble(t, "speedKph")?.let { kmToMi(it).toInt() } ?: 0
        trackedPhase = phaseOf(t, speedMph, trackedPhase)
        session.apply(
            DriveSessionTracker.Sample(
                atMs = t.optLong("updatedAt", 0L),
                phase = trackedPhase,
                speedKph = optDouble(t, "speedKph"),
                powerKw = optDouble(t, "powerKw"),
                chargerKw = optDouble(t, "chargerPowerKw"),
                soc = optDouble(t, "soc"),
                lat = optDouble(t, "latitude"),
                lon = optDouble(t, "longitude"),
            ),
        )
    }

    private fun mapDrive(
        current: DriveUiState,
        t: JSONObject,
    ): DriveUiState {
        val rpm = optDouble(t, "rpm")?.toInt() ?: current.rpm
        val mode = if (engineRunning(t, current.mode)) DriveMode.GAS else DriveMode.EV
        val speedMph = optDouble(t, "speedKph")?.let { kmToMi(it).toInt() } ?: current.speedMph
        val phase = trackedPhase
        val soc = optDouble(t, "soc") ?: current.socPercent
        val at = t.optLong("updatedAt", 0L)
        val chargerKw = optDouble(t, "chargerPowerKw")
        val charging = phase == DrivePhase.CHARGING
        val acVolts = optDouble(t, "chargerAcVoltage")
        return current.copy(
            phase = phase,
            powerKw = optDouble(t, "powerKw") ?: current.powerKw,
            speedMph = speedMph,
            speedTrace = speedTrace.toList(),
            powerTrace = powerTrace.toList(),
            gasTrace = gasTrace.toList(),
            socTrace = socTrace.toList(),
            socPercent = soc,
            displayedSocPercent = optDouble(t, "displayedSocPct") ?: current.displayedSocPercent,
            evRangeMiles = evRangeMiles(t, current.evRangeMiles),
            fuelPercent = fresh(t, "fuelLevelPct", "fuelLevelStaleMs", current.fuelPercent),
            gasRangeMiles =
                fresh(
                    t,
                    "fuelRangeKm",
                    "rangeStaleMs",
                    current.gasRangeMiles?.let { it / MI_PER_KM },
                )?.let(::kmToMi),
            packTempF = optDouble(t, "batteryTemp")?.let { cToF(it).toInt() } ?: current.packTempF,
            packVolts = optDouble(t, "packVoltage") ?: current.packVolts,
            packAmps = optDouble(t, "packCurrentA") ?: current.packAmps,
            mode = mode,
            rpm = rpm,
            auxVolts = optDouble(t, "controlModuleVoltage") ?: optDouble(t, "voltage") ?: current.auxVolts,
            aux12Volts = fresh(t, "aux12vVoltage", "aux12vStaleMs", current.aux12Volts),
            aux12SocPercent = fresh(t, "aux12vSocPct", "aux12vStaleMs", current.aux12SocPercent?.toDouble())?.toInt(),
            aux12Amps = fresh(t, "aux12vCurrentA", "aux12vStaleMs", current.aux12Amps),
            coolantF = optDouble(t, "coolantC")?.let { cToF(it).toInt() } ?: current.coolantF,
            gpsAccuracyFt = optDouble(t, "accuracyM")?.let { (it * FT_PER_M).toInt() } ?: current.gpsAccuracyFt,
            ambientF = optDouble(t, "outsideTempC")?.let { cToF(it).toInt() } ?: current.ambientF,
            gear = gearText(t),
            motorAKw = optDouble(t, "motorAPowerKw") ?: current.motorAKw,
            motorBKw = optDouble(t, "motorBPowerKw") ?: current.motorBKw,
            motorTempF = optDouble(t, "motorTempC")?.let { cToF(it).toInt() } ?: current.motorTempF,
            inverterTempF = optDouble(t, "inverterTempC")?.let { cToF(it).toInt() } ?: current.inverterTempF,
            cabinTempF =
                fresh(t, "cabinTempEstC", "climateStaleMs", current.cabinTempF?.let { fToC(it.toDouble()) })
                    ?.let { cToF(it).toInt() },
            transTempF = optDouble(t, "transmissionTempC")?.let { cToF(it).toInt() } ?: current.transTempF,
            torqueNm = optDouble(t, "engineTorqueNm")?.toInt() ?: current.torqueNm,
            oilLifePct = optDouble(t, "engineOilLifePct")?.toInt() ?: current.oilLifePct,
            tires = tires(t, current.tires),
            locked = lockState(t, current.locked),
            cellSpreadMv = optDouble(t, "cellBalanceMv") ?: current.cellSpreadMv,
            minCellVolts = optDouble(t, "minCellVoltage") ?: current.minCellVolts,
            maxCellVolts = optDouble(t, "maxCellVoltage") ?: current.maxCellVolts,
            minCellNumber = optDouble(t, "minCellNumber")?.toInt() ?: current.minCellNumber,
            cellVoltages = cellVoltages(t) ?: current.cellVoltages,
            chargeKw = if (charging) chargerKw ?: 0.0 else 0.0,
            chargeAcVolts = if (charging) acVolts else null,
            chargeAcAmps = if (charging) optDouble(t, "chargerAcCurrentA") else null,
            chargeLevel =
                if (charging) {
                    chargeLevelLabel(
                        t.optString("chargingLevel", "").ifBlank { null },
                        acVolts,
                    )
                } else {
                    null
                },
            chargeFromSoc = session.chargeFromSoc,
            chargeAddedKwh = session.chargeAddedKwh,
            chargeStartedAtMs = session.chargeStartedAtMs,
            chargeEta = if (charging) chargeEta(optDouble(t, "soc"), chargerKw, optDouble(t, "sohPct")) else null,
            sampleAtMs = if (at > 0) at else current.sampleAtMs,
            signalCount = signalCount(t),
            tripMiles = session.driveMiles,
            tripDuration = durationLabel(session.durationMs(at)),
            tripMaxMph = kmToMi(session.maxKph).toInt(),
            tripMiPerKwh = session.miPerKwh,
            tripKwh = session.energyKwh.takeIf { it >= MIN_TRIP_KWH },
            cycleEvPercent = cycleEvPercent(t) ?: current.cycleEvPercent,
            cycleMpg = cycleMpg(t) ?: current.cycleMpg,
            lastDrive = session.lastDrive,
        )
    }

    /**
     * Engine state for this sample: the classifier's verdict when it gives one, else rpm above
     * [GAS_RPM_FLOOR] (only when no classifier state is present), else the prior mode.
     */
    private fun engineRunning(
        t: JSONObject,
        currentMode: DriveMode,
    ): Boolean {
        val vehicleState = t.optString("vehicleState", "")
        val rpm = optDouble(t, "rpm")
        return when {
            vehicleState == "driving_gas" -> true
            vehicleState.isNotBlank() -> false
            // Low or missing rpm with no classifier verdict keeps the prior mode rather than guessing.
            rpm != null && rpm > GAS_RPM_FLOOR -> true
            else -> currentMode == DriveMode.GAS
        }
    }

    /**
     * Charging when the classifier says so or the charger draws a real Level-1-or-better load;
     * driving when it says so, or the car is moving / in a drive gear; parked in P or at rest.
     */
    private fun phaseOf(
        t: JSONObject,
        speedMph: Int,
        current: DrivePhase,
    ): DrivePhase {
        val vehicleState = t.optString("vehicleState", "")
        val gear = t.optString("prndlState", "")
        val chargerKw = optDouble(t, "chargerPowerKw") ?: 0.0
        return when {
            vehicleState == "charging" || chargerKw >= MIN_CHARGE_KW -> DrivePhase.CHARGING
            vehicleState == "driving_ev" || vehicleState == "driving_gas" -> DrivePhase.DRIVE
            vehicleState == "parked" || gear == VoltGear.PARK -> DrivePhase.PARKED
            speedMph > 0 || gear in DRIVE_GEARS -> DrivePhase.DRIVE
            else -> current
        }
    }

    /**
     * A reading that ages out: absent keeps the prior value like every other tile, but a
     * broadcast older than [BROADCAST_STALE_MS] clears it so the screen shows "not reported"
     * instead of an old figure.
     */
    private fun fresh(
        t: JSONObject,
        key: String,
        staleKey: String,
        current: Double?,
    ): Double? {
        val ageMs = optDouble(t, staleKey)
        if (ageMs != null && ageMs > BROADCAST_STALE_MS) return null
        return optDouble(t, key) ?: current
    }

    private fun tires(
        t: JSONObject,
        current: TirePressures?,
    ): TirePressures? {
        val ageMs = optDouble(t, "tirePressureStaleMs")
        if (ageMs != null && ageMs > BROADCAST_STALE_MS) return null
        val psi =
            listOf("Fl", "Fr", "Rl", "Rr").map { corner ->
                optDouble(t, "tirePressure${corner}Kpa")?.times(PSI_PER_KPA) ?: return current
            }
        return TirePressures(psi[0], psi[1], psi[2], psi[3])
    }

    private fun lockState(
        t: JSONObject,
        current: Boolean?,
    ): Boolean? {
        val ageMs = optDouble(t, "doorLockStaleMs")
        if (ageMs != null && ageMs > BROADCAST_STALE_MS) return null
        return when (t.optString("doorLockState", "").lowercase(Locale.US)) {
            "locked" -> true
            "unlocked" -> false
            "" -> current
            else -> null
        }
    }

    private fun cellVoltages(t: JSONObject): List<Double?>? {
        val array = t.optJSONArray("cellVoltages") ?: return null
        return (0 until array.length()).map { i ->
            if (array.isNull(i)) null else array.optDouble(i).takeIf { it.isFinite() }
        }
    }

    /** Share of the car's since-charge miles driven on electricity (SW-CAN drive cycle). */
    private fun cycleEvPercent(t: JSONObject): Int? {
        val ev = optDouble(t, "cycleEvDistanceKm") ?: return null
        val gas = optDouble(t, "cycleFuelDistanceKm") ?: return null
        if (ev + gas <= 0) return null
        return (ev / (ev + gas) * PERCENT).toInt()
    }

    /** Miles per gallon over the car's since-charge cycle, once it has burned measurable fuel. */
    private fun cycleMpg(t: JSONObject): Double? {
        val ev = optDouble(t, "cycleEvDistanceKm") ?: return null
        val gas = optDouble(t, "cycleFuelDistanceKm") ?: return null
        val litres = optDouble(t, "cycleFuelUsedL")?.takeIf { it >= MIN_CYCLE_FUEL_L } ?: return null
        return kmToMi(ev + gas) / (litres / LITRES_PER_GALLON)
    }

    /** Live readings in this sample (keys with a value, minus bookkeeping and staleness ages). */
    private fun signalCount(t: JSONObject): Int =
        t.keys().asSequence().count { key ->
            key !in META_KEYS && !key.endsWith("StaleMs") && !t.isNull(key)
        }

    /** Updates the Settings tab's app-update section (status/available/progress). */
    fun onUpdateState(
        statusLabel: String?,
        availableTag: String?,
        downloadPercent: Int?,
    ) {
        _state.value =
            _state.value.let { s ->
                s.copy(
                    settings =
                        s.settings.copy(
                            updateStatusLabel = statusLabel,
                            updateAvailableTag = availableTag,
                            updateDownloadPercent = downloadPercent,
                        ),
                )
            }
    }

    /** Applies the Settings → Appearance choice (the theme follows it immediately). */
    fun onAppearance(mode: AppearanceMode) {
        _state.value =
            _state.value.let { s -> s.copy(settings = s.settings.copy(appearance = mode)) }
    }

    /** Applies the persisted Drive view choices: Focus vs Detailed, and the energy-flow card. */
    fun onDrivePrefs(
        detailed: Boolean,
        energyFlow: Boolean,
    ) {
        _state.value =
            _state.value.let { s ->
                s.copy(
                    drive = s.drive.copy(detailed = detailed),
                    settings = s.settings.copy(driveEnergyFlow = energyFlow),
                )
            }
    }

    /** Stamps the installed-version line shown in Settings. */
    fun onVersionLabel(label: String) {
        _state.value =
            _state.value.let { s -> s.copy(settings = s.settings.copy(versionLabel = label)) }
    }

    private fun statusLabel(
        stateName: String,
        adapter: String,
    ): String =
        when (stateName) {
            "connected" -> "Live · 1 Hz"
            "demo" -> "Demo"
            "connecting", "initializing", "reconnecting" -> "Connecting…"
            "scanning", "scan-complete" -> "Scanning…"
            else -> if (adapter == "--") "No adapter" else "Idle · $adapter"
        }

    private fun push(
        ring: ArrayDeque<Float>,
        value: Float,
        cap: Int,
    ) {
        ring.addLast(value)
        while (ring.size > cap) ring.removeFirst()
    }

    private fun optDouble(
        payload: JSONObject,
        key: String,
    ): Double? =
        if (payload.has(key) && !payload.isNull(key)) {
            payload.optDouble(key).takeIf { it.isFinite() }
        } else {
            null
        }

    /**
     * The car's own EV range estimate (2241A6 `evRangeKm`), in miles. A stale read
     * (older than [EV_RANGE_STALE_MS]) clears it rather than presenting an old
     * estimate; an absent key keeps the prior value like every other tile. The
     * distance driven this cycle (`evDistanceThisCycleKm`) is never used here.
     */
    private fun evRangeMiles(
        t: JSONObject,
        current: Double?,
    ): Double? {
        val ageMs = optDouble(t, "evRangeStaleMs")
        if (ageMs != null && ageMs > EV_RANGE_STALE_MS) return null
        return optDouble(t, "evRangeKm")?.takeIf { it >= 0.0 }?.let(::kmToMi) ?: current
    }

    /**
     * The gear from this sample alone. Unlike the numeric tiles it never carries the last letter
     * forward: a sample with no gear reading, or one older than [VoltGear.FRESH_MS], reads
     * [NO_GEAR] rather than a gear the car may have left.
     */
    private fun gearText(t: JSONObject): String {
        val letter = t.optString("prndlState", "")
        val ageMs = optDouble(t, "prndlStateStaleMs") ?: 0.0
        if (letter.isBlank() || ageMs > VoltGear.FRESH_MS) return NO_GEAR
        return VoltGear.displayText(letter, if (t.isNull("prndlRaw")) null else t.optInt("prndlRaw"))
    }

    private fun kmToMi(km: Double): Double = km * MI_PER_KM

    private fun cToF(c: Double): Double = c * 9.0 / 5.0 + 32.0

    private fun fToC(f: Double): Double = (f - 32.0) * 5.0 / 9.0

    private companion object {
        const val DEMO_SOURCE = "demo"
        val TRANSITION_STATES = setOf("connecting", "initializing", "reconnecting", "scanning", "scan-complete")
        const val TRACE_CAP = 30

        /** The cockpit power strip spans the last minute at 1 Hz. */
        const val POWER_TRACE_CAP = 60
        const val SOC_TRACE_CAP = 60
        const val SOC_SAMPLE_MS = 30_000L

        /** Mirrors telemetry.ts EV_RANGE_STALE_MS. */
        const val EV_RANGE_STALE_MS = 120_000.0
        const val GAS_RPM_FLOOR = 300
        const val MIN_CHARGE_KW = 0.5
        const val MIN_TRIP_KWH = 0.05
        const val MIN_CYCLE_FUEL_L = 0.1
        const val LITRES_PER_GALLON = 3.785411784
        const val PERCENT = 100.0
        const val PSI_PER_KPA = 0.145038

        /** SW-CAN broadcasts and cluster ranges older than this read as "not reported". */
        const val BROADCAST_STALE_MS = 120_000.0
        val DRIVE_GEARS = setOf("D", "L", "R", "N")
        val META_KEYS =
            setOf(
                "source",
                "connected",
                "adapter",
                "sampleCount",
                "sessionMs",
                "supportedPids",
                "updatedAt",
                "raw",
                "vehicleState",
                "vehicleStateConfidence",
                "vehicleStateReasons",
                "provider",
                "locationProvider",
                "gearConfidence",
                "throttleSource",
                "doorLockSource",
            )

        /** Matches [DriveUiState.gear]'s empty default. */
        const val NO_GEAR = "--"
        const val MI_PER_KM = 0.621371
        const val FT_PER_M = 3.28084
    }
}
