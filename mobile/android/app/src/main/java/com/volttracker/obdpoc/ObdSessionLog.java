package com.volttracker.obdpoc;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Writes the per-session {@code .jsonl} field log to disk: one JSON line per command,
 * event, telemetry sample or status change. Extracted from {@link ObdService} so the
 * file IO is isolated from the OBD/session orchestration. This class owns only the
 * file — the database session lifecycle stays in {@code ObdService}.
 */
final class ObdSessionLog {

    private final File logsDir;
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
        File pending = new File(logsDir, "session-" + System.currentTimeMillis() + "-" + mode + ".jsonl");
        try {
            writer = new BufferedWriter(new FileWriter(pending, true));
            file = pending;
            writeLatestPointer(pending);
        } catch (IOException ex) {
            writer = null;
            file = null;
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
        } catch (IOException | JSONException ignored) {
        }
    }

    synchronized void close() {
        if (writer != null) {
            try {
                writer.flush();
                writer.close();
            } catch (IOException ignored) {
            }
        }
        writer = null;
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
