package com.volttracker.obdpoc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.volttracker.obdpoc.data.TripTrackFormatter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Writes a single-trip GPX/CSV export to `cacheDir/trip-exports/` and builds the
 * [Intent.ACTION_SEND] that hands it to the system share sheet via the project's `FileProvider` --
 * the same cache-dir + FileProvider path [DiagnosticsShareIntent] uses for the diagnostics zip.
 *
 * The route-to-text serialization lives in the data layer ([TripTrackFormatter]); this object owns
 * only the Android file/Intent plumbing (which the data layer is forbidden from importing).
 */
object TripExportShareIntent {
    /** Export formats the trip share supports. */
    enum class Format(
        val key: String,
        val extension: String,
        val mime: String,
    ) {
        GPX("gpx", ".gpx", TripTrackFormatter.GPX_MIME),
        CSV("csv", ".csv", TripTrackFormatter.CSV_MIME),
        ;

        companion object {
            /** Maps a bridge format token onto a [Format], or null when it is unrecognized. */
            fun fromKey(raw: String?): Format? = entries.firstOrNull { it.key.equals(raw?.trim(), ignoreCase = true) }
        }
    }

    /** A written export: the file plus the metadata the caller records into the `exports` table. */
    class TripExportFile(
        val file: File,
        val format: Format,
        val pointCount: Int,
    ) {
        val name: String get() = file.name
        val bytes: Long get() = file.length()
    }

    private const val TAG = "TripExportShareIntent"
    private const val SUBDIR = "trip-exports"

    /**
     * Serializes [route] in [format] and writes it to the trip-export cache dir. Returns null when
     * the route has no usable points or the file couldn't be written. Exposed so the bridge can read
     * the result back (size/point count) and the tests can verify contents without the FileProvider.
     */
    fun writeExport(
        ctx: Context,
        route: JSONObject,
        format: Format,
    ): TripExportFile? {
        val pointCount = TripTrackFormatter.pointCount(route)
        if (pointCount <= 0) {
            return null
        }
        val outDir = prepareOutDir(ctx) ?: return null
        val content =
            when (format) {
                Format.GPX -> TripTrackFormatter.toGpx(route)
                Format.CSV -> TripTrackFormatter.toCsv(route)
            }
        val file = File(outDir, TripTrackFormatter.fileBaseName(route) + format.extension)
        return try {
            file.writeText(content, Charsets.UTF_8)
            TripExportFile(file, format, pointCount)
        } catch (ex: IOException) {
            Log.e(TAG, "trip export write failed", ex)
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "could not delete partial trip export: $file")
            }
            null
        }
    }

    /**
     * Serializes [trips] (the `{ tripId, label, route }` bundle the store builds) as one combined
     * all-trips CSV (M6) and writes it to the trip-export cache dir. [pointCount] is the total sample
     * count across all trips, recorded into the `exports` table. Returns null when there are no points
     * to export or the file couldn't be written. Shares the cache-dir + FileProvider path with the
     * single-trip export, so [buildShareIntent] handles both.
     */
    fun writeAllTripsCsv(
        ctx: Context,
        trips: JSONArray,
        pointCount: Int,
    ): TripExportFile? {
        if (pointCount <= 0) {
            return null
        }
        val outDir = prepareOutDir(ctx) ?: return null
        val content = TripTrackFormatter.toAllTripsCsv(trips)
        val file = File(outDir, allTripsFileName())
        return try {
            file.writeText(content, Charsets.UTF_8)
            TripExportFile(file, Format.CSV, pointCount)
        } catch (ex: IOException) {
            Log.e(TAG, "all-trips export write failed", ex)
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "could not delete partial all-trips export: $file")
            }
            null
        }
    }

    private fun allTripsFileName(): String {
        val stamp =
            SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date())
        return "volt-all-trips-$stamp.csv"
    }

    /**
     * Wraps an already-written [export] in an [Intent.ACTION_SEND] ready for [Intent.createChooser].
     * Returns null only if the FileProvider rejected the file (usually a misconfigured file_paths.xml).
     */
    fun buildShareIntent(
        ctx: Context,
        export: TripExportFile,
    ): Intent? {
        val uri: Uri =
            try {
                FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", export.file)
            } catch (ex: IllegalArgumentException) {
                Log.e(TAG, "FileProvider rejected trip export: ${export.file}", ex)
                return null
            }
        return Intent(Intent.ACTION_SEND).apply {
            type = export.format.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.share_trip_export_subject))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Creates `cacheDir/trip-exports/` and clears stale artifacts so the cache keeps only the export
     * we're about to share. Returns null if the dir can't be created.
     */
    private fun prepareOutDir(ctx: Context): File? {
        val outDir = File(ctx.cacheDir, SUBDIR)
        if (!outDir.exists() && !outDir.mkdirs()) {
            Log.e(TAG, "could not create trip-export dir: $outDir")
            return null
        }
        outDir.listFiles()?.forEach { stale ->
            if (stale.isFile && !stale.delete()) {
                Log.w(TAG, "could not delete stale trip export: $stale")
            }
        }
        return outDir
    }
}
