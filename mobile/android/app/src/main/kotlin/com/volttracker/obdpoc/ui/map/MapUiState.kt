package com.volttracker.obdpoc.ui.map

/** Per-segment efficiency bucket — drives the route trace color. */
enum class RouteQuality { GREAT, AVERAGE, POOR }

/** How the route trace is colored/decorated, mirroring the legacy map's modes. */
enum class MapViewMode(
    val label: String,
) {
    ROUTES("Routes"),
    HEAT("Heat"),
    STOPS("Stops"),
    EFFICIENCY("Eff"),
}

/** Route sample: real geographic position + per-point telemetry for the modes. */
data class RoutePoint(
    val lat: Double,
    val lon: Double,
    val quality: RouteQuality = RouteQuality.GREAT,
    /** 0..1 speed relative to the trip max — colors the Heat mode. */
    val speedFrac: Float = 0.5f,
    /** True where the car sat still (light, parking) — marked in Stops mode. */
    val stop: Boolean = false,
)

/** One trip in the selector list. */
data class TripEntry(
    val title: String,
    val whenLabel: String,
    val miles: Double,
    val miPerKwh: Double?,
    val selected: Boolean = false,
)

/** Telemetry at the scrubber position along the route. */
data class ScrubberStats(
    val distanceMiles: Double,
    val speedMph: Int,
    val elevationFt: Int,
    val gradePercent: Int,
    val batteryPercent: Int,
    val miPerKwh: Double?,
)

/**
 * Everything the Map screen renders, as one immutable value.
 * Pure data — previewable and screenshot-testable with no service running.
 * The basemap bitmap is supplied separately (ComposedMap) so state stays pure.
 */
data class MapUiState(
    val connected: Boolean = false,
    val statusLabel: String = "No adapter",
    val trips: List<TripEntry> = emptyList(),
    val route: List<RoutePoint> = emptyList(),
    val viewMode: MapViewMode = MapViewMode.EFFICIENCY,
    val tripTitle: String = "",
    val tripSubtitle: String = "",
    val distanceMiles: Double = 0.0,
    val durationLabel: String = "--",
    val avgMph: Int = 0,
    val energyKwh: Double? = null,
    /** 0..1 position of the route scrubber. */
    val scrubberFraction: Float = 0f,
    val scrubberStats: ScrubberStats? = null,
    val speedProfile: List<Float> = emptyList(),
    val elevationProfile: List<Float> = emptyList(),
    /** Signed pack power over the trip: + drive, − regen. */
    val powerProfile: List<Float> = emptyList(),
    /** Battery %% over the trip. */
    val socProfile: List<Float> = emptyList(),
    val pointCount: Int = 0,
) {
    companion object {
        /**
         * Sample state mirroring the demo scenario's "Morning drive" — an
         * east-west loop through Los Feliz / Griffith Park, LA (the same area
         * the browser demo stream synthesizes GPS around).
         */
        val demo =
            MapUiState(
                connected = true,
                statusLabel = "Live · 1 Hz",
                trips =
                    listOf(
                        TripEntry("Morning drive", "Today 11:30 AM", 13.0, 5.8, selected = true),
                        TripEntry("Morning drive", "Yesterday 11:30 AM", 5.5, 5.0),
                        TripEntry("Evening errand", "2 days ago", 5.5, 4.1),
                    ),
                route = DemoRoute.points(),
                viewMode = MapViewMode.EFFICIENCY,
                tripTitle = "Morning drive",
                tripSubtitle = "Today 11:30 AM · OBDLink MX+ · 461 pts",
                distanceMiles = 13.0,
                durationLabel = "40m 01s",
                avgMph = 20,
                energyKwh = 2.8,
                scrubberFraction = 0.52f,
                scrubberStats =
                    ScrubberStats(
                        distanceMiles = 6.6,
                        speedMph = 13,
                        elevationFt = 443,
                        gradePercent = -5,
                        batteryPercent = 63,
                        miPerKwh = 5.1,
                    ),
                speedProfile =
                    listOf(
                        12f,
                        24f,
                        31f,
                        28f,
                        35f,
                        42f,
                        38f,
                        27f,
                        18f,
                        25f,
                        33f,
                        40f,
                        44f,
                        38f,
                        30f,
                        22f,
                        28f,
                        35f,
                        31f,
                        20f,
                    ),
                elevationProfile =
                    listOf(
                        410f,
                        415f,
                        424f,
                        440f,
                        452f,
                        448f,
                        455f,
                        470f,
                        462f,
                        450f,
                        445f,
                        452f,
                        460f,
                        471f,
                        468f,
                        455f,
                        447f,
                        440f,
                        436f,
                        443f,
                    ),
                powerProfile =
                    listOf(
                        8f,
                        14f,
                        18f,
                        12f,
                        20f,
                        26f,
                        22f,
                        6f,
                        -8f,
                        4f,
                        16f,
                        24f,
                        28f,
                        18f,
                        10f,
                        -6f,
                        9f,
                        19f,
                        15f,
                        5f,
                    ),
                socProfile =
                    listOf(
                        68f,
                        67.5f,
                        67f,
                        66.5f,
                        66f,
                        65.4f,
                        64.8f,
                        64.3f,
                        63.9f,
                        63.4f,
                        63f,
                        62.6f,
                        62.1f,
                        61.7f,
                        61.4f,
                        61.6f,
                        61.2f,
                        60.8f,
                        60.5f,
                        60.3f,
                    ),
                pointCount = 461,
            )
    }
}
