package com.volttracker.obdpoc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import org.json.JSONObject

/**
 * Draws the shareable drive-summary card: a square PNG-ready bitmap with the route outline and the
 * stats the dashboard's trip-detail sheet shows. The dashboard passes the stat strings already
 * formatted (units, currency and cost math live in one place, the WebView), so this renderer only
 * lays them out — it never re-derives values.
 */
object TripShareCardRenderer {
    const val SIZE = 1080
    const val MAX_STATS = 6

    private const val MARGIN = 72f
    private const val STATS_TOP = 680f
    private const val STAT_CELL_H = 90f
    private const val STAT_GAP = 24f

    /** Baseline of the brand label; its glyph tops sit ~34px (the text size) above this. */
    private const val BRAND_BASELINE = SIZE - 40f
    private const val BRAND_TEXT_SIZE = 34f

    private const val BG_COLOR = 0xFF101014.toInt()
    private const val PANEL_COLOR = 0xFF1A1A22.toInt()
    private const val ACCENT_COLOR = 0xFFFF7A45.toInt()
    private const val TEXT_COLOR = 0xFFF2F2F5.toInt()
    private const val MUTED_COLOR = 0xFF9A9AA6.toInt()

    /** A single already-formatted stat ("Distance" / "11 mi") from the dashboard. */
    class CardStat(
        val label: String,
        val value: String,
    )

    fun render(
        route: JSONObject,
        title: String,
        subtitle: String,
        stats: List<CardStat>,
    ): Bitmap {
        val bitmap = createBitmap(SIZE, SIZE)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BG_COLOR)

        val text = Paint(Paint.ANTI_ALIAS_FLAG)
        text.color = TEXT_COLOR
        text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        text.textSize = 64f
        canvas.drawText(ellipsize(title, text, SIZE - 2 * MARGIN), MARGIN, MARGIN + 58f, text)

        val muted = Paint(Paint.ANTI_ALIAS_FLAG)
        muted.color = MUTED_COLOR
        muted.typeface = Typeface.SANS_SERIF
        muted.textSize = 38f
        canvas.drawText(ellipsize(subtitle, muted, SIZE - 2 * MARGIN), MARGIN, MARGIN + 120f, muted)

        drawRoute(canvas, route, RectF(MARGIN, 260f, SIZE - MARGIN, 620f))
        drawStats(canvas, stats.take(MAX_STATS))

        val brand = Paint(Paint.ANTI_ALIAS_FLAG)
        brand.color = ACCENT_COLOR
        brand.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        brand.textSize = BRAND_TEXT_SIZE
        canvas.drawText("VoltTracker", MARGIN, BRAND_BASELINE, brand)
        return bitmap
    }

    /**
     * Bottom edge (px) of the stat grid for [statCount] stats. Exposed so tests can pin the
     * layout invariant that the common full 6-stat grid ends above [brandGlyphTop] — the two are
     * drawn independently, so nothing else stops them from colliding.
     */
    fun statsGridBottom(statCount: Int): Float {
        val rows = (minOf(statCount, MAX_STATS) + 1) / 2
        if (rows <= 0) {
            return STATS_TOP
        }
        return STATS_TOP + rows * STAT_CELL_H + (rows - 1) * STAT_GAP
    }

    /** Top of the brand label's glyphs; see [statsGridBottom]. */
    fun brandGlyphTop(): Float = BRAND_BASELINE - BRAND_TEXT_SIZE

    /** Route outline normalized into [box], aspect-preserving. Skipped when under 2 GPS points. */
    private fun drawRoute(
        canvas: Canvas,
        route: JSONObject,
        box: RectF,
    ) {
        val points = route.optJSONArray("points") ?: return
        var latMin = Double.MAX_VALUE
        var latMax = -Double.MAX_VALUE
        var lngMin = Double.MAX_VALUE
        var lngMax = -Double.MAX_VALUE
        var count = 0
        for (i in 0 until points.length()) {
            val point = points.optJSONObject(i) ?: continue
            val lat = point.optDouble("lat")
            val lng = point.optDouble("lng")
            if (!lat.isFinite() || !lng.isFinite()) continue
            latMin = minOf(latMin, lat)
            latMax = maxOf(latMax, lat)
            lngMin = minOf(lngMin, lng)
            lngMax = maxOf(lngMax, lng)
            count += 1
        }
        if (count < 2) return
        // Aspect-preserving scale, centered in the box; a degenerate span (straight
        // north-south drive) still gets a visible line via the minimum span floor.
        val latSpan = maxOf(latMax - latMin, 1e-5)
        val lngSpan = maxOf(lngMax - lngMin, 1e-5)
        val scale = minOf(box.width() / lngSpan, box.height() / latSpan).toFloat()
        val offsetX = box.left + (box.width() - (lngSpan * scale).toFloat()) / 2f
        val offsetY = box.top + (box.height() - (latSpan * scale).toFloat()) / 2f
        val path = Path()
        var started = false
        for (i in 0 until points.length()) {
            val point = points.optJSONObject(i) ?: continue
            val lat = point.optDouble("lat")
            val lng = point.optDouble("lng")
            if (!lat.isFinite() || !lng.isFinite()) continue
            val x = offsetX + ((lng - lngMin) * scale).toFloat()
            // Latitude grows north; canvas Y grows down.
            val y = offsetY + ((latMax - lat) * scale).toFloat()
            if (started) path.lineTo(x, y) else path.moveTo(x, y)
            started = true
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = 8f
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        stroke.color = ACCENT_COLOR
        canvas.drawPath(path, stroke)
    }

    /** Two-column stat grid (up to [MAX_STATS]) in rounded panels under the route. */
    private fun drawStats(
        canvas: Canvas,
        stats: List<CardStat>,
    ) {
        if (stats.isEmpty()) return
        val columns = 2
        val gap = STAT_GAP
        val cellW = (SIZE - 2 * MARGIN - gap) / columns
        // Three rows of MAX_STATS/2 cells must end above the brand glyphs — the invariant
        // statsGridBottom pins for tests: 680 + 2*(90+24) + 90 = 998 < 1006.
        val cellH = STAT_CELL_H
        val top = STATS_TOP
        val panel = Paint(Paint.ANTI_ALIAS_FLAG)
        panel.color = PANEL_COLOR
        val label = Paint(Paint.ANTI_ALIAS_FLAG)
        label.color = MUTED_COLOR
        label.typeface = Typeface.SANS_SERIF
        label.textSize = 30f
        val value = Paint(Paint.ANTI_ALIAS_FLAG)
        value.color = TEXT_COLOR
        value.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        value.textSize = 46f
        stats.forEachIndexed { index, stat ->
            val col = index % columns
            val row = index / columns
            val left = MARGIN + col * (cellW + gap)
            val cellTop = top + row * (cellH + gap)
            canvas.drawRoundRect(RectF(left, cellTop, left + cellW, cellTop + cellH), 18f, 18f, panel)
            canvas.drawText(ellipsize(stat.label, label, cellW - 48f), left + 24f, cellTop + 36f, label)
            canvas.drawText(ellipsize(stat.value, value, cellW - 48f), left + 24f, cellTop + 74f, value)
        }
    }

    private fun ellipsize(
        raw: String,
        paint: Paint,
        maxWidth: Float,
    ): String {
        if (paint.measureText(raw) <= maxWidth) {
            return raw
        }
        var text = raw
        while (text.isNotEmpty() && paint.measureText("$text…") > maxWidth) {
            text = text.dropLast(1)
        }
        return "$text…"
    }
}
