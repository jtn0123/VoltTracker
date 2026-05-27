package com.volttracker.obdpoc;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import androidx.core.content.FileProvider;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a one-zip share intent for the diagnostics share-sheet flow. The zip pulls together:
 *
 * <ul>
 *   <li>the most recent {@value #MAX_SESSION_LOGS} per-session JSONLs from {@code files/obd-logs/}
 *   <li>the rolling app log + its rolled sibling from {@code files/app-log/}
 *   <li>the session summary rollup {@code sessions-summary.jsonl}
 * </ul>
 *
 * <p>The zip is written to {@code cacheDir/diagnostics/} and exposed via the project's {@code
 * FileProvider} — the bridge call wraps the returned intent in {@code Intent.createChooser} and
 * starts it.
 */
final class DiagnosticsShareIntent {

    /** Most recent N per-session JSONLs we include. */
    static final int MAX_SESSION_LOGS = 5;

    private static final String TAG = "DiagnosticsShareIntent";
    private static final String SUBDIR = "diagnostics";
    private static final String SESSION_LOG_DIR = "obd-logs";
    private static final String APP_LOG_DIR = "app-log";
    private static final String SUMMARY_NAME = "sessions-summary.jsonl";

    private DiagnosticsShareIntent() {}

    /**
     * Zips the diagnostics bundle and returns an {@link Intent#ACTION_SEND} intent ready for {@link
     * Intent#createChooser(Intent, CharSequence)}. Returns {@code null} only if the zip couldn't be
     * written (caller logs and surfaces the failure to the UI).
     */
    static Intent buildIntent(Context ctx) {
        if (ctx == null) {
            return null;
        }
        File zip = buildZip(ctx);
        if (zip == null) {
            return null;
        }
        Uri uri;
        try {
            uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", zip);
        } catch (IllegalArgumentException ex) {
            // FileProvider couldn't expose the file — usually a misconfigured file_paths.xml.
            Log.e(TAG, "FileProvider rejected diagnostics zip: " + zip, ex);
            return null;
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.putExtra(Intent.EXTRA_SUBJECT, "VoltTracker diagnostics");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    /**
     * Builds the zip file (exposed package-private so tests can verify its contents without driving
     * the FileProvider). Returns {@code null} on IO failure.
     */
    static File buildZip(Context ctx) {
        File outDir = new File(ctx.getCacheDir(), SUBDIR);
        if (!outDir.exists() && !outDir.mkdirs()) {
            Log.e(TAG, "could not create diagnostics dir: " + outDir);
            return null;
        }
        // Clear stale zips so the cache doesn't accumulate one per share over the device's
        // lifetime. We keep only the one we're about to build.
        File[] stale = outDir.listFiles();
        if (stale != null) {
            for (File f : stale) {
                if (f.isFile() && !f.delete()) {
                    Log.w(TAG, "could not delete stale diagnostics file: " + f);
                }
            }
        }
        File zipFile = new File(outDir, "diagnostics-" + System.currentTimeMillis() + ".zip");
        File filesDir = ctx.getFilesDir();
        try (FileOutputStream fos = new FileOutputStream(zipFile);
                ZipOutputStream zos = new ZipOutputStream(fos)) {
            addRecentSessionLogs(zos, new File(filesDir, SESSION_LOG_DIR));
            addAppLogs(zos, new File(filesDir, APP_LOG_DIR));
            addSummary(zos, new File(new File(filesDir, SESSION_LOG_DIR), SUMMARY_NAME));
        } catch (IOException ex) {
            Log.e(TAG, "diagnostics zip failed", ex);
            // Best-effort cleanup so callers don't pick up a half-written zip.
            if (zipFile.exists() && !zipFile.delete()) {
                Log.w(TAG, "could not delete partial zip: " + zipFile);
            }
            return null;
        }
        return zipFile;
    }

    private static void addRecentSessionLogs(ZipOutputStream zos, File sessionLogDir)
            throws IOException {
        if (!sessionLogDir.isDirectory()) {
            return;
        }
        // Filter to the per-session JSONLs (session-*.jsonl). The sessions-summary file is also
        // .jsonl but is added separately via addSummary; including it here would either crowd the
        // 5-file budget or — worse — duplicate the entry name in the zip and blow up putNextEntry.
        File[] files =
                sessionLogDir.listFiles(
                        (dir, name) -> name.startsWith("session-") && name.endsWith(".jsonl"));
        if (files == null || files.length == 0) {
            return;
        }
        // Newest first so the slice gives the freshest N files. We use a plain anonymous
        // Comparator with Collections.sort (rather than Comparator.comparingLong / List.sort)
        // because those are API 24+ and the app's minSdk is 23.
        List<File> sorted = new ArrayList<>(Arrays.asList(files));
        Collections.sort(
                sorted,
                new Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        return Long.compare(b.lastModified(), a.lastModified());
                    }
                });
        int limit = Math.min(MAX_SESSION_LOGS, sorted.size());
        for (int i = 0; i < limit; i++) {
            addFile(zos, sorted.get(i), SESSION_LOG_DIR + "/" + sorted.get(i).getName());
        }
    }

    private static void addAppLogs(ZipOutputStream zos, File appLogDir) throws IOException {
        if (!appLogDir.isDirectory()) {
            return;
        }
        File[] files = appLogDir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isFile()) {
                addFile(zos, f, APP_LOG_DIR + "/" + f.getName());
            }
        }
    }

    private static void addSummary(ZipOutputStream zos, File summary) throws IOException {
        if (summary.isFile()) {
            addFile(zos, summary, SESSION_LOG_DIR + "/" + summary.getName());
        }
    }

    private static void addFile(ZipOutputStream zos, File source, String entryName)
            throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        entry.setTime(source.lastModified());
        zos.putNextEntry(entry);
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(source))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                zos.write(buf, 0, n);
            }
        }
        zos.closeEntry();
    }
}
