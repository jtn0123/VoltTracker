package com.volttracker.obdpoc;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Owns the Bluetooth RFCOMM socket to the ELM327 adapter and the low-level command IO. Extracted
 * from {@link ObdService} so socket/stream handling is isolated and the {@link #transact} read loop
 * is unit-testable against in-memory streams.
 *
 * <p>Not {@code final}: {@code ObdPollingEngineTest} subclasses this to script adapter responses
 * (overriding {@link #open}, {@link #transact}, {@link #sendEscape}, {@link #close}) so the
 * polling-engine state machine can be exercised without a real RFCOMM socket.
 */
class ElmConnection {

    /**
     * Indirected wall clock — tests inject a synthetic clock so {@link #transact}'s deadline check
     * is deterministic rather than asserting on wall time (CI-flaky). Defined as a package-private
     * interface rather than {@code java.util.function.LongSupplier} so we don't need API 24 / core
     * library desugaring (minSdk is 23).
     */
    interface Clock {
        long nowMs();
    }

    /**
     * Per-phase wall-clock timings (ms) for the last successful {@link #open} call. Field-readable
     * so {@link ObdPollingEngine} can stamp these onto the {@code socket_open_result} event without
     * needing a side-channel — A1/B3 in the connection-hardening plan.
     *
     * <p>{@link #firstReadMs} is populated by {@link #wakeNudge} (the first read after open) rather
     * than by {@link #open} itself, because the OS rarely surfaces a wedged adapter until the first
     * byte is requested; setting it during {@code wakeNudge} keeps the {@code first_read} label on
     * the right operation. All three default to {@code -1} when not measured.
     */
    long rfcommConnectMs = -1L;

    long getStreamsMs = -1L;
    long firstReadMs = -1L;

    /**
     * Phase the most recent {@link #open}/{@link #wakeNudge} failure occurred in. Mirrors the
     * {@code errorPhase} field on the {@code socket_open_result} event. One of {@code
     * "rfcomm_connect"}, {@code "get_streams"}, {@code "first_read"}, or empty when the call
     * succeeded.
     */
    String lastErrorPhase = "";

    private BluetoothSocket socket;
    private InputStream input;
    private OutputStream output;
    private final Clock clock;

    ElmConnection() {
        this.clock = System::currentTimeMillis;
    }

    /** Test constructor: drives {@link #transact}/{@link #sendEscape} against in-memory streams. */
    ElmConnection(InputStream input, OutputStream output) {
        this(input, output, System::currentTimeMillis);
    }

    /** Test constructor: in-memory streams + injected clock for deterministic timeout tests. */
    ElmConnection(InputStream input, OutputStream output, Clock clock) {
        this.input = input;
        this.output = output;
        this.clock = clock;
    }

    boolean isOpen() {
        return input != null && output != null;
    }

    /**
     * Opens an RFCOMM socket to {@code device}. {@link BluetoothSocket#connect()} has no timeout
     * and can block indefinitely on a dead adapter, so a daemon watchdog closes the socket after
     * {@code connectTimeoutMs}, which makes the blocked connect throw IOException and lets the
     * normal failure path take over.
     */
    void open(BluetoothDevice device, UUID uuid, long connectTimeoutMs) throws IOException {
        rfcommConnectMs = -1L;
        getStreamsMs = -1L;
        firstReadMs = -1L;
        lastErrorPhase = "";

        BluetoothSocket pendingSocket = device.createRfcommSocketToServiceRecord(uuid);
        socket = pendingSocket;
        Thread watchdog =
                new Thread(
                        () -> {
                            sleep(connectTimeoutMs);
                            if (!pendingSocket.isConnected()) {
                                try {
                                    pendingSocket.close();
                                } catch (IOException ignored) {
                                }
                            }
                        });
        watchdog.setDaemon(true);
        watchdog.start();
        long connectStart = clock.nowMs();
        try {
            pendingSocket.connect();
            rfcommConnectMs = Math.max(0L, clock.nowMs() - connectStart);
        } catch (IOException ex) {
            rfcommConnectMs = Math.max(0L, clock.nowMs() - connectStart);
            lastErrorPhase = "rfcomm_connect";
            throw ex;
        } finally {
            watchdog.interrupt();
        }
        long streamsStart = clock.nowMs();
        try {
            input = socket.getInputStream();
            output = socket.getOutputStream();
            getStreamsMs = Math.max(0L, clock.nowMs() - streamsStart);
        } catch (IOException ex) {
            getStreamsMs = Math.max(0L, clock.nowMs() - streamsStart);
            lastErrorPhase = "get_streams";
            throw ex;
        }
    }

    /** Result of a {@link #wakeNudge} call — recorded onto the {@code wake_nudge} JSONL event. */
    static final class WakeNudgeResult {
        final long durationMs;
        final boolean gotResponse;

        WakeNudgeResult(long durationMs, boolean gotResponse) {
            this.durationMs = durationMs;
            this.gotResponse = gotResponse;
        }
    }

    /**
     * Sends a single {@code \r} no-op and waits up to {@code toleranceMs} for any response byte.
     * Some adapters (notably wedged OBDLink MX+ units) need a no-op to spin up before {@code ATZ}
     * lands; on a healthy adapter this returns immediately with {@code gotResponse=true} and on a
     * wedged adapter it returns with {@code gotResponse=false}, which the engine logs as a {@code
     * wake_nudge} event for later triage.
     *
     * <p>Doubles as the {@code first_read} timing source: a failing {@link InputStream#available()}
     * /{@link InputStream#read} surfaces the wedged-adapter symptom that motivated the bucket
     * split. On {@link IOException} the call sets {@link #firstReadMs} + {@link #lastErrorPhase}
     * and re-throws so the engine's failure path can classify it.
     */
    WakeNudgeResult wakeNudge(long toleranceMs) throws IOException {
        if (output == null || input == null) {
            throw new IOException("Adapter stream is not open");
        }
        long start = clock.nowMs();
        boolean gotResponse = false;
        try {
            output.write((byte) '\r');
            output.flush();
            long deadline = start + Math.max(0L, toleranceMs);
            byte[] buffer = new byte[64];
            while (clock.nowMs() < deadline) {
                int available = input.available();
                if (available > 0) {
                    int read = input.read(buffer, 0, Math.min(buffer.length, available));
                    if (read > 0) {
                        gotResponse = true;
                        break;
                    }
                } else {
                    sleep(20);
                }
            }
            firstReadMs = Math.max(0L, clock.nowMs() - start);
            return new WakeNudgeResult(firstReadMs, gotResponse);
        } catch (IOException ex) {
            firstReadMs = Math.max(0L, clock.nowMs() - start);
            lastErrorPhase = "first_read";
            throw ex;
        }
    }

    /**
     * Writes {@code command} and reads the reply until the ELM '>' prompt, the timeout, or {@code
     * keepWaiting} going false. Returns the raw response (possibly partial).
     *
     * @throws IOException if the stream is not open or the socket has broken
     */
    String transact(String command, long timeoutMs, BooleanSupplier keepWaiting)
            throws IOException {
        if (output == null || input == null) {
            throw new IOException("Adapter stream is not open");
        }
        drainInput();
        output.write((command + "\r").getBytes(StandardCharsets.US_ASCII));
        output.flush();

        StringBuilder response = new StringBuilder();
        long deadline = clock.nowMs() + timeoutMs;
        byte[] buffer = new byte[128];
        while (clock.nowMs() < deadline && keepWaiting.getAsBoolean()) {
            int available = input.available();
            if (available > 0) {
                int read = input.read(buffer, 0, Math.min(buffer.length, available));
                if (read > 0) {
                    String chunk = new String(buffer, 0, read, StandardCharsets.US_ASCII);
                    response.append(chunk);
                    if (chunk.indexOf('>') >= 0) {
                        break;
                    }
                }
            } else {
                sleep(25);
            }
        }
        return response.toString();
    }

    /** Sends the ELM escape byte and drains whatever the adapter echoes back. */
    void sendEscape(long settleMs) throws IOException {
        if (output == null || input == null) {
            return;
        }
        output.write(0x1B);
        output.flush();
        sleep(settleMs);
        drainInput();
    }

    void close() {
        try {
            if (input != null) {
                input.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (output != null) {
                output.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        input = null;
        output = null;
        socket = null;
    }

    private void drainInput() throws IOException {
        if (input == null) {
            return;
        }
        byte[] buffer = new byte[128];
        int drained = 0;
        // Cap the drain so a chatty/garbage adapter cannot spin this loop forever.
        while (input.available() > 0 && drained < 8192) {
            int read = input.read(buffer, 0, Math.min(buffer.length, input.available()));
            if (read < 0) {
                break;
            }
            drained += read;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
