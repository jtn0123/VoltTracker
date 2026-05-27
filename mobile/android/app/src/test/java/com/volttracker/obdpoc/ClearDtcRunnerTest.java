package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class ClearDtcRunnerTest {

    @Test
    public void runBroadcastsSuccessForExplicitPositiveResponse() throws Exception {
        FakeService service = new FakeService();
        FakeEngine engine = new FakeEngine(service, "04\r44\r>");

        new ClearDtcRunner(service, engine).run();

        assertEquals("04", engine.command);
        assertEquals(6000L, engine.timeoutMs);
        assertEquals("codes-cleared", service.lastStatusState());
        assertEquals("DTCs cleared on Test adapter", service.lastNotification());
        assertTrue(service.lastTelemetry().getBoolean("clearDtcOk"));
        assertEquals("ok", service.lastTelemetry().getString("clearDtcCode"));
        assertEquals("04 44", service.lastTelemetry().getString("raw"));
    }

    @Test
    public void runPrefersNegativeResponseWhenPayloadAlsoContains44() throws Exception {
        FakeService service = new FakeService();
        FakeEngine engine = new FakeEngine(service, "44\r7F 04 22\r>");

        new ClearDtcRunner(service, engine).run();

        assertEquals("error", service.lastStatusState());
        assertTrue(service.lastStatusDetail().contains("conditions not correct"));
        assertEquals("Clear-codes failed on Test adapter", service.lastNotification());
        assertFalse(service.lastTelemetry().getBoolean("clearDtcOk"));
        assertEquals("22", service.lastTelemetry().getString("clearDtcCode"));
    }

    @Test
    public void runReportsFailureForUnusableReply() throws Exception {
        FakeService service = new FakeService();
        FakeEngine engine = new FakeEngine(service, "NO DATA>");

        new ClearDtcRunner(service, engine).run();

        assertEquals("error", service.lastStatusState());
        assertTrue(service.lastStatusDetail().contains("No usable reply"));
        assertFalse(service.lastTelemetry().getBoolean("clearDtcOk"));
        assertEquals("", service.lastTelemetry().getString("clearDtcCode"));
    }

    @Test
    public void positiveMode04ResponseMustBeExplicitToken() {
        assertTrue(ClearDtcRunner.hasPositiveMode04Response("04 44"));
        assertTrue(ClearDtcRunner.hasPositiveMode04Response("SEARCHING... 44"));

        assertFalse(ClearDtcRunner.hasPositiveMode04Response("7F 04 22"));
        assertFalse(ClearDtcRunner.hasPositiveMode04Response("014400"));
        assertFalse(ClearDtcRunner.hasPositiveMode04Response("NO DATA"));
    }

    @Test
    public void negativeResponseCodeHandlesSpacedAndCompactPayloads() {
        assertEquals("22", ClearDtcRunner.extractNegativeResponseCode("7F 04 22"));
        assertEquals("11", ClearDtcRunner.extractNegativeResponseCode("SEARCHING... 7F0411"));
        assertEquals("", ClearDtcRunner.extractNegativeResponseCode("44"));
    }

    @Test
    public void compactResponsePreservesFrameBoundaries() {
        assertEquals("04 44", ClearDtcRunner.compactResponse("04\r44\r>"));
        assertEquals("7F 04 22", ClearDtcRunner.compactResponse("7F 04 22>"));
    }

    private static final class FakeService extends ObdService {
        final List<String> statuses = new ArrayList<>();
        final List<String> details = new ArrayList<>();
        final List<String> notifications = new ArrayList<>();
        JSONObject telemetry;

        FakeService() {
            activeName = "Test adapter";
        }

        @Override
        void broadcastTelemetry(JSONObject payload) {
            telemetry = payload;
        }

        @Override
        void broadcastStatus(String state, String detail, boolean blocked) {
            statuses.add(state);
            details.add(detail);
        }

        @Override
        void updateNotification(String text) {
            notifications.add(text);
        }

        String lastStatusState() {
            return statuses.get(statuses.size() - 1);
        }

        String lastStatusDetail() {
            return details.get(details.size() - 1);
        }

        String lastNotification() {
            return notifications.get(notifications.size() - 1);
        }

        JSONObject lastTelemetry() {
            return telemetry;
        }
    }

    private static final class FakeEngine extends ObdPollingEngine {
        private final String response;
        String command;
        long timeoutMs;

        FakeEngine(ObdService service, String response) {
            super(service);
            this.response = response;
        }

        @Override
        String sendRecoverableCommand(String command, long timeoutMs) throws IOException {
            this.command = command;
            this.timeoutMs = timeoutMs;
            return response;
        }
    }
}
