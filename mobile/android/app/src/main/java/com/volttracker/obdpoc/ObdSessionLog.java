package com.volttracker.obdpoc;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Writes the per-session {@code .jsonl} field log to disk: one JSON line per command, event,
 * telemetry sample or status change. Extracted from {@link ObdService} so the file IO is isolated
 * from the OBD/session orchestration. This class owns only the file — the database session
 * lifecycle stays in {@code ObdService}.
 *
 * <p>The writer holds onto the underlying {@link FileOutputStream} so {@link #writeDurable} can
 * call {@code getFD().sync()} after flush — that fsync is what lets the on-disk record of an
 * unexpected process death (error rows in particular) survive a crash or sudden power loss.
 */
final class ObdSessionLog {

    private final File logsDir;
    private FileOutputStream fos;
    private BufferedWriter writer;
    private File file;

    ObdSessionLog(File logsDir) {
        this.logsDir = logsDir;
    }

    /** Opens a fresh {@code session-<ts>-<mode>.jsonl}. Leaves the log closed on failure. */
    synchronized void open(String mode) {
        close();
        if (!logsDir.exists() && !logsDir.mkdirs()) {
            return;
        }
        File pending =
                new File(logsDir, "session-" + System.currentTimeMillis() + "-" + mode + ".jsonl");
        try {
            // Append mode: a re-open within the same millisecond + mode shouldn't truncate prior
            // content, even though we expect a fresh file in practice.
            fos = new FileOutputStream(pending, true);
            writer = new BufferedWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8));
            file = pending;
            writeLatestPointer(pending);
        } catch (IOException ex) {
            closeQuietly();
        }
    }

    synchronized boolean isOpen() {
        return writer != null;
    }

    /** The current log file name, or {@code null} when the log is closed. */
    synchronized String fileName() {
        return file == null ? null : file.getName();
    }

    /** Appends one {@code {ts,type,payload,file}} line. A no-op when the log is closed. */
    synchronized void write(String type, JSONObject payload) {
        writeInternal(type, payload, false);
    }

    /**
     * Same as {@link #write} but additionally fsyncs the underlying file descriptor so the line
     * survives a process death or sudden power loss. Use for {@code "error"} rows and other
     * "must-not-lose" diagnostics — regular telemetry stays on the buffered path so the poll loop
     * isn't blocked on disk on every sample.
     */
    synchronized void writeDurable(String type, JSONObject payload) {
        writeInternal(type, payload, true);
    }

    private void writeInternal(String type, JSONObject payload, boolean durable) {
        if (writer == null) {
            return;
        }
        JSONObject line = new JSONObject();
        try {
            line.put("ts", System.currentTimeMillis());
            line.put("type", type);
            line.put("payload", payload);
            if (file != null) {
                line.put("file", file.getName());
            }
            writer.write(line.toString());
            writer.newLine();
            writer.flush();
            if (durable && fos != null) {
                // flush() drains the BufferedWriter into the OutputStream and on to the
                // FileOutputStream's underlying FD — but the FD's bytes are still in the OS page
                // cache. sync() blocks until they hit stable storage.
                try {
                    fos.getFD().sync();
                } catch (IOException ignored) {
                    // Already past flush; nothing more we can do here.
                }
            }
        } catch (IOException | JSONException ignored) {
        }
    }

    synchronized void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        if (writer != null) {
            try {
                writer.flush();
                writer.close();
            } catch (IOException ignored) {
            }
        } else if (fos != null) {
            try {
                fos.close();
            } catch (IOException ignored) {
            }
        }
        writer = null;
        fos = null;
        file = null;
    }

    private void writeLatestPointer(File logFile) {
        File pointer = new File(logsDir, "latest.txt");
        try (BufferedWriter pointerWriter = new BufferedWriter(new FileWriter(pointer, false))) {
            pointerWriter.write(logFile.getName());
            pointerWriter.newLine();
        } catch (IOException ignored) {
        }
    }
}
