package com.volttracker.obdpoc.data

import android.database.sqlite.SQLiteDatabase
import com.volttracker.obdpoc.materialize.TripSplitRules

/** Result of a user split or merge: the split key and the trip keys that now exist around it. */
class TripSplitOutcome(
    @JvmField val splitKey: String,
    @JvmField val merged: Boolean,
    /** Split: the first and second halves' trip keys. Merge: the merged trip's key. */
    @JvmField val routeKeys: List<String>,
)

/**
 * "Split trip here" / "merge back" for in-trip Park stops ([TripSplitRules.ParkStop]).
 *
 * A split is persisted as an [ObdTripSplits] status event, then the session's trip caches are
 * invalidated and — when the session already has saved `trip_segments` — its segments are
 * re-materialized, so the trip list, the map and saved trips all re-cut the session the same way.
 *
 * Labels and favorites hang off trip keys (`sessionId:start:end`), and a split changes the keys:
 * - Split: the FIRST half inherits the original trip's label and favorite; the SECOND half is a new
 *   trip with none. The original key's own events are left untouched.
 * - Merge: the merged trip takes the first half's label and favorite (so a rename made while split
 *   survives the merge); the second half's label/favorite no longer applies to any trip, but its
 *   events are kept, so splitting at the same stop again brings them back.
 * Hidden ("not a trip") state is not carried either way.
 *
 * Only gear-aware sessions can be split (only they have in-trip Park stops); legacy sessions are
 * refused, so trips the user never touches keep their exact windows and keys.
 */
internal class ObdStoreTripSplitEdits(
    private val helper: VoltTrackerDb,
    private val writer: ObdStoreWriter,
    private val trips: ObdStoreTrips,
    private val edits: ObdTripEditStore,
    private val rematerializeTrips: (ObdSessionRecord) -> Unit,
) {
    fun splitTripAtStop(
        routeKey: String?,
        stopStartMs: Long,
        stopEndMs: Long,
    ): TripSplitOutcome? {
        val canonical = ObdTripExclusions.canonicalRouteKey(routeKey) ?: return null
        val parsed = DriveWindowDetector.parseRouteKey(canonical) ?: return null
        val startedAtMs = parsed.startedAtMs ?: return null
        val endedAtMs = parsed.endedAtMs ?: return null
        val db = helper.writableDatabase
        val session = gearAwareSession(db, parsed.sessionId) ?: return null
        // Only a genuine in-trip Park stop of this trip may become a split point.
        val stop =
            DriveWindowDetector
                .parkStopsForWindow(db, session, startedAtMs, endedAtMs)
                .firstOrNull { it.startMs == stopStartMs && it.endMs == stopEndMs } ?: return null
        val original =
            DriveWindowDetector
                .windowsForSession(db, session)
                .firstOrNull { it.startedAtMs <= stop.startMs && stop.endMs <= it.endedAtMs } ?: return null
        val originalKeys = identityKeys(db, original) + canonical
        val span = TripSplitRules.Span(stop.startMs, stop.endMs)
        val splitKey = ObdTripSplits.splitKey(session.id, span.startMs, span.endMs)
        writer.recordEvent(
            session.id,
            ObdTripSplits.EVENT_KIND,
            ObdTripSplits.STATE_SPLIT,
            splitKey,
            false,
            ObdTripSplits.eventPayload(splitKey, canonical, span),
        )
        refresh(session)
        val after = DriveWindowDetector.windowsForSession(db, session)
        val first = after.firstOrNull { it.endedAtMs == span.startMs }
        val second = after.firstOrNull { it.startedAtMs == span.endMs + 1L }
        val keys = ArrayList<String>()
        if (first != null) {
            val firstKey = ObdStoreRouteProjection.tripKeyForWindow(db, first)
            carryEdits(session.id, originalKeys, firstKey)
            keys.add(firstKey)
        }
        second?.let { keys.add(ObdStoreRouteProjection.tripKeyForWindow(db, it)) }
        return TripSplitOutcome(splitKey, false, keys)
    }

    fun mergeTripSplit(splitKey: String?): TripSplitOutcome? {
        val canonical = ObdTripExclusions.canonicalRouteKey(splitKey) ?: return null
        val parsed = DriveWindowDetector.parseRouteKey(canonical) ?: return null
        val db = helper.writableDatabase
        val session = gearAwareSession(db, parsed.sessionId) ?: return null
        val span =
            ObdTripSplits
                .activeSplits(db, session.id)
                .firstOrNull { it.startMs == parsed.startedAtMs && it.endMs == parsed.endedAtMs } ?: return null
        val first = DriveWindowDetector.windowsForSession(db, session).firstOrNull { it.endedAtMs == span.startMs }
        val firstKeys = first?.let { identityKeys(db, it) }.orEmpty()
        writer.recordEvent(
            session.id,
            ObdTripSplits.EVENT_KIND,
            ObdTripSplits.STATE_MERGED,
            canonical,
            false,
            ObdTripSplits.eventPayload(canonical, firstKeys.firstOrNull() ?: canonical, span),
        )
        refresh(session)
        val merged =
            DriveWindowDetector
                .windowsForSession(db, session)
                .firstOrNull { it.startedAtMs <= span.startMs && span.endMs <= it.endedAtMs }
                ?: return TripSplitOutcome(canonical, true, emptyList())
        val mergedKey = ObdStoreRouteProjection.tripKeyForWindow(db, merged)
        if (firstKeys.isNotEmpty()) carryEdits(session.id, firstKeys, mergedKey)
        return TripSplitOutcome(canonical, true, listOf(mergedKey))
    }

    private fun gearAwareSession(
        db: SQLiteDatabase,
        sessionId: Long,
    ): ObdSessionRecord? {
        val session =
            db
                .query(VoltTrackerDb.TABLE_SESSIONS, null, "_id = ?", arrayOf(sessionId.toString()), null, null, null)
                .use { cursor -> if (cursor.moveToFirst()) ObdStoreSupport.readSession(cursor) else null }
        return session?.takeIf { TripSplitRules.appliesTo(it.tripRulesVersion) }
    }

    private fun refresh(session: ObdSessionRecord) {
        trips.invalidateSessionTripCache(session.id)
        rematerializeTrips(session)
    }

    /** Both keys a trip can be addressed by: the trip-list (point-clipped) id and the window key. */
    private fun identityKeys(
        db: SQLiteDatabase,
        window: DriveWindowDetector.DriveWindow,
    ): List<String> = listOf(ObdStoreRouteProjection.tripKeyForWindow(db, window), window.routeKey()).distinct()

    /**
     * Makes [toKey]'s label and favorite match the first of [fromKeys] that has one (clearing them
     * when none does). Writes only when something actually changes.
     */
    private fun carryEdits(
        sessionId: Long,
        fromKeys: List<String>,
        toKey: String,
    ) {
        if (toKey in fromKeys) return
        val db = helper.readableDatabase
        val labels = ObdTripLabels.labelsByRouteKey(db, listOf(sessionId))
        val label = fromKeys.firstNotNullOfOrNull { labels[it] }.orEmpty()
        if (labels[toKey].orEmpty() != label) {
            edits.setTripLabel(toKey, label)
        }
        val favorites = ObdTripFavorites.favoriteRouteKeys(db, listOf(sessionId))
        val favorite = fromKeys.any { it in favorites }
        if ((toKey in favorites) != favorite) {
            edits.setTripFavorite(toKey, favorite)
        }
    }
}
