package com.volttracker.obdpoc.data

/** User decisions survive raw retention and keep their embedded keys when merged. */
internal object TripEditKinds {
    val durable =
        setOf(
            ObdTripLabels.EVENT_KIND,
            ObdTripFavorites.EVENT_KIND,
            ObdTripExclusions.EVENT_KIND,
            ObdTripSplits.EVENT_KIND,
        )
}
