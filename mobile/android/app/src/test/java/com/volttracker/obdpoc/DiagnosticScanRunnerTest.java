package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

public class DiagnosticScanRunnerTest {

    @Test
    public void runSweepsProbesAndPublishesScanTelemetry() throws Exception {
        FakeService service = new FakeService();
        FakeEngine engine = new FakeEngine(service);

        new DiagnosticScanRunner(service, engine).run();

        assertEquals("scanning", service.statuses.get(0));
        assertEquals("scan-complete", service.lastStatusState());
        assertEquals("Scan complete for Test adapter", service.lastNotification());

        assertEquals("ATI", engine.commands.get(0));
        assertTrue(engine.commands.contains("ATSP0"));
        assertTrue(engine.commands.contains("03"));
        assertTrue(engine.commands.contains("0902"));
        assertTrue(engine.commands.contains("ATSH7E4"));
        assertTrue(engine.commands.contains("ATSH760"));
        assertEquals("ATSH7DF", engine.commands.get(engine.commands.size() - 1));

        JSONObject telemetry = service.lastTelemetry();
        assertEquals("scan", telemetry.getString("source"));
        assertEquals("Test adapter", telemetry.getString("adapter"));
        assertTrue(telemetry.getBoolean("connected"));
        assertEquals(42.0, telemetry.getDouble("latitude"), 0.001);
        assertEquals(-71.0, telemetry.getDouble("longitude"), 0.001);

        String raw = telemetry.getString("raw");
        assertTrue(raw.contains("adapter: Test adapter"));
        assertTrue(raw.contains("ATI: OK"));
        assertTrue(raw.contains("volt-discovery: restore auto protocol for live + Volt probes"));
        assertTrue(raw.contains("tpms-discovery: ATSH760 candidate TPMS receiver probes"));
    }

    private static final class FakeService extends ObdService {
        final List<String> statuses = new ArrayList<>();
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
        }

        @Override
        void updateNotification(String text) {
            notifications.add(text);
        }

        String lastStatusState() {
            return statuses.get(statuses.size() - 1);
        }

        String lastNotification() {
            return notifications.get(notifications.size() - 1);
        }

        JSONObject lastTelemetry() {
            return telemetry;
        }
    }

    private static final class FakeEngine extends ObdPollingEngine {
        final List<String> commands = new ArrayList<>();

        FakeEngine(ObdService service) {
            super(service);
        }

        @Override
        String sendRecoverableCommand(String command, long timeoutMs) throws IOException {
            commands.add(command);
            return "OK>";
        }

        @Override
        public void appendLocation(JSONObject sample) throws JSONException {
            sample.put("latitude", 42.0);
            sample.put("longitude", -71.0);
        }
    }
}
