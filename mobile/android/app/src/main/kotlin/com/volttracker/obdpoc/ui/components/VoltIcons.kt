package com.volttracker.obdpoc.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The redesign's line icons (mockups `core.js`): 24-unit viewBox, round-capped strokes, no fill.
 * Drawn in black and recolored by `Icon(tint = …)`, so one vector serves every theme and state.
 */
object VoltIcons {
    val Drive by lazy { icon("M4.5 16.5a8 8 0 1 1 15 0", "M12 12.5l3.5-3.5", circle(12f, 13f, 1.2f)) }
    val Trips by lazy {
        icon(circle(6f, 18f, 2f), circle(18f, 6f, 2f), "M8 18h7a3 3 0 0 0 0-6H9a3 3 0 0 1 0-6h7")
    }
    val Bolt by lazy { icon("M13 3L5.5 13.5H12L11 21l7.5-10.5H12z") }
    val Insights by lazy { icon("M4 20V10M10 20V4M16 20v-7M22 20H2") }
    val Car by lazy {
        icon(
            "M5 17v2M19 17v2M3.5 12.5l1.8-5A2 2 0 0 1 7.2 6h9.6a2 2 0 0 1 1.9 1.5l1.8 5",
            rect(3f, 12f, 18f, 5.5f, 2f),
            circle(7.5f, 14.8f, 0.9f),
            circle(16.5f, 14.8f, 0.9f),
        )
    }
    val Settings by lazy {
        icon(
            circle(12f, 12f, 3f),
            "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 " +
                "1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3" +
                "l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 " +
                "0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 " +
                "1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 " +
                "0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 " +
                "1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z",
        )
    }
    val ChevronRight by lazy { icon("M9 6l6 6-6 6") }
    val ChevronLeft by lazy { icon("M15 6l-6 6 6 6") }
    val Fuel by lazy {
        icon("M4 21V5a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v16M3 21h12M4 10h10", "M14 8l3 0 2 2v7a1.5 1.5 0 0 0 3 0V9l-3-3")
    }
    val Bluetooth by lazy { icon("M7 7l10 10-5 4V3l5 4L7 17") }
    val Pulse by lazy { icon("M3 12h4l3-8 4 16 3-8h4") }
    val Cells by lazy { icon(rect(3f, 6f, 18f, 12f, 2f), "M7.5 6v12M12 6v12M16.5 6v12") }
    val Window by lazy { icon(rect(4f, 4f, 16f, 16f, 3f), "M4 12h16") }
    val Alert by lazy { icon("M12 3l10 18H2z", "M12 10v4M12 17.5h.01") }
    val Share by lazy { icon("M12 3v12M7 8l5-5 5 5M5 14v5a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-5") }
    val Play by lazy { icon("M7 4.5v15l12-7.5z") }
    val Wrench by lazy {
        icon("M14.7 6.3a4 4 0 0 0-5.4 5.2L3 17.8V21h3.2l6.3-6.3a4 4 0 0 0 5.2-5.4l-2.6 2.6-2.4-.6-.6-2.4z")
    }
    val Doc by lazy { icon("M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z", "M14 3v6h6M8 13h8M8 17h5") }
    val Refresh by lazy { icon("M20 11a8 8 0 1 0-2.3 5.7", "M20 4v7h-7") }

    private const val VIEWPORT = 24f
    private const val STROKE = 1.8f

    private fun icon(vararg paths: String): ImageVector {
        val builder =
            ImageVector.Builder(
                defaultWidth = VIEWPORT.dp,
                defaultHeight = VIEWPORT.dp,
                viewportWidth = VIEWPORT,
                viewportHeight = VIEWPORT,
            )
        paths.forEach { data ->
            builder.addPath(
                pathData = addPathNodes(data),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }

    /** SVG `<circle>` as path data (two half-arcs). */
    internal fun circle(
        cx: Float,
        cy: Float,
        r: Float,
    ): String = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

    /** SVG `<rect rx>` as path data. */
    internal fun rect(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        rx: Float,
    ): String =
        "M${x + rx} ${y}h${w - 2 * rx}a$rx $rx 0 0 1 $rx ${rx}v${h - 2 * rx}a$rx $rx 0 0 1 ${-rx} $rx" +
            "h${-(w - 2 * rx)}a$rx $rx 0 0 1 ${-rx} ${-rx}v${-(h - 2 * rx)}a$rx $rx 0 0 1 $rx ${-rx}z"
}
