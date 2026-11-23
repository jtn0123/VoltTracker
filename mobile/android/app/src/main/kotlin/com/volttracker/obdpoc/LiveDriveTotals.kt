package com.volttracker.obdpoc

import com.volttracker.obdpoc.ui.drive.DrivePhase
import com.volttracker.obdpoc.ui.units.haversineMeters
import org.json.JSONObject

/**
 * The service's running totals for the drive and charge in progress, published on every live
 * sample. The service sees every sample; a dashboard does not, because it stops receiving
 * broadcasts while paused and the resume ring ([LiveDashboardSnapshot]) holds only the last few
 * minutes. Folding these in the UI lost the window between the pause and the start of the ring,
 * so both dashboards now read them from the sample instead.
 */
internal class LiveDriveTotals {
    private val fold = DriveTotalsFold()
    private var phase = DrivePhase.PARKED

    /** Clears everything for a fresh adapter session. */
    @Synchronized
    fun reset() {
        fold.reset()
        phase = DrivePhase.PARKED
    }

    /**
     * Folds [sample] and returns a copy of it that also carries the totals. [sample] itself is
     * left untouched: the persistence worker may still be writing it, and the stored row keeps
     * exactly what was read from the car.
     */
    @Synchronized
    fun publish(sample: JSONObject): JSONObject {
        phase = DrivePhaseRule.next(sample, phase)
        fold.apply(DriveTotalsFold.Input.fromJson(sample, phase))
        val out = JSONObject()
        for (key in sample.keys()) out.put(key, sample.opt(key))
        fold.totals().writeTo(out)
        return out
    }
}

/**
 * Which phase a live sample puts the car in. Charging only when the classifier confirms it from
 * charger status: a positive power reading alone must not override ready/parked/driving or start
 * a plug-in charge session. Driving when it says so, or the car is moving / in a drive gear;
 * parked in P or at rest.
 */
internal object DrivePhaseRule {
    private val DRIVE_GEARS = setOf("D", "L", "R", "N")
    private const val MI_PER_KM = 0.621371

    fun next(
        t: JSONObject,
        current: DrivePhase,
    ): DrivePhase {
        val vehicleState = t.optString("vehicleState", "")
        val gear = t.optString("prndlState", "")
        val speedMph = finite(t, "speedKph")?.let { (it * MI_PER_KM).toInt() } ?: 0
        return when {
            vehicleState == "charging" -> DrivePhase.CHARGING
            vehicleState == "driving_ev" || vehicleState == "driving_gas" -> DrivePhase.DRIVE
            vehicleState == "parked" || gear == VoltGear.PARK -> DrivePhase.PARKED
            speedMph > 0 || gear in DRIVE_GEARS -> DrivePhase.DRIVE
            vehicleState == "ready" || vehicleState == "plugged" || current == DrivePhase.CHARGING -> DrivePhase.PARKED
            else -> current
        }
    }
}

/** The running totals one sample carries: the current drive's, and the current charge's start. */
internal data class DriveTotals(
    val distanceM: Double = 0.0,
    val energyKwh: Double = 0.0,
    val maxKph: Double = 0.0,
    val driveStartedAtMs: Long = 0L,
    val chargeStartSoc: Double? = null,
    val chargeStartedAtMs: Long? = null,
) {
    fun writeTo(out: JSONObject) {
        out.put("driveDistanceM", distanceM)
        out.put("driveEnergyKwh", energyKwh)
        out.put("driveMaxSpeedKph", maxKph)
        out.put("driveStartedAtMs", driveStartedAtMs)
        chargeStartSoc?.let { out.put("chargeStartSoc", it) }
        chargeStartedAtMs?.let { out.put("chargeStartedAtMs", it) }
    }

    companion object {
        /** The sample keys [writeTo] adds: service bookkeeping, not readings from the car. */
        val KEYS =
            setOf(
                "driveDistanceM",
                "driveEnergyKwh",
                "driveMaxSpeedKph",
                "driveStartedAtMs",
                "chargeStartSoc",
                "chargeStartedAtMs",
            )

        /** The totals a service-published sample carries, or null for one that has none. */
        fun fromJson(t: JSONObject): DriveTotals? {
            val distanceM = finite(t, "driveDistanceM") ?: return null
            return DriveTotals(
                distanceM = distanceM,
                energyKwh = finite(t, "driveEnergyKwh") ?: 0.0,
                maxKph = finite(t, "driveMaxSpeedKph") ?: 0.0,
                driveStartedAtMs = t.optLong("driveStartedAtMs", 0L),
                chargeStartSoc = finite(t, "chargeStartSoc"),
                chargeStartedAtMs = if (t.isNull("chargeStartedAtMs")) null else t.optLong("chargeStartedAtMs"),
            )
        }
    }
}

/**
 * Folds live samples into [DriveTotals], with the same rules as the WebView's fallback for
 * samples that carry no totals (`telemetry.ts` recordSampleHistory / accumulateSessionEnergy):
 *  - distance: haversine between GPS fixes while driving, ignoring jitter (< 2 m) and fix jumps
 *    (≥ 250 m);
 *  - energy: pack power integrated between driving samples ≤ 10 s apart, never while plugged in;
 *  - charge: the SOC and time the charge in progress started at.
 * Entering DRIVE from parked/charging starts a new drive. Samples are applied once each: anything
 * not newer than the last applied one (a resume replay) is ignored, so it can't double-count.
 */
internal class DriveTotalsFold {
    private var distanceM = 0.0
    private var energyKwh = 0.0
    private var maxKph = 0.0
    private var driveStartedAtMs = 0L
    private var chargeStartSoc: Double? = null
    private var chargeStartedAtMs: Long? = null
    private var lastAtMs = 0L
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var phase: DrivePhase? = null

    fun totals(): DriveTotals =
        DriveTotals(distanceM, energyKwh, maxKph, driveStartedAtMs, chargeStartSoc, chargeStartedAtMs)

    fun reset() {
        resetDrive()
        chargeStartSoc = null
        chargeStartedAtMs = null
        lastAtMs = 0L
        phase = null
    }

    fun apply(input: Input) {
        if (input.phase == DrivePhase.UNKNOWN) return
        if (input.atMs <= 0 || input.atMs <= lastAtMs) return
        val stepS = if (lastAtMs > 0) (input.atMs - lastAtMs) / MS_PER_S else Double.NaN
        if (input.phase != phase) onPhase(input)
        phase = input.phase
        input.speedKph?.let { if (it > maxKph && input.phase == DrivePhase.DRIVE) maxKph = it }
        addDistance(input)
        val contiguous = stepS > 0 && stepS <= MAX_STEP_S
        if (contiguous && input.phase == DrivePhase.DRIVE && (input.chargerKw ?: 0.0) <= 0.0) {
            input.powerKw?.let { energyKwh += it * stepS / S_PER_HOUR }
        }
        lastAtMs = input.atMs
    }

    private fun onPhase(input: Input) {
        when (input.phase) {
            DrivePhase.DRIVE -> {
                resetDrive()
                driveStartedAtMs = input.atMs
            }
            DrivePhase.CHARGING -> {
                chargeStartSoc = input.soc
                chargeStartedAtMs = input.atMs
            }
            DrivePhase.PARKED, DrivePhase.UNKNOWN -> Unit
        }
    }

    private fun resetDrive() {
        distanceM = 0.0
        energyKwh = 0.0
        maxKph = 0.0
        driveStartedAtMs = 0L
        lastLat = Double.NaN
        lastLon = Double.NaN
    }

    private fun addDistance(input: Input) {
        val lat = input.lat ?: return
        val lon = input.lon ?: return
        if (!lastLat.isNaN() && input.phase == DrivePhase.DRIVE) {
            val step = haversineMeters(lastLat, lastLon, lat, lon)
            if (step >= MIN_STEP_M && step < MAX_STEP_M) distanceM += step
        }
        lastLat = lat
        lastLon = lon
    }

    /** The subset of a live sample the fold needs. */
    data class Input(
        val atMs: Long,
        val phase: DrivePhase,
        val speedKph: Double? = null,
        val powerKw: Double? = null,
        val chargerKw: Double? = null,
        val soc: Double? = null,
        val lat: Double? = null,
        val lon: Double? = null,
    ) {
        companion object {
            fun fromJson(
                t: JSONObject,
                phase: DrivePhase,
            ) = Input(
                atMs = t.optLong("updatedAt", 0L),
                phase = phase,
                speedKph = finite(t, "speedKph"),
                powerKw = finite(t, "powerKw"),
                chargerKw = finite(t, "chargerPowerKw"),
                // The charge's start SOC uses the driver's scale, like the ring and time-to-full.
                soc = finite(t, "displayedSocPct") ?: finite(t, "soc"),
                lat = finite(t, "latitude"),
                lon = finite(t, "longitude"),
            )
        }
    }

    private companion object {
        const val MIN_STEP_M = 2.0
        const val MAX_STEP_M = 250.0
        const val MAX_STEP_S = 10.0
        const val MS_PER_S = 1000.0
        const val S_PER_HOUR = 3600.0
    }
}

private fun finite(
    t: JSONObject,
    key: String,
): Double? =
    if (t.has(key) && !t.isNull(key)) {
        t.optDouble(key).takeIf { it.isFinite() }
    } else {
        null
    }
