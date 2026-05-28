package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.content.Context;
import com.volttracker.obdpoc.data.ObdLocalStore;
import com.volttracker.obdpoc.data.StorageSummaryRecord;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityStorageReadErrorTest {

    private ActivityController<MainActivity> controller;
    private MainActivity activity;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(MainActivity.class).create();
        activity = controller.get();
        if (activity.localStore != null) {
            activity.localStore.close();
        }
        activity.localStore = new ThrowingStore(activity);
    }

    @After
    public void tearDown() {
        if (controller != null) {
            controller.destroy();
        }
    }

    @Test
    public void storageSummaryFailureReturnsStructuredError() throws Exception {
        JSONObject payload = new JSONObject(activity.getStorageSummaryJson());

        assertFalse(payload.optBoolean("ok", true));
        assertEquals("storage_summary_failed", payload.optString("error"));
        assertEquals("Could not read local storage summary.", payload.optString("message"));
    }

    @Test
    public void tripsFailureReturnsStructuredErrorInsteadOfEmptyArray() throws Exception {
        JSONObject payload = new JSONObject(activity.getTripsJson());

        assertFalse(payload.optBoolean("ok", true));
        assertEquals("trips_read_failed", payload.optString("error"));
        assertEquals("Could not read logged trips.", payload.optString("message"));
    }

    @Test
    public void insightsFailureReturnsStructuredError() throws Exception {
        JSONObject payload = new JSONObject(activity.getInsightsJson());

        assertFalse(payload.optBoolean("ok", true));
        assertEquals("insights_read_failed", payload.optString("error"));
        assertEquals("Could not read vehicle insights.", payload.optString("message"));
    }

    private static final class ThrowingStore extends ObdLocalStore {
        ThrowingStore(Context context) {
            super(context);
        }

        @Override
        public StorageSummaryRecord getStorageSummaryRecord() {
            throw new RuntimeException("summary boom");
        }

        @Override
        public JSONArray getTripsJson(int limit) {
            throw new RuntimeException("trips boom");
        }

        @Override
        public JSONObject getInsightsJson() {
            throw new RuntimeException("insights boom");
        }
    }
}
