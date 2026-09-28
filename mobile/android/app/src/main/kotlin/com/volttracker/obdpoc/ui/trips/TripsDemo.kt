package com.volttracker.obdpoc.ui.trips

import com.volttracker.obdpoc.ui.units.METERS_PER_MILE
import kotlin.math.cos
import kotlin.math.sin

/**
 * FABRICATED demo trips for the Trips tab's demo/preview state. The route is a hand-authored
 * loop through the Los Feliz / Griffith Park area of Los Angeles (the same neighborhood the
 * browser demo stream synthesizes GPS around). These coordinates are invented for the demo and
 * are NOT copied from any device log; the file is allowlisted by name in
 * tools/privacy-scan-allowlist.txt for exactly that reason.
 */
internal object TripsDemo {
    const val MIXED_KEY = "demo:4"

    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR
    private const val WIGGLE = 0.00035
    private const val LEG_STEPS = 8
    private const val FIRST_FILLER_DAY = 5
    private const val LAST_FILLER_DAY = 58
    private const val DAYS_IN_MONTH = 30
    private const val WEEK = 7
    private val WEEKEND = setOf(2, 3)
    private const val BASE_MI_PER_KWH = 3.6
    private const val EFF_STEPS = 5
    private const val EFF_STEP = 0.2
    private const val MIN_PER_MILE = 0.9
    private const val ERRAND_MILES = 6.5
    private const val ERRAND_STEPS = 4
    private const val LAST_MONTH_GAS_EVERY = 4
    private const val LAST_MONTH_COMMUTE_SHARE = 0.6

    /** Days ago → (miles, EV share) of the longer drives that ran the engine. */
    private val LONG_DRIVES =
        mapOf(
            12 to (64.0 to 0.55),
            19 to (96.0 to 0.4),
            33 to (120.0 to 0.3),
            40 to (82.0 to 0.45),
            47 to (150.0 to 0.25),
            54 to (70.0 to 0.5),
        )

    /** One demo drive: start before "now", duration, miles, kWh, EV share, label. */
    private class Drive(
        val key: String,
        val agoMs: Long,
        val durationMs: Long,
        val miles: Double,
        val kwh: Double?,
        val evShare: Double,
        val label: String = "",
    )

    // Apr 30 9:42 PM "now": today 8:14 AM, yesterday 5:42 / 7:08 PM, Apr 28 7:22 AM, …
    private val drives =
        listOf(
            Drive("demo:1", 13 * HOUR + 28 * MIN, 28 * MIN, 18.4, 4.49, 1.0, label = "Commute"),
            Drive("demo:2", DAY + 2 * HOUR + 34 * MIN, 14 * MIN, 7.3, 1.83, 1.0),
            Drive("demo:3", DAY + 4 * HOUR, 14 * MIN, 6.1, 1.56, 1.0),
            Drive(MIXED_KEY, 2 * DAY + 14 * HOUR + 20 * MIN, 2 * HOUR + 53 * MIN, 184.2, 10.2, 0.21, "Tahoe weekend"),
            Drive("demo:5", 3 * DAY + 11 * HOUR + 42 * MIN, 47 * MIN, 22.0, 6.11, 1.0),
            Drive("demo:6", 4 * DAY + 3 * HOUR + 12 * MIN, 26 * MIN, 9.8, 2.28, 1.0),
        )

    /**
     * About two months of everyday driving behind the featured drives, so the month and year
     * figures (Trips header, Insights) have a realistic history: weekday commutes, weekend
     * errands, and a few longer drives that ran the engine — more of those last month.
     */
    private val filler: List<Drive> =
        (FIRST_FILLER_DAY..LAST_FILLER_DAY).flatMap { day ->
            val lastMonth = day > DAYS_IN_MONTH
            val eff = BASE_MI_PER_KWH + (day % EFF_STEPS) * EFF_STEP
            val long = LONG_DRIVES[day]
            when {
                long != null -> {
                    val (miles, share) = long
                    listOf(
                        Drive(
                            "demo:f$day",
                            day * DAY - 2 * HOUR,
                            (miles * MIN_PER_MILE).toLong() * MIN,
                            miles,
                            miles * share / eff,
                            share,
                        ),
                    )
                }
                day % WEEK in WEEKEND -> {
                    val miles = ERRAND_MILES + (day % ERRAND_STEPS) * 2.3
                    listOf(Drive("demo:f$day", day * DAY - HOUR, (miles * 2.2).toLong() * MIN, miles, miles / eff, 1.0))
                }
                else -> {
                    val share = if (lastMonth && day % LAST_MONTH_GAS_EVERY == 0) LAST_MONTH_COMMUTE_SHARE else 1.0
                    listOf(
                        Drive(
                            "demo:f$day:am",
                            day * DAY + 2 * HOUR,
                            27 * MIN,
                            18.2,
                            18.2 * share / eff,
                            share,
                            "Commute",
                        ),
                        Drive("demo:f$day:pm", day * DAY - 6 * HOUR, 31 * MIN, 18.9, 18.9 * share / eff, share),
                    )
                }
            }
        }

    private val history = drives + filler

    fun trips(nowMs: Long): List<TripSummary> =
        history.map { d ->
            val start = nowMs - d.agoMs
            TripSummary(
                routeKey = d.key,
                startedAtMs = start,
                endedAtMs = start + d.durationMs,
                distanceMeters = d.miles * METERS_PER_MILE,
                energyKwh = d.kwh,
                evShare = d.evShare,
                label = d.label,
            )
        }

    /** The loop, timed across the drive; the mixed drive switches to gas after its EV share. */
    fun route(
        key: String,
        nowMs: Long,
    ): TripRoute? {
        val trip = trips(nowMs).firstOrNull { it.routeKey == key } ?: return null
        val shape = if (history.indexOfFirst { it.key == key } % 2 == 0) loop() else loop().reversed()
        // Switch to gas once the drive's electric share of the track is behind it, at a point placed
        // exactly there so the map's "Engine on" mark reads the same EV miles as the summary chip.
        val track = if (trip.mode == TripMode.EV) shape.map { it to false } else splitAt(shape, trip.evShare ?: 1.0)
        val last = (track.size - 1).coerceAtLeast(1)
        val points =
            track.mapIndexed { i, (at, gas) ->
                TripPoint(
                    lat = at.first,
                    lon = at.second,
                    atMs = trip.startedAtMs + (trip.endedAtMs - trip.startedAtMs) * i / last,
                    gas = gas,
                )
            }
        return TripRoute(key, points)
    }

    /** [shape] with a point inserted [share] of the way along it; the points past it are gas. */
    private fun splitAt(
        shape: List<Pair<Double, Double>>,
        share: Double,
    ): List<Pair<Pair<Double, Double>, Boolean>> {
        val along =
            shape.zipWithNext().runningFold(0.0) { sum, (a, b) ->
                sum + distanceMiles(TripPoint(a.first, a.second, 0L), TripPoint(b.first, b.second, 0L))
            }
        val at = along.last() * share
        val next = along.indexOfFirst { it > at }.takeIf { it > 0 } ?: return shape.map { it to false }
        val t = (at - along[next - 1]) / (along[next] - along[next - 1])
        val (a, b) = shape[next - 1] to shape[next]
        val split = Pair(a.first + (b.first - a.first) * t, a.second + (b.second - a.second) * t)
        return shape.take(next).map { it to false } + (split to true) + shape.drop(next).map { it to true }
    }

    private fun loop(): List<Pair<Double, Double>> =
        leg(34.1090, -118.3108, 34.1180, -118.3102) +
            leg(34.1180, -118.3102, 34.1228, -118.2985) +
            leg(34.1228, -118.2985, 34.1237, -118.2872) +
            leg(34.1237, -118.2872, 34.1102, -118.2870) +
            leg(34.1102, -118.2870, 34.1107, -118.2790)

    private fun leg(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
    ): List<Pair<Double, Double>> =
        (0 until LEG_STEPS).map { i ->
            val t = i.toDouble() / (LEG_STEPS - 1)
            Pair(
                fromLat + (toLat - fromLat) * t + WIGGLE * sin(t * 7.0),
                fromLon + (toLon - fromLon) * t + WIGGLE * cos(t * 5.0),
            )
        }
}
