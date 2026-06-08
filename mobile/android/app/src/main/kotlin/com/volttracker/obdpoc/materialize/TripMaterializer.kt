package com.volttracker.obdpoc.materialize

import com.volttracker.obdpoc.VehicleActivityThresholds
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Conservative trip materializer: takes the location samples logged for a session and groups them
 * into "trip" windows where the device kept moving with continuity. Tunables are deliberately
 * cautious; we'd rather drop a borderline trip than fabricate one.
 */
object TripMaterializer {
    /** All thresholds in one place so they're easy to retune from tests. */
    private object Tunables {
        /** Two samples farther apart than this end the current trip window. */
        const val MAX_GAP_MS: Long = 5L * 60_000L

        /** Sustained no-motion/no-ready telemetry past this point is treated as car-off dwell. */
        const val MAX_INACTIVE_MS: Long = 5L * 60_000L

        /** A low-speed GPS run past this duration is a stop boundary, not drive time. */
        const val MIN_STOP_SPLIT_MS: Long = 3L * 60_000L

        /** GPS segment speed below this is treated as stopped/crawling. */
        const val STOP_SPEED_MPS: Double = 2.5

        /** Split only compact stops; long slow movement remains one drive. */
        const val MAX_STOP_DRIFT_METERS: Double = 300.0

        /** OBD speed above this is enough evidence that the vehicle is in a drive window. */
        const val MOVING_SPEED_KPH: Double = VehicleActivityThresholds.MOVING_SPEED_KPH

        /** RPM above this means the car is awake even if the GPS speed is momentarily zero. */
        const val ENGINE_READY_RPM: Int = VehicleActivityThresholds.ENGINE_READY_RPM

        /** 12V bus above this suggests the DC-DC converter is active, not a sleeping adapter. */
        const val READY_VOLTAGE: Double = VehicleActivityThresholds.READY_VOLTAGE

        /** Small pack-power noise is ignored; above this the car is doing real drivetrain work. */
        const val ACTIVE_POWER_KW: Double = VehicleActivityThresholds.ACTIVE_POWER_KW

        /** Pack-current fallback for rows where power_kw is unavailable. */
        const val ACTIVE_PACK_CURRENT_A: Double = VehicleActivityThresholds.ACTIVE_PACK_CURRENT_A

        /**
         * Hard upper bound on a single trip window. Protects against pathological
         * sparse-but-continuous sessions becoming multi-day "trips" with a fictitious integrated
         * energy number.
         */
        const val MAX_TRIP_DURATION_MS: Long = 12L * 60L * 60_000L

        /** Below this distance, the window is not a real trip. */
        const val MIN_DISTANCE_METERS: Double = 100.0

        /** Below this duration, the window is not a real trip. */
        const val MIN_DURATION_MS: Long = 30_000L

        /** At this sample count or more the materializer reports [Confidence.OBSERVED]. */
        const val OBSERVED_SAMPLE_COUNT: Int = 10

        /** At this sample count or more the trip is considered to have a renderable route. */
        const val HAS_ROUTE_SAMPLE_COUNT: Int = 5

        /** Avg speed below this is classified as [Trip.CLASSIFICATION_CITY]. */
        const val CITY_AVG_SPEED_KPH: Double = 50.0

        /** Avg speed above this is classified as [Trip.CLASSIFICATION_HIGHWAY]. */
        const val HIGHWAY_AVG_SPEED_KPH: Double = 80.0
    }

    /** Splits the session's location samples into zero or more trips and returns them. */
    @JvmStatic
    fun materialize(
        input: MaterializerInput?,
        data: MaterializerData?,
    ): List<Trip> {
        val result = mutableListOf<Trip>()
        if (input == null || data == null) {
            return result
        }
        val samples = data.readLocationSamples(input.sessionId)
        if (samples.isNullOrEmpty()) {
            return result
        }
        val telemetry = data.readTelemetrySamples(input.sessionId)
        val inactiveSpans = splitSpans(samples, telemetry)

        var window = mutableListOf<LocationSample>()
        for (sample in samples) {
            if (insideInactiveSpan(sample.capturedAtMs, inactiveSpans)) {
                appendTripsCapped(result, window, telemetry)
                window = mutableListOf()
                continue
            }
            if (window.isNotEmpty()) {
                val gap = sample.capturedAtMs - window.last().capturedAtMs
                if (gap > Tunables.MAX_GAP_MS) {
                    appendTripsCapped(result, window, telemetry)
                    window = mutableListOf()
                }
            }
            window.add(sample)
        }
        if (window.isNotEmpty()) {
            appendTripsCapped(result, window, telemetry)
        }
        return result
    }

    private fun splitSpans(
        samples: List<LocationSample>,
        telemetry: List<TelemetrySample>?,
    ): List<InactiveSpan> {
        val spans = gpsStopSpans(samples).toMutableList()
        spans.addAll(inactiveSpans(telemetry))
        spans.sortBy { it.startMs }

        val merged = mutableListOf<InactiveSpan>()
        for (span in spans) {
            if (merged.isEmpty()) {
                merged.add(span)
                continue
            }
            val last = merged.last()
            if (span.startMs <= last.endMs) {
                merged[merged.lastIndex] = InactiveSpan(last.startMs, max(last.endMs, span.endMs))
            } else {
                merged.add(span)
            }
        }
        return merged
    }

    private fun gpsStopSpans(samples: List<LocationSample>?): List<InactiveSpan> {
        val spans = mutableListOf<InactiveSpan>()
        if (samples == null || samples.size < 2) {
            return spans
        }
        var runStart = -1
        for (i in 1 until samples.size) {
            val previous = samples[i - 1]
            val sample = samples[i]
            val seconds = max(1.0, (sample.capturedAtMs - previous.capturedAtMs) / 1000.0)
            val speedMps =
                haversineMeters(
                    previous.latitude,
                    previous.longitude,
                    sample.latitude,
                    sample.longitude,
                ) / seconds
            if (speedMps < Tunables.STOP_SPEED_MPS) {
                if (runStart < 0) {
                    runStart = i - 1
                }
            } else if (runStart >= 0) {
                appendGpsStopSpan(spans, samples, runStart, i - 1)
                runStart = -1
            }
        }
        if (runStart >= 0) {
            appendGpsStopSpan(spans, samples, runStart, samples.lastIndex)
        }
        return spans
    }

    private fun appendGpsStopSpan(
        spans: MutableList<InactiveSpan>,
        samples: List<LocationSample>,
        startIndex: Int,
        endIndex: Int,
    ) {
        val startMs = samples[startIndex].capturedAtMs
        val endMs = samples[endIndex].capturedAtMs
        val stoppedAtMs = samples[min(startIndex + 1, endIndex)].capturedAtMs
        if (endMs - stoppedAtMs >= Tunables.MIN_STOP_SPLIT_MS &&
            pathMeters(samples, startIndex, endIndex) <= Tunables.MAX_STOP_DRIFT_METERS
        ) {
            spans.add(InactiveSpan(startMs, endMs))
        }
    }

    private fun pathMeters(
        samples: List<LocationSample>,
        startIndex: Int,
        endIndex: Int,
    ): Double {
        var meters = 0.0
        for (i in startIndex + 1..endIndex) {
            val previous = samples[i - 1]
            val sample = samples[i]
            meters +=
                haversineMeters(
                    previous.latitude,
                    previous.longitude,
                    sample.latitude,
                    sample.longitude,
                )
        }
        return meters
    }

    private fun inactiveSpans(telemetry: List<TelemetrySample>?): List<InactiveSpan> {
        val spans = mutableListOf<InactiveSpan>()
        if (telemetry.isNullOrEmpty()) {
            return spans
        }
        var inactiveStart = -1L
        var inactiveEnd = -1L
        for (sample in telemetry) {
            if (isActiveVehicleSample(sample)) {
                appendInactiveSpanIfLongEnough(spans, inactiveStart, inactiveEnd)
                inactiveStart = -1L
                inactiveEnd = -1L
            } else {
                if (inactiveStart < 0L) {
                    inactiveStart = sample.capturedAtMs
                }
                inactiveEnd = sample.capturedAtMs
            }
        }
        appendInactiveSpanIfLongEnough(spans, inactiveStart, inactiveEnd)
        return spans
    }

    private fun appendInactiveSpanIfLongEnough(
        spans: MutableList<InactiveSpan>,
        startMs: Long,
        endMs: Long,
    ) {
        if (startMs >= 0L && endMs >= startMs && endMs - startMs > Tunables.MAX_INACTIVE_MS) {
            spans.add(InactiveSpan(startMs, endMs))
        }
    }

    private fun insideInactiveSpan(
        atMs: Long,
        spans: List<InactiveSpan>,
    ): Boolean {
        for (span in spans) {
            if (atMs > span.startMs && atMs <= span.endMs) {
                return true
            }
        }
        return false
    }

    private fun isActiveVehicleSample(sample: TelemetrySample?): Boolean {
        if (sample == null) {
            return false
        }
        val speedKph = sample.speedKph
        if (speedKph != null && speedKph > Tunables.MOVING_SPEED_KPH) {
            return true
        }
        val rpm = sample.rpm
        if (rpm != null && rpm > Tunables.ENGINE_READY_RPM) {
            return true
        }
        val adapterVoltage = sample.adapterVoltage
        if (adapterVoltage != null && adapterVoltage > Tunables.READY_VOLTAGE) {
            return true
        }
        val powerKw = sample.powerKw
        if (powerKw != null && abs(powerKw) > Tunables.ACTIVE_POWER_KW) {
            return true
        }
        val packCurrentA = sample.packCurrentA
        return packCurrentA != null && abs(packCurrentA) > Tunables.ACTIVE_PACK_CURRENT_A
    }

    /**
     * Adds zero or one trip per recursion. If the window exceeds MAX_TRIP_DURATION_MS, splits at
     * the largest internal gap and recurses on both halves so the cap holds even for long runs.
     */
    private fun appendTripsCapped(
        result: MutableList<Trip>,
        window: List<LocationSample>,
        telemetry: List<TelemetrySample>?,
    ) {
        if (window.size < 2) {
            return
        }
        val duration = window.last().capturedAtMs - window.first().capturedAtMs
        if (duration <= Tunables.MAX_TRIP_DURATION_MS) {
            buildTrip(window, telemetry)?.let(result::add)
            return
        }

        var splitIndex = -1
        var largestGap = -1L
        val midpoint = window.size / 2
        for (i in 1 until window.size) {
            val gap = window[i].capturedAtMs - window[i - 1].capturedAtMs
            val tieBreakWin =
                gap == largestGap &&
                    (splitIndex < 0 || abs(i - midpoint) < abs(splitIndex - midpoint))
            if (gap > largestGap || tieBreakWin) {
                largestGap = gap
                splitIndex = i
            }
        }
        if (splitIndex <= 0) {
            buildTrip(window, telemetry)?.let(result::add)
            return
        }
        appendTripsCapped(result, ArrayList(window.subList(0, splitIndex)), telemetry)
        appendTripsCapped(result, ArrayList(window.subList(splitIndex, window.size)), telemetry)
    }

    private fun buildTrip(
        window: List<LocationSample>,
        telemetry: List<TelemetrySample>?,
    ): Trip? {
        if (window.size < 2) {
            return null
        }
        var distance = 0.0
        var previous: LocationSample? = null
        for (sample in window) {
            val previousSample = previous
            if (previousSample != null) {
                distance +=
                    haversineMeters(
                        previousSample.latitude,
                        previousSample.longitude,
                        sample.latitude,
                        sample.longitude,
                    )
            }
            previous = sample
        }
        val startedAtMs = window.first().capturedAtMs
        val endedAtMs = window.last().capturedAtMs
        val durationMs = max(0L, endedAtMs - startedAtMs)
        if (distance < Tunables.MIN_DISTANCE_METERS || durationMs < Tunables.MIN_DURATION_MS) {
            return null
        }
        val maxSpeedKph = maxSpeedInWindow(telemetry, startedAtMs, endedAtMs, window)
        val confidence =
            if (window.size >= Tunables.OBSERVED_SAMPLE_COUNT) {
                Confidence.OBSERVED
            } else {
                Confidence.WEAK
            }
        val hasRoute = window.size >= Tunables.HAS_ROUTE_SAMPLE_COUNT
        val avgSpeedKph =
            if (durationMs > 0L) {
                (distance / 1000.0) / (durationMs / 3_600_000.0)
            } else {
                0.0
            }
        val classification = classify(avgSpeedKph)
        val energyKwh = integrateEnergyKwh(telemetry, startedAtMs, endedAtMs)
        return Trip(
            startedAtMs,
            endedAtMs,
            distance,
            durationMs,
            maxSpeedKph,
            window.size,
            hasRoute,
            confidence,
            classification,
            energyKwh,
        )
    }

    /**
     * Buckets the trip's average speed into a human-readable regime label. A trip with no usable
     * speed at all falls back to [Trip.CLASSIFICATION_UNKNOWN].
     */
    @JvmStatic
    fun classify(avgSpeedKph: Double): String {
        if (!(avgSpeedKph > 0.0)) {
            return Trip.CLASSIFICATION_UNKNOWN
        }
        if (avgSpeedKph < Tunables.CITY_AVG_SPEED_KPH) {
            return Trip.CLASSIFICATION_CITY
        }
        if (avgSpeedKph >= Tunables.HIGHWAY_AVG_SPEED_KPH) {
            return Trip.CLASSIFICATION_HIGHWAY
        }
        return Trip.CLASSIFICATION_MIXED
    }

    private fun integrateEnergyKwh(
        telemetry: List<TelemetrySample>?,
        startMs: Long,
        endMs: Long,
    ): Double? {
        if (telemetry.isNullOrEmpty()) {
            return null
        }
        var prevPowerKw: Double? = null
        var prevAtMs: Long? = null
        var energyKwh = 0.0
        var integrated = 0
        for (sample in telemetry) {
            if (sample.capturedAtMs < startMs || sample.capturedAtMs > endMs) {
                continue
            }
            val powerKw = samplePowerKw(sample) ?: continue
            val previousPowerKw = prevPowerKw
            val previousAtMs = prevAtMs
            if (previousPowerKw != null && previousAtMs != null) {
                val hours = (sample.capturedAtMs - previousAtMs) / 3_600_000.0
                if (hours > 0.0) {
                    energyKwh += ((previousPowerKw + powerKw) / 2.0) * hours
                    integrated += 1
                }
            }
            prevPowerKw = powerKw
            prevAtMs = sample.capturedAtMs
        }
        return if (integrated == 0) null else energyKwh
    }

    private fun samplePowerKw(sample: TelemetrySample): Double? {
        sample.powerKw?.let {
            return it
        }
        val packVoltage = sample.packVoltage
        val packCurrentA = sample.packCurrentA
        return if (packVoltage != null && packCurrentA != null) {
            packVoltage * packCurrentA / 1000.0
        } else {
            null
        }
    }

    private fun maxSpeedInWindow(
        telemetry: List<TelemetrySample>?,
        startMs: Long,
        endMs: Long,
        window: List<LocationSample>,
    ): Double {
        var maxSpeed = 0.0
        if (telemetry != null) {
            for (sample in telemetry) {
                if (sample.capturedAtMs < startMs || sample.capturedAtMs > endMs) {
                    continue
                }
                val speedKph = sample.speedKph
                if (speedKph != null && speedKph > maxSpeed) {
                    maxSpeed = speedKph
                }
            }
        }
        if (maxSpeed == 0.0) {
            for (sample in window) {
                val speedMps = sample.speedMps
                if (speedMps != null) {
                    val kph = speedMps * 3.6
                    if (kph > maxSpeed) {
                        maxSpeed = kph
                    }
                }
            }
        }
        return maxSpeed
    }

    private fun haversineMeters(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double,
    ): Double {
        val earthMeters = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a =
            sin(dLat / 2.0) * sin(dLat / 2.0) +
                cos(Math.toRadians(lat1)) *
                cos(Math.toRadians(lat2)) *
                sin(dLng / 2.0) *
                sin(dLng / 2.0)
        return earthMeters * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    }

    private class InactiveSpan(
        val startMs: Long,
        val endMs: Long,
    )
}
