package com.volttracker.obdpoc.location

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLocationManager
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Extends the framework [ShadowLocationManager] with a toggle that makes every
 * `requestLocationUpdates` call throw, simulating a device where all providers fail to
 * subscribe (the stock shadow auto-creates providers and never throws).
 */
@Implements(LocationManager::class)
class FailingLocationManagerShadow : ShadowLocationManager() {
    @Implementation
    override fun requestLocationUpdates(
        provider: String,
        minTime: Long,
        minDistance: Float,
        listener: LocationListener,
    ) {
        if (failRequests) {
            throw IllegalArgumentException("test: provider $provider unavailable")
        }
        super.requestLocationUpdates(provider, minTime, minDistance, listener)
    }

    companion object {
        var failRequests: Boolean = false
    }
}

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
    fun startWithoutPermissionParksListenerUntilResumeAfterGrant() {
        denyLocationPermissions()
        val tracker = LocationManagerTracker(context)
        tracker.start { }

        assertEquals(
            "no permission yet, so no provider may be subscribed",
            0,
            requestedListenerCount(),
        )
        assertFalse("still no permission, resume must not start updates", tracker.resumeUpdatesIfPermitted())

        grantLocationPermissions()
        assertTrue("grant + resume must begin GPS updates", tracker.resumeUpdatesIfPermitted())
        assertTrue("providers should now be subscribed", requestedListenerCount() > 0)
    }

    @Test
    fun resumeIsANoOpWhenAlreadyRunningOrNeverStarted() {
        val neverStarted = LocationManagerTracker(context)
        assertFalse("no parked listener, nothing to resume", neverStarted.resumeUpdatesIfPermitted())

        val running = LocationManagerTracker(context)
        grantLocationPermissions()
        running.start { }
        assertFalse("updates already requested at start", running.resumeUpdatesIfPermitted())
    }

    @Test
    @Config(sdk = [34], shadows = [FailingLocationManagerShadow::class])
    fun resumeRetriesAfterBothProvidersFailedToSubscribe() {
        FailingLocationManagerShadow.failRequests = true
        try {
            grantLocationPermissions()
            val tracker = LocationManagerTracker(context)
            tracker.start { }
            assertEquals("both providers threw, so nothing is subscribed", 0, requestedListenerCount())

            // The providers recover: resume must not be suppressed by a stale
            // updates-active flag left over from the all-failed attempt.
            FailingLocationManagerShadow.failRequests = false
            assertTrue("resume must retry after a total subscription failure", tracker.resumeUpdatesIfPermitted())
            assertTrue("providers should now be subscribed", requestedListenerCount() > 0)
        } finally {
            FailingLocationManagerShadow.failRequests = false
        }
    }

    @Test
    fun stopAfterDeferredStartClearsTheParkedListener() {
        denyLocationPermissions()
        val tracker = LocationManagerTracker(context)
        tracker.start { }
        tracker.stop()

        grantLocationPermissions()
        assertFalse("stop must clear the parked listener", tracker.resumeUpdatesIfPermitted())
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

    private fun denyLocationPermissions() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    private fun grantLocationPermissions() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    private fun requestedListenerCount(): Int {
        val manager = context.getSystemService(LocationManager::class.java)
        return shadowOf(manager).requestLocationUpdateListeners.size
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
