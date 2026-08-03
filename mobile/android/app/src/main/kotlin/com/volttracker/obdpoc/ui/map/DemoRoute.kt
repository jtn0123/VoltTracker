package com.volttracker.obdpoc.ui.map

/**
 * FABRICATED demo route for the Map screen's demo/preview state — a
 * hand-authored loop through the Los Feliz / Griffith Park area of Los
 * Angeles (the same neighborhood the browser demo stream synthesizes GPS
 * around). These coordinates are invented for the demo and are NOT copied
 * from any device log; the file is allowlisted by name in
 * tools/privacy-scan-allowlist.txt for exactly that reason.
 */
internal object DemoRoute {
    @Suppress("LongParameterList")
    private fun leg(
        from: LatLng,
        to: LatLng,
        steps: Int,
        quality: RouteQuality,
        speedFrom: Float,
        speedTo: Float,
        stopAtEnd: Boolean = false,
    ): List<RoutePoint> =
        (0 until steps).map { i ->
            val t = i.toFloat() / (steps - 1).coerceAtLeast(1)
            RoutePoint(
                lat = from.lat + (to.lat - from.lat) * t + 0.00035 * kotlin.math.sin(t * 7.0),
                lon = from.lon + (to.lon - from.lon) * t + 0.00035 * kotlin.math.cos(t * 5.0),
                quality = quality,
                speedFrac = speedFrom + (speedTo - speedFrom) * t,
                stop = stopAtEnd && i == steps - 1,
            )
        }

    fun points(): List<RoutePoint> =
        leg(LatLng(34.1090, -118.3108), LatLng(34.1180, -118.3102), 8, RouteQuality.GREAT, 0.25f, 0.55f) +
            leg(
                LatLng(34.1180, -118.3102),
                LatLng(34.1228, -118.2985),
                7,
                RouteQuality.AVERAGE,
                0.55f,
                0.7f,
                stopAtEnd = true,
            ) +
            leg(LatLng(34.1228, -118.2985), LatLng(34.1237, -118.2872), 7, RouteQuality.GREAT, 0.1f, 0.8f) +
            leg(
                LatLng(34.1237, -118.2872),
                LatLng(34.1102, -118.2870),
                7,
                RouteQuality.POOR,
                0.8f,
                0.45f,
                stopAtEnd = true,
            ) +
            leg(LatLng(34.1102, -118.2870), LatLng(34.1107, -118.2790), 7, RouteQuality.GREAT, 0.45f, 0.6f)
}
