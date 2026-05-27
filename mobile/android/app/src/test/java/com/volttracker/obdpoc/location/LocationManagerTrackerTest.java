package com.volttracker.obdpoc.location;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Covers the platform tracker wrapper around {@link LocationFilter}. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LocationManagerTrackerTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
    }

    @Test
    public void acceptedFixIsRoundedStoredAndDelivered() throws Exception {
        LocationManagerTracker tracker = new LocationManagerTracker(context);
        AtomicReference<FilteredLocation> seen = new AtomicReference<>();
        tracker.start(seen::set);

        invokeHandleFix(tracker, fix(32.71571234, -117.16119876, 4.44f, nowMs()));

        FilteredLocation accepted = seen.get();
        assertNotNull(accepted);
        assertEquals(32.715712, accepted.latitude, 0.000001);
        assertEquals(-117.161199, accepted.longitude, 0.000001);
        assertEquals(4.4, accepted.accuracyM, 0.0);
        assertEquals(LocationManager.GPS_PROVIDER, accepted.provider);
        assertEquals(accepted, tracker.getLastLocation());
    }

    @Test
    public void rejectedFixDoesNotReplaceLastLocationOrNotify() throws Exception {
        LocationManagerTracker tracker = new LocationManagerTracker(context);
        AtomicInteger delivered = new AtomicInteger();
        tracker.start(location -> delivered.incrementAndGet());
        long t0 = nowMs();
        invokeHandleFix(tracker, fix(32.7157, -117.1611, 4f, t0));

        FilteredLocation first = tracker.getLastLocation();
        invokeHandleFix(tracker, fix(32.7160, -117.1614, 200f, t0 + 1_000L));

        assertEquals(1, delivered.get());
        assertEquals(first, tracker.getLastLocation());
    }

    @Test
    public void startClearsPreviousSessionLocation() throws Exception {
        LocationManagerTracker tracker = new LocationManagerTracker(context);
        tracker.start(location -> {});
        invokeHandleFix(tracker, fix(32.7157, -117.1611, 4f, nowMs()));
        assertNotNull(tracker.getLastLocation());

        tracker.start(location -> {});

        assertNull(tracker.getLastLocation());
    }

    @Test
    public void stopSuppressesFutureCallbacks() throws Exception {
        LocationManagerTracker tracker = new LocationManagerTracker(context);
        AtomicReference<FilteredLocation> seen = new AtomicReference<>();
        tracker.start(seen::set);
        tracker.stop();

        invokeHandleFix(tracker, fix(32.7157, -117.1611, 4f, nowMs()));

        assertNull(seen.get());
        assertNotNull(tracker.getLastLocation());
    }

    private static Location fix(double lat, double lng, float accuracy, long timeMs) {
        Location location = new Location(LocationManager.GPS_PROVIDER);
        location.setLatitude(lat);
        location.setLongitude(lng);
        location.setAccuracy(accuracy);
        location.setTime(timeMs);
        location.setElapsedRealtimeNanos(timeMs * 1_000_000L);
        return location;
    }

    private static long nowMs() {
        return System.currentTimeMillis();
    }

    private static void invokeHandleFix(LocationManagerTracker tracker, Location location)
            throws Exception {
        Method method = LocationManagerTracker.class.getDeclaredMethod("handleFix", Location.class);
        method.setAccessible(true);
        method.invoke(tracker, location);
    }
}
