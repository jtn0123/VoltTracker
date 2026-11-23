package com.volttracker.obdpoc.data

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.core.content.edit
import java.io.File
import java.util.WeakHashMap

/**
 * Keeps a damaged history database instead of losing it.
 *
 * Android's default [DatabaseErrorHandler] deletes the database file as soon as SQLite reports it
 * corrupt, and the next open silently creates an empty one. The live database is the only copy of
 * the owner's history (app backup is off), so [VoltTrackerDb] installs [handler] for it instead:
 * the damaged file and its `-wal`/`-shm`/`-journal` siblings are renamed to
 * `<name>.corrupt-<epoch ms>` (siblings keep their suffix after that, so the copy still opens with
 * its WAL), at most [KEEP_COPIES] such copies are kept, and a notice is recorded for the dashboard
 * to show until the owner dismisses it. "Clear data" removes the copies ([discardAll]).
 *
 * Several helpers can have the live file open at once (the recording service, each dashboard's
 * store, short-lived readers). Once one of them has moved the damaged file aside, the others still
 * hold that moved file and report it corrupt in turn; [VoltTrackerDb] registers each database it
 * opens ([onOpened]) so those late reports only close their connection instead of moving the
 * fresh database that now sits at the live path.
 */
object DatabaseCorruption {
    private const val TAG = "DatabaseCorruption"
    private const val PREFS = "volt_db_corruption"
    private const val KEY_SET_ASIDE_AT_MS = "set_aside_at_ms"
    private const val KEY_SET_ASIDE_NAME = "set_aside_name"
    private const val MARKER = ".corrupt-"

    /** How many set-aside copies to keep; others are deleted to bound disk use. */
    const val KEEP_COPIES = 2

    private val SIBLING_SUFFIXES = listOf("-wal", "-shm", "-journal")

    private val lock = Any()

    /** How many times this process has moved the live file aside. Guarded by [lock]. */
    private var setAsideCount = 0L

    /** The [setAsideCount] when each open live database was opened. Guarded by [lock]. */
    private val openedAt = WeakHashMap<SQLiteDatabase, Long>()

    /** The error handler for the live database: set the damaged file aside, never delete it. */
    fun handler(context: Context): DatabaseErrorHandler {
        val appContext = context.applicationContext
        return DatabaseErrorHandler { db -> onCorruption(appContext, db) }
    }

    /** Records that [db] has just opened the file at the live path (see the class notes). */
    fun onOpened(db: SQLiteDatabase) {
        synchronized(lock) { openedAt[db] = setAsideCount }
    }

    private fun onCorruption(
        context: Context,
        db: SQLiteDatabase,
    ) {
        val path = db.path
        synchronized(lock) {
            // A database never registered is still opening, so the file it reports is the one at
            // the path. One opened before the last set-aside holds the file already moved.
            val opened = openedAt.remove(db)
            val moved = opened != null && opened != setAsideCount
            try {
                // Same as the platform handler: close every connection before the file moves.
                if (db.isOpen) db.close()
            } catch (ex: RuntimeException) {
                Log.w(TAG, "closing the corrupt database failed; moving it anyway", ex)
            }
            if (moved) {
                Log.w(TAG, "SQLite reported $path corrupt on a connection to a copy already set aside")
                return
            }
            Log.e(TAG, "SQLite reported $path corrupt; setting it aside")
            if (setAside(context, File(path), System.currentTimeMillis()) != null) setAsideCount++
        }
    }

    /**
     * Renames [dbFile] and its siblings to a `.corrupt-<ms>` copy, prunes older copies and records
     * the owner notice. Returns the set-aside main file, or null when nothing could be moved (the
     * file is then left in place rather than deleted).
     */
    fun setAside(
        context: Context,
        dbFile: File,
        nowMs: Long,
    ): File? {
        if (!dbFile.exists()) return null
        var stamp = nowMs
        while (File(dbFile.path + MARKER + stamp).exists()) stamp++
        val target = File(dbFile.path + MARKER + stamp)
        if (!dbFile.renameTo(target)) {
            Log.e(TAG, "could not set aside ${dbFile.name}; leaving it in place")
            return null
        }
        for (suffix in SIBLING_SUFFIXES) {
            val sibling = File(dbFile.path + suffix)
            if (sibling.exists() && !sibling.renameTo(File(target.path + suffix))) {
                // A stale sibling next to the fresh database would be replayed into it.
                Log.w(TAG, "could not move ${sibling.name}; deleting it")
                sibling.delete()
            }
        }
        prune(dbFile)
        prefs(context).edit(commit = true) {
            putLong(KEY_SET_ASIDE_AT_MS, stamp)
            putString(KEY_SET_ASIDE_NAME, target.name)
        }
        Log.e(TAG, "set aside damaged database as ${target.name}; a new empty one will be created")
        return target
    }

    /** The set-aside copies of [dbFile] (main files only), oldest first. */
    fun setAsideCopies(dbFile: File): List<File> {
        val prefix = dbFile.name + MARKER
        val siblings = dbFile.parentFile?.listFiles() ?: return emptyList()
        return siblings
            .filter { it.name.startsWith(prefix) && it.name.substring(prefix.length).toLongOrNull() != null }
            .sortedBy { it.name.substring(prefix.length).toLong() }
    }

    /** Deletes every set-aside copy of [dbFile] and withdraws the notice: the owner cleared their data. */
    fun discardAll(
        context: Context,
        dbFile: File,
    ) {
        setAsideCopies(dbFile).forEach(::deleteFamily)
        dismissNotice(context)
    }

    /** When the live history was last set aside and not yet acknowledged, or null. */
    fun pendingNoticeAtMs(context: Context): Long? {
        val at = prefs(context).getLong(KEY_SET_ASIDE_AT_MS, 0L)
        return at.takeIf { it > 0L }
    }

    fun dismissNotice(context: Context) {
        prefs(context).edit(commit = true) {
            remove(KEY_SET_ASIDE_AT_MS)
            remove(KEY_SET_ASIDE_NAME)
        }
    }

    /** The notice store, so a screen can listen for a set-aside that happens while it is showing. */
    fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Keeps the largest copy, which most likely holds the history, plus the newest others up to
     * [KEEP_COPIES]: a later damaged file is usually a small database started after the first reset.
     */
    private fun prune(dbFile: File) {
        val copies = setAsideCopies(dbFile)
        val largest = copies.asReversed().maxByOrNull(::familySize) ?: return
        val keep = setOf(largest) + copies.asReversed().filter { it != largest }.take(KEEP_COPIES - 1)
        copies.filterNot { it in keep }.forEach(::deleteFamily)
    }

    private fun familySize(copy: File): Long = (listOf("") + SIBLING_SUFFIXES).sumOf { File(copy.path + it).length() }

    private fun deleteFamily(copy: File) {
        (listOf("") + SIBLING_SUFFIXES).forEach { suffix ->
            val file = File(copy.path + suffix)
            if (file.exists() && !file.delete()) Log.w(TAG, "could not delete ${file.name}")
        }
    }
}
