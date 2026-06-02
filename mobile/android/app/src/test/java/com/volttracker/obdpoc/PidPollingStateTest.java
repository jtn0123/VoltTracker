package com.volttracker.obdpoc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.volttracker.obdpoc.PidSchedule.PidSpec;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Behavior tests for {@link PidPollingState}: carry-forward age capping (B2) and generalized
 * Mode-01 same-cycle batching beyond Tier-1 (G2).
 *
 * <p>The state object is driven through a {@link CapturingEngine} subclass of {@link
 * ObdPollingEngine} that scripts adapter responses and records every command issued, so a test can
 * assert exactly which round-trips the schedule produced. A {@link ObdService} is brought up via
 * {@link Robolectric#setupService} so {@code service.recorder} (used for the batch-disabled event)
 * and {@code service.ioLock} are live.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PidPollingStateTest {

    private ObdService service;
    private CapturingEngine engine;
    private PidPollingState state;

    @Before
    public void setUp() {
        service = Robolectric.setupService(ObdService.class);
        service.activeName = "Test Adapter";
        service.sessionStartedAtMs = System.currentTimeMillis();
        service.running.set(true);
        engine = new CapturingEngine(service);
        state = new PidPollingState(service, engine);
    }

    @After
    public void tearDown() {
        if (service != null) {
            service.running.set(false);
            try {
                service.onDestroy();
            } catch (RuntimeException ignored) {
                // Foreground service was never started here; safe to ignore.
            }
        }
    }

    // ---- B2: carry-forward age cap --------------------------------------------------

    @Test
    public void carryForwardValueIsServedWhileFresh() throws IOException {
        final long[] nowMs = {1_000L};
        state.setClockForTesting(() -> nowMs[0]);
        engine.responses.put("010D", "41 0D 28\r>");

        // Poll speed once on cycle 0.
        state.runScheduledPolls(specs("010D"), new StringBuilder());

        // Well within the ceiling: value is still served.
        nowMs[0] += PidPollingState.CARRY_FORWARD_MAX_AGE_MS - 1;
        assertNotNull("a fresh carry-forward value must still be served", state.lastRaw("010D"));
    }

    @Test
    public void staleCarryForwardValueIsDroppedPastTheCeiling() throws IOException {
        final long[] nowMs = {1_000L};
        state.setClockForTesting(() -> nowMs[0]);
        engine.responses.put("010D", "41 0D 28\r>");

        state.runScheduledPolls(specs("010D"), new StringBuilder());
        assertNotNull("value should be present immediately after polling", state.lastRaw("010D"));

        // Advance the clock past the carry-forward ceiling: the stalled value must be dropped so
        // the dashboard renders "--" rather than a frozen number.
        nowMs[0] += PidPollingState.CARRY_FORWARD_MAX_AGE_MS + 1;
        assertNull(
                "a stale carry-forward value must be dropped past the cap", state.lastRaw("010D"));
        // Eagerly cleared, so a subsequent read is still null even without re-advancing.
        assertNull("the dropped value must stay dropped", state.lastRaw("010D"));
    }

    @Test
    public void resetClearsCarryForwardTracking() throws IOException {
        final long[] nowMs = {1_000L};
        state.setClockForTesting(() -> nowMs[0]);
        engine.responses.put("010D", "41 0D 28\r>");
        state.runScheduledPolls(specs("010D"), new StringBuilder());

        state.reset();
        // After reset there is no tracked value and no set-time, so lastRaw is null regardless of
        // how much time has elapsed.
        nowMs[0] += PidPollingState.CARRY_FORWARD_MAX_AGE_MS * 10;
        assertNull(state.lastRaw("010D"));
    }

    // ---- G2: generalized Mode-01 same-cycle batching --------------------------------

    @Test
    public void multiTierSameCycleMode01PidsAreBatchedIntoOneRequest() throws IOException {
        state.setMode01BatchSupported(true);
        // SOC (015B, Tier 2) and coolant (0105, Tier 3) both fall due on the same broadcast cycle
        // (cycle 10). They are NOT in the Tier-1 batch set, so the generalized path must group them
        // into a single 01 5B 05 request rather than two separate per-PID reads.
        engine.responses.put("015B05", "41 5B 80 41 05 7B\r>");

        List<PidSpec> due = specs("015B", "0105");
        state.runScheduledPolls(due, new StringBuilder());

        assertTrue(
                "SOC + coolant must be requested as one batched command, got " + engine.commandLog,
                engine.commandLog.contains("015B05"));
        assertFalse(
                "a batched extra cycle must not also send SOC per-PID",
                engine.commandLog.contains("015B"));
        assertFalse(
                "a batched extra cycle must not also send coolant per-PID",
                engine.commandLog.contains("0105"));
        // Both values are carried forward from the single batched response.
        assertNotNull(state.lastRaw("015B"));
        assertNotNull(state.lastRaw("0105"));
    }

    @Test
    public void incompleteExtraBatchFallsBackToPerPidWithoutDisablingTier1() throws IOException {
        state.setMode01BatchSupported(true);
        // The batched reply is missing the coolant (05) frame, so the group batch must fail and the
        // caller must poll both PIDs per-PID. The adapter capability flag must stay enabled — a
        // short reply here is a per-cycle fallback, not a permanent verdict.
        engine.responses.put("015B05", "41 5B 80\r>");
        engine.responses.put("015B", "41 5B 80\r>");
        engine.responses.put("0105", "41 05 7B\r>");

        List<PidSpec> due = specs("015B", "0105");
        state.runScheduledPolls(due, new StringBuilder());

        assertTrue("must attempt the batched request", engine.commandLog.contains("015B05"));
        assertTrue(
                "incomplete batch must fall back to per-PID SOC",
                engine.commandLog.contains("015B"));
        assertTrue(
                "incomplete batch must fall back to per-PID coolant",
                engine.commandLog.contains("0105"));
        // Tier-1 batching support must remain enabled.
        engine.responses.put("010D0C041149", "41 0D 28 41 0C 0F A0 41 04 80 41 11 33 41 49 7F\r>");
        state.runScheduledPolls(specs("010D", "010C", "0104", "0111", "0149"), new StringBuilder());
        assertTrue(
                "Tier-1 batching must still engage after an extra-batch fallback",
                engine.commandLog.contains("010D0C041149"));
    }

    @Test
    public void aSingleExtraMode01PidIsNotBatched() throws IOException {
        state.setMode01BatchSupported(true);
        engine.responses.put("0105", "41 05 7B\r>");
        // Only coolant is due (no second batchable PID), so there is nothing to batch — it is
        // polled per-PID and no multi-PID request is built.
        state.runScheduledPolls(specs("0105"), new StringBuilder());
        assertTrue(engine.commandLog.contains("0105"));
        for (String cmd : engine.commandLog) {
            assertFalse("a single PID must not be sent as a batch: " + cmd, cmd.length() > 4);
        }
    }

    @Test
    public void tier1BatchingIsUnchanged() throws IOException {
        state.setMode01BatchSupported(true);
        engine.responses.put("010D0C041149", "41 0D 28 41 0C 0F A0 41 04 80 41 11 33 41 49 7F\r>");
        state.runScheduledPolls(specs("010D", "010C", "0104", "0111", "0149"), new StringBuilder());
        assertTrue(
                "Tier-1 PIDs must batch into one command",
                engine.commandLog.contains("010D0C041149"));
        assertFalse("must not also poll speed per-PID", engine.commandLog.contains("010D"));
    }

    // ---- helpers --------------------------------------------------------------------

    /** Builds the {@link PidSpec} list for the given commands, preserving SPECS order/header. */
    private static List<PidSpec> specs(String... commands) {
        List<PidSpec> out = new ArrayList<>();
        for (String command : commands) {
            out.add(findSpec(command));
        }
        return out;
    }

    private static PidSpec findSpec(String command) {
        for (PidSpec spec : PidSchedule.SPECS) {
            if (spec.command.equals(command)) {
                return spec;
            }
        }
        throw new AssertionError("no spec for command " + command);
    }

    /**
     * {@link ObdPollingEngine} that records every command and returns scripted responses, so the
     * test can assert the exact round-trips the schedule produced without a real adapter.
     */
    private static final class CapturingEngine extends ObdPollingEngine {
        final List<String> commandLog = Collections.synchronizedList(new ArrayList<>());
        final java.util.Map<String, String> responses = new java.util.HashMap<>();

        CapturingEngine(ObdService service) {
            super(service);
        }

        @Override
        String sendRecoverableCommand(String command, long timeoutMs) {
            commandLog.add(command);
            return responses.getOrDefault(command, ">");
        }

        @Override
        String sendCommand(String command, long timeoutMs) {
            commandLog.add(command);
            return responses.getOrDefault(command, ">");
        }
    }
}
