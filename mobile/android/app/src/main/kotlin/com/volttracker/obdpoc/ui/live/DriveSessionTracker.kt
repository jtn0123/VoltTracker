package com.volttracker.obdpoc.ui.live

import com.volttracker.obdpoc.DriveTotals
import com.volttracker.obdpoc.DriveTotalsFold
import com.volttracker.obdpoc.ui.charge.SocPoint
import com.volttracker.obdpoc.ui.drive.DrivePhase
import com.volttracker.obdpoc.ui.drive.LastDrive
import com.volttracker.obdpoc.ui.drive.usableKwh
import com.volttracker.obdpoc.ui.units.METERS_PER_MILE

/**
 * Running figures for the drive and charge in progress. The distance, energy, peak speed, drive
 * start and charge start come from the service, which keeps them over every sample
 * ([com.volttracker.obdpoc.LiveDriveTotals]) and publishes them on each one: the screen misses the
 * samples sent while it was paused, so a total folded here would lose that window. A sample that
 * carries no published totals is folded here with the same [DriveTotalsFold].
 * The energy a charge has added is worked out here, from the SOC it started at (see [chargeAddedKwh]).
 * A new drive (entering DRIVE from parked/charging) closes the previous one into [lastDrive].
 * Samples are applied once each: anything not newer than the last applied one (a resume
 * replay) is ignored, so backfill can't double-count.
 */
internal class DriveSessionTracker {
    private val fold = DriveTotalsFold()

    var distanceM = 0.0
        private set
    var energyKwh = 0.0
        private set
    var maxKph = 0.0
        private set
    var driveStartedAtMs = 0L
        private set
    var chargeFromSoc: Double? = null
        private set

    /**
     * Energy this charge has put into the pack: the SOC gained times the usable pack energy (the
     * same model as time-to-full and the logged charge sessions), so it always agrees with the
     * SOC on screen. Before two SOC readings exist it falls back to the charger power integrated
     * over time.
     */
    val chargeAddedKwh: Double
        get() {
            val from = chargeFromSoc
            val last = chargeLastSoc
            return if (from != null && last != null) {
                ((last - from) / PERCENT * usableKwh(chargeSohPct)).coerceAtLeast(0.0)
            } else {
                chargeIntegratedKwh
            }
        }

    private var chargeIntegratedKwh = 0.0
    private var chargeLastSoc: Double? = null
    private var chargeSohPct: Double? = null
    var chargeStartedAtMs: Long? = null
        private set
    var lastDrive: LastDrive? = null
        private set

    /** Pack SOC across the charge in progress, oldest first (the Charge tab's session curve). */
    val chargeSocPoints: List<SocPoint> get() = chargePoints.toList()

    private val chargePoints = ArrayList<SocPoint>()
    private var chargePointSpacingMs = CHARGE_POINT_MS

    private var lastAtMs = 0L
    private var phase: DrivePhase? = null
    private var driveClosed = false

    val driveMiles: Double get() = distanceM / METERS_PER_MILE

    /** Trip efficiency, once there's enough distance and energy to mean something. */
    val miPerKwh: Double?
        get() = if (energyKwh >= MIN_ENERGY_KWH && driveMiles >= MIN_MILES) driveMiles / energyKwh else null

    fun durationMs(nowMs: Long): Long =
        if (driveStartedAtMs > 0 &&
            nowMs > driveStartedAtMs
        ) {
            nowMs - driveStartedAtMs
        } else {
            0L
        }

    /** Clears everything for a fresh adapter session. */
    fun reset() {
        fold.reset()
        adopt(DriveTotals())
        driveClosed = false
        chargeIntegratedKwh = 0.0
        chargeLastSoc = null
        chargePoints.clear()
        lastDrive = null
        lastAtMs = 0L
        phase = null
    }

    fun apply(sample: Sample) {
        if (sample.phase == DrivePhase.UNKNOWN) return
        if (sample.atMs <= 0 || sample.atMs <= lastAtMs) return
        val stepS = if (lastAtMs > 0) (sample.atMs - lastAtMs) / MS_PER_S else Double.NaN
        onPhase(sample)
        val published = sample.published
        if (published == null) fold.apply(sample.foldInput())
        adopt(published ?: fold.totals())
        val contiguous = stepS > 0 && stepS <= MAX_STEP_S
        if (contiguous && sample.phase == DrivePhase.CHARGING) {
            sample.chargerKw?.takeIf { it > 0 }?.let { chargeIntegratedKwh += it * stepS / S_PER_HOUR }
        }
        if (sample.phase == DrivePhase.CHARGING) {
            sample.soc?.let { chargeLastSoc = it }
            sample.sohPct?.let { chargeSohPct = it }
        }
        if (sample.phase == DrivePhase.CHARGING) recordChargeSoc(sample)
        lastAtMs = sample.atMs
    }

    private fun adopt(totals: DriveTotals) {
        distanceM = totals.distanceM
        energyKwh = totals.energyKwh
        maxKph = totals.maxKph
        driveStartedAtMs = totals.driveStartedAtMs
        chargeFromSoc = totals.chargeStartSoc
        chargeStartedAtMs = totals.chargeStartedAtMs
    }

    /** Closes the finished drive and clears this screen's own charge figures on a phase change. */
    private fun onPhase(sample: Sample) {
        val previous = phase
        phase = sample.phase
        if (sample.phase == previous) return
        when (sample.phase) {
            DrivePhase.DRIVE -> {
                closeDriveIfAny()
                driveClosed = false
            }
            DrivePhase.CHARGING -> {
                closeDriveIfAny()
                chargeIntegratedKwh = 0.0
                chargeLastSoc = sample.soc
                chargePoints.clear()
                chargePointSpacingMs = CHARGE_POINT_MS
            }
            DrivePhase.PARKED -> closeDriveIfAny()
            DrivePhase.UNKNOWN -> Unit
        }
    }

    /**
     * Keeps a SOC reading every [chargePointSpacingMs]. A long charge halves the list and doubles
     * the spacing whenever it outgrows [MAX_CHARGE_POINTS], so memory stays bounded.
     */
    private fun recordChargeSoc(sample: Sample) {
        val soc = sample.soc ?: return
        val last = chargePoints.lastOrNull()
        if (last != null && sample.atMs - last.atMs < chargePointSpacingMs) return
        chargePoints.add(SocPoint(sample.atMs, soc.toFloat()))
        if (chargePoints.size > MAX_CHARGE_POINTS) {
            val kept = chargePoints.filterIndexed { i, _ -> i % 2 == 0 }
            chargePoints.clear()
            chargePoints.addAll(kept)
            chargePointSpacingMs *= 2
        }
    }

    /** Closes the drive into [lastDrive] once — a later phase change must not re-stamp its end time. */
    private fun closeDriveIfAny() {
        if (!driveClosed && driveMiles >= MIN_MILES) lastDrive = LastDrive(driveMiles, miPerKwh, lastAtMs)
        driveClosed = true
    }

    /** The subset of a telemetry sample the tracker needs. */
    data class Sample(
        val atMs: Long,
        val phase: DrivePhase,
        val speedKph: Double? = null,
        val powerKw: Double? = null,
        val chargerKw: Double? = null,
        val soc: Double? = null,
        val sohPct: Double? = null,
        val lat: Double? = null,
        val lon: Double? = null,
        /** The service's running totals, when the sample carries them. */
        val published: DriveTotals? = null,
    ) {
        fun foldInput() = DriveTotalsFold.Input(atMs, phase, speedKph, powerKw, chargerKw, soc, lat, lon)
    }

    private companion object {
        const val MAX_STEP_S = 10.0
        const val MS_PER_S = 1000.0
        const val S_PER_HOUR = 3600.0
        const val MIN_ENERGY_KWH = 0.05
        const val MIN_MILES = 0.1
        const val CHARGE_POINT_MS = 15_000L
        const val PERCENT = 100.0
        const val MAX_CHARGE_POINTS = 240
    }
}
