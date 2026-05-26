package com.volttracker.obdpoc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Process-wide append-only log file shared across all sessions. Used as the destination for {@link
 * OBDLog#mirror(RollingAppLog)} so logcat events also land on disk for inclusion in the diagnostics
 * share zip.
 *
 * <p>One log file lives at {@code files/app-log/app.log}; once it crosses 7 days old it's rolled to
 * {@code app.log.1} (overwriting any previous rolled file — we keep at most one). The cap is
 * intentionally small: an app log this app's size shouldn't approach megabytes in a week, and
 * keeping only one rolled file simplifies the diagnostics-zip contents.
 *
 * <p>All public methods are synchronized so cross-thread callers (poll loop, main thread, the BT
 * receiver) can write without coordination.
 */
final class RollingAppLog {

    /** Rotate the live log file when it's at least this old. */
    static final long ROTATE_AGE_MS = 7L * 24L * 60L * 60L * 1000L;

    private static final String LIVE_NAME = "app.log";
    private static final String ROLLED_NAME = "app.log.1";
    // Sidecar file: stores the epoch ms when the current live file was first created. We can't use
    // liveFile.lastModified() because every append updates that, so an actively-used log would
    // never age past ROTATE_AGE_MS and rotation would never fire. A tiny sidecar keeps the anchor
    // stable across appends and survives process restarts.
    private static final String BORN_NAME = "app.log.born";

    private final File dir;
    private final File liveFile;
    private final File rolledFile;
    private final File bornFile;
    // Test seam: lets tests advance a "wall clock" without touching System.currentTimeMillis().
    private final Clock clock;

    /** Source of "now" for the rotate-on-age check. Default is wall clock. */
    interface Clock {
        long now();
    }

    RollingAppLog(File dir) {
        this(dir, System::currentTimeMillis);
    }

    RollingAppLog(File dir, Clock clock) {
        this.dir = dir;
        this.liveFile = new File(dir, LIVE_NAME);
        this.rolledFile = new File(dir, ROLLED_NAME);
        this.bornFile = new File(dir, BORN_NAME);
        this.clock = clock;
    }

    /** File the most recent line was appended to. Exposed for the diagnostics zip. */
    File liveFile() {
        return liveFile;
    }

    /** Rolled file (may not exist). Exposed for the diagnostics zip. */
    File rolledFile() {
        return rolledFile;
    }

    /**
     * Appends one {@code <iso-ts> <level> <tag>: <msg>} line. Best-effort: write failures are
     * swallowed so a full disk doesn't tear down the calling thread.
     */
    synchronized void write(String level, String tag, String msg) {
        if (!ensureDir()) {
            return;
        }
        rotateIfStale();
        // Ensure the birth marker exists for the current live file before we append. This catches
        // the cold-start case (no born file yet) and the post-rotation case (we just wiped it).
        if (!bornFile.exists()) {
            writeBorn(clock.now());
        }
        try (BufferedWriter w = new BufferedWriter(new FileWriter(liveFile, true))) {
            // ISO-8601-ish with millis. Locale.US so the formatter is stable regardless of device
            // locale; SimpleDateFormat per-write avoids holding a non-thread-safe formatter as a
            // field even though this whole method is synchronized.
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US);
            w.write(fmt.format(new Date(clock.now())));
            w.write(' ');
            w.write(safe(level));
            w.write(' ');
            w.write(safe(tag));
            w.write(": ");
            w.write(safe(msg));
            w.newLine();
        } catch (IOException ignored) {
            // Logging that the log failed would risk an infinite loop; just drop the line.
        }
    }

    private static String safe(String s) {
        if (s == null) {
            return "-";
        }
        // Strip newlines so the one-line-per-event contract holds.
        return s.replace('\n', ' ').replace('\r', ' ');
    }

    private boolean ensureDir() {
        if (dir.isDirectory()) {
            return true;
        }
        return dir.mkdirs();
    }

    private void rotateIfStale() {
        if (!liveFile.exists()) {
            return;
        }
        // Anchor age to the stable birth marker, NOT liveFile.lastModified() — every append
        // touches mtime, so an active log would never rotate. If the marker is missing or
        // unreadable, treat the live file as fresh (and the write path will write a fresh marker
        // afterwards), since we'd rather skip a rotation than rotate spuriously.
        long bornMs = readBornMs();
        if (bornMs <= 0L) {
            return;
        }
        long age = clock.now() - bornMs;
        if (age < ROTATE_AGE_MS) {
            return;
        }
        // Roll: overwrite any previous rolled file with the current live file.
        if (rolledFile.exists() && !rolledFile.delete()) {
            // If we can't make room for the rolled file, leave the live file in place and keep
            // appending — better to keep one oversize log than to start dropping lines silently.
            return;
        }
        if (!liveFile.renameTo(rolledFile)) {
            // Rename failed; live file stays in place.
            return;
        }
        // The live file is now gone; the FileWriter below will recreate it on next write.
        // Reset the birth marker so the new live file starts a fresh 7-day window.
        if (bornFile.exists() && !bornFile.delete()) {
            // Best-effort: a stale marker would make the next rotation fire too early (also
            // acceptable) rather than too late, so it's not catastrophic. Try once.
        }
    }

    private long readBornMs() {
        if (!bornFile.exists()) {
            return -1L;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(bornFile))) {
            String line = r.readLine();
            if (line == null) {
                return -1L;
            }
            return Long.parseLong(line.trim());
        } catch (IOException | NumberFormatException ex) {
            return -1L;
        }
    }

    private void writeBorn(long whenMs) {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(bornFile, false))) {
            w.write(Long.toString(whenMs));
            w.newLine();
        } catch (IOException ignored) {
            // Best-effort: a missing marker on the next call just makes the age-check skip
            // (treat as fresh). We'll try again on the next write.
        }
    }
}
