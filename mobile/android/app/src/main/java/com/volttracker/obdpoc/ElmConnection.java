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
 */
final class ElmConnection {

    private BluetoothSocket socket;
    private InputStream input;
    private OutputStream output;

    ElmConnection() {}

    /** Test constructor: drives {@link #transact}/{@link #sendEscape} against in-memory streams. */
    ElmConnection(InputStream input, OutputStream output) {
        this.input = input;
        this.output = output;
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
        try {
            pendingSocket.connect();
        } finally {
            watchdog.interrupt();
        }
        input = socket.getInputStream();
        output = socket.getOutputStream();
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
        long deadline = System.currentTimeMillis() + timeoutMs;
        byte[] buffer = new byte[128];
        while (System.currentTimeMillis() < deadline && keepWaiting.getAsBoolean()) {
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
