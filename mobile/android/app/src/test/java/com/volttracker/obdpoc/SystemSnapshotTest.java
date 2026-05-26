package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Verifies that {@link SystemSnapshot#collect} produces the expected diagnostic keys and resolves
 * {@code lastSuccessfulSessionMs} against {@link SessionSummaryStore}'s recent rows.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SystemSnapshotTest {

    private Context context;
    private File summaryFile;

    @Before
    public void setUp() throws IOException {
        context = RuntimeEnvironment.getApplication();
        File tmp = Files.createTempDirectory("snapshot").toFile();
        summaryFile = new File(new File(tmp, "obd-logs"), "sessions-summary.jsonl");
    }

    @After
    public void tearDown() {
        SessionSummaryStore.resetForTests();
    }

    @Test
    public void collectIncludesExpectedKeys() {
        JSONObject snap = SystemSnapshot.collect(context, null);

        assertTrue("must include Android SDK level", snap.has("androidSdk"));
        assertTrue("must include Android release", snap.has("androidRelease"));
        assertTrue("must include device manufacturer", snap.has("deviceManufacturer"));
        assertTrue("must include device model", snap.has("deviceModel"));
        assertTrue("must include app version name", snap.has("appVersionName"));
        assertTrue("must include app version code", snap.has("appVersionCode"));
        assertTrue("must include BT adapter availability flag", snap.has("btAdapterAvailable"));
        assertTrue("must include process uptime", snap.has("processUptimeMs"));
    }

    @Test
    public void collectOmitsLastSuccessfulSessionMsWhenStoreIsNull() {
        JSONObject snap = SystemSnapshot.collect(context, null);
        assertFalse(
                "without a store, the last-successful key should be absent",
                snap.has("lastSuccessfulSessionMs"));
    }

    @Test
    public void collectFillsLastSuccessfulFromStore() {
        SessionSummaryStore store = new SessionSummaryStore(summaryFile);
        store.recordStart(1_000L, "A", "AA");
        store.recordEnd(2_000L, SessionSummary.OUTCOME_FAILED, 0, "INSTANT_DROP");
        store.recordStart(3_000L, "A", "AA");
        store.recordEnd(4_000L, SessionSummary.OUTCOME_SUCCESS, 7, null);
        store.recordStart(5_000L, "A", "AA");
        store.recordEnd(6_000L, SessionSummary.OUTCOME_ABORTED, 0, null);

        JSONObject snap = SystemSnapshot.collect(context, store);
        assertNotNull(snap);
        assertEquals(
                "should resolve to the most recent SUCCESS, not the latest session of any kind",
                4_000L,
                snap.optLong("lastSuccessfulSessionMs"));
    }

    @Test
    public void collectReturnsZeroLastSuccessfulWhenNoSuccess() {
        SessionSummaryStore store = new SessionSummaryStore(summaryFile);
        store.recordStart(1L, "A", "AA");
        store.recordEnd(2L, SessionSummary.OUTCOME_FAILED, 0, "BT_OFF");

        JSONObject snap = SystemSnapshot.collect(context, store);
        assertEquals(0L, snap.optLong("lastSuccessfulSessionMs"));
    }

    @Test
    public void collectWithNullContextDoesNotThrow() {
        JSONObject snap = SystemSnapshot.collect(null, null);
        assertNotNull(snap);
        // Android version info is from Build.* statics, so always populated.
        assertTrue(snap.has("androidSdk"));
        // App version comes from the context — absent when context is null.
        assertFalse(snap.has("appVersionName"));
    }
}
