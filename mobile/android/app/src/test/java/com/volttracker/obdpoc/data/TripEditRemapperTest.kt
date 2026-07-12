package com.volttracker.obdpoc.data

import android.content.ContentValues
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Focused coverage for [TripEditRemapper] (B2): rewriting the session-id component of the route
 * key embedded in trip-edit status events (detail + payload.routeKey) during a donor merge. The
 * end-to-end merge behavior is covered in [DatabaseMergerTest]; this pins the edge cases —
 * malformed keys, unparseable payloads, unmapped sessions, and identity mappings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TripEditRemapperTest {
    private val sessionMap = mapOf(1L to 87L, 5L to 5L)

    private fun event(
        kind: String,
        detail: String?,
        payload: String?,
    ): ContentValues {
        val cv = ContentValues()
        cv.put("kind", kind)
        if (detail != null) cv.put("detail", detail)
        if (payload != null) cv.put("payload", payload)
        return cv
    }

    @Test
    fun rewritesDetailAndPayloadForEveryTripEditKind() {
        for (kind in listOf(
            ObdTripLabels.EVENT_KIND,
            ObdTripFavorites.EVENT_KIND,
            ObdTripExclusions.EVENT_KIND,
        )) {
            val cv = event(kind, "1:2000:3000", """{"routeKey":"1:2000:3000","label":"Home"}""")
            TripEditRemapper.remapEvent(cv, sessionMap)
            assertEquals("detail must carry the target session id for $kind", "87:2000:3000", cv.getAsString("detail"))
            val payload = JSONObject(cv.getAsString("payload"))
            assertEquals(
                "payload.routeKey must carry the target session id for $kind",
                "87:2000:3000",
                payload.getString("routeKey"),
            )
            assertEquals("other payload fields must survive", "Home", payload.getString("label"))
        }
    }

    @Test
    fun leavesNonTripEditKindsUntouched() {
        val cv = event("status", "1:2000:3000", """{"routeKey":"1:2000:3000"}""")
        TripEditRemapper.remapEvent(cv, sessionMap)
        assertEquals("1:2000:3000", cv.getAsString("detail"))
        assertEquals("""{"routeKey":"1:2000:3000"}""", cv.getAsString("payload"))
    }

    @Test
    fun leavesMalformedDetailUntouchedButStillRemapsPayload() {
        val cv = event(ObdTripLabels.EVENT_KIND, "not-a-route-key", """{"routeKey":"1:2000:3000"}""")
        TripEditRemapper.remapEvent(cv, sessionMap)
        assertEquals("not-a-route-key", cv.getAsString("detail"))
        assertEquals("87:2000:3000", JSONObject(cv.getAsString("payload")).getString("routeKey"))
    }

    @Test
    fun leavesUnparseablePayloadUntouchedButStillRemapsDetail() {
        val cv = event(ObdTripExclusions.EVENT_KIND, "1:2000:3000", "not json at all")
        TripEditRemapper.remapEvent(cv, sessionMap)
        assertEquals("87:2000:3000", cv.getAsString("detail"))
        assertEquals("not json at all", cv.getAsString("payload"))
    }

    @Test
    fun leavesNullDetailAndPayloadUntouched() {
        val cv = event(ObdTripLabels.EVENT_KIND, null, null)
        TripEditRemapper.remapEvent(cv, sessionMap)
        assertNull(cv.getAsString("detail"))
        assertNull(cv.getAsString("payload"))
    }

    @Test
    fun remapRouteKeyHandlesEveryEdgeShape() {
        assertEquals("87:2000:3000", TripEditRemapper.remapRouteKey("1:2000:3000", sessionMap))
        // Unmapped session: identity — the caller keeps the original text.
        assertNull(TripEditRemapper.remapRouteKey("42:2000:3000", sessionMap))
        // Session mapped to itself: nothing to rewrite.
        assertNull(TripEditRemapper.remapRouteKey("5:2000:3000", sessionMap))
        // Session-only key (no window timestamps): not a canonical trip-edit key.
        assertNull(TripEditRemapper.remapRouteKey("1", sessionMap))
        // Garbage / null.
        assertNull(TripEditRemapper.remapRouteKey("a:b:c", sessionMap))
        assertNull(TripEditRemapper.remapRouteKey(null, sessionMap))
    }

    @Test
    fun payloadWithoutRouteKeyFieldIsLeftUntouched() {
        val cv = event(ObdTripFavorites.EVENT_KIND, "1:2000:3000", """{"favorite":true}""")
        TripEditRemapper.remapEvent(cv, sessionMap)
        assertEquals("87:2000:3000", cv.getAsString("detail"))
        assertEquals("""{"favorite":true}""", cv.getAsString("payload"))
    }
}
