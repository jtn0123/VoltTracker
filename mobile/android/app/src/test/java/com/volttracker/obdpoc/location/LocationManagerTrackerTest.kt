package com.volttracker.obdpoc.location

import android.content.Context
import android.location.Location
import android.location.LocationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Covers the platform tracker wrapper around [LocationFilter]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationManagerTrackerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun acceptedFixIsRoundedStoredAndDelivered() {
        val tracker = LocationManagerTracker(context)
        val seen = AtomicReference<FilteredLocation>()
        tracker.start { seen.set(it) }

        invokeHandleFix(tracker, fix(32.71571234, -117.16119876, 4.44f, nowMs()))

        val accepted = seen.get()
        assertNotNull(accepted)
        assertEquals(32.715712, accepted.latitude, 0.000001)
        assertEquals(-117.161199, accepted.longitude, 0.000001)
        assertEquals(4.4, accepted.accuracyM!!, 0.0)
        assertEquals(LocationManager.GPS_PROVIDER, accepted.provider)
        assertEquals(accepted, tracker.getLastLocation())
    }

    @Test
    fun rejectedFixDoesNotReplaceLastLocationOrNotify() {
        val tracker = LocationManagerTracker(context)
        val delivered = AtomicInteger()
        tracker.start { delivered.incrementAndGet() }
        val t0 = nowMs()
        invokeHandleFix(tracker, fix(32.7157, -117.1611, 4f, t0))

        val first = tracker.getLastLocation()
        invokeHandleFix(tracker, fix(32.7160, -117.1614, 200f, t0 + 1_000L))

        assertEquals(1, delivered.get())
        assertEquals(first, tracker.getLastLocation())
    }

    @Test
    fun startClearsPreviousSessionLocation() {
        val tracker = LocationManagerTracker(context)
        tracker.start { }
        invokeHandleFix(tracker, fix(32.7157, -117.1611, 4f, nowMs()))
        assertNotNull(tracker.getLastLocation())

        tracker.start { }

        assertNull(tracker.getLastLocation())
    }

    @Test
    fun stopSuppressesFutureCallbacks() {
        val tracker = LocationManagerTracker(context)
        val seen = AtomicReference<FilteredLocation>()
        tracker.start { seen.set(it) }
        tracker.stop()

        invokeHandleFix(tracker, fix(32.7157, -117.1611, 4f, nowMs()))

        assertNull(seen.get())
        assertNotNull(tracker.getLastLocation())
    }

    private companion object {
        private fun fix(
            lat: Double,
            lng: Double,
            accuracy: Float,
            timeMs: Long,
        ): Location {
            val location = Location(LocationManager.GPS_PROVIDER)
            location.latitude = lat
            location.longitude = lng
            location.accuracy = accuracy
            location.time = timeMs
            location.elapsedRealtimeNanos = timeMs * 1_000_000L
            return location
        }

        private fun nowMs(): Long = System.currentTimeMillis()

        private fun invokeHandleFix(
            tracker: LocationManagerTracker,
            location: Location,
        ) {
            val method =
                LocationManagerTracker::class.java.getDeclaredMethod("handleFix", Location::class.java)
            method.isAccessible = true
            method.invoke(tracker, location)
        }
    }
}
