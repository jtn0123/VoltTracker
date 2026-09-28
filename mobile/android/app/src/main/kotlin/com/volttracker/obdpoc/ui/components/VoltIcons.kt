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

    /** A ruler: Settings → Units. */
    val Ruler by lazy { icon(rect(3f, 8f, 18f, 8f, 1.5f), "M7 8v3M11 8v4M15 8v3") }

    /** A half-shaded circle: Settings → Appearance. */
    val Contrast by lazy { icon(circle(12f, 12f, 9f), "M12 3v18M12 7h4.5M12 11h5.5M12 15h5M12 19h2.5") }
    val Alert by lazy { icon("M12 3l10 18H2z", "M12 10v4M12 17.5h.01") }
    val Share by lazy { icon("M12 3v12M7 8l5-5 5 5M5 14v5a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-5") }
    val Play by lazy { icon("M7 4.5v15l12-7.5z") }
    val Wrench by lazy {
        icon("M14.7 6.3a4 4 0 0 0-5.4 5.2L3 17.8V21h3.2l6.3-6.3a4 4 0 0 0 5.2-5.4l-2.6 2.6-2.4-.6-.6-2.4z")
    }
    val Doc by lazy { icon("M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z", "M14 3v6h6M8 13h8M8 17h5") }
    val Refresh by lazy { icon("M20 11a8 8 0 1 0-2.3 5.7", "M20 4v7h-7") }
    val Lock by lazy { icon(rect(5f, 11f, 14f, 10f, 2.5f), "M8 11V8a4 4 0 0 1 8 0v3") }
    val Unlock by lazy { icon(rect(5f, 11f, 14f, 10f, 2.5f), "M8 11V8a4 4 0 0 1 7.8-1.2") }
    val Power by lazy { icon("M12 3v8", "M6.3 6.8a8 8 0 1 0 11.4 0") }
    val Horn by lazy { icon("M4 10v4h3l6 4V6l-6 4z", "M16.5 9a4 4 0 0 1 0 6M19 6.5a8 8 0 0 1 0 11") }
    val Lights by lazy {
        icon("M9 18h6M10 21h4M12 3a6 6 0 0 0-4 10.5c.8.8 1 1.5 1 2.5h6c0-1 .2-1.7 1-2.5A6 6 0 0 0 12 3z")
    }
    val Fan by lazy {
        icon(
            circle(12f, 12f, 1.5f),
            "M12 10.5C12 6 13 3 15.5 3S19 6 12 10.5zM13.5 12c4.5 0 7.5 1 7.5 3.5S18 19 13.5 12z",
            "M12 13.5c0 4.5-1 7.5-3.5 7.5S5 18 12 13.5zM10.5 12C6 12 3 11 3 8.5S6 5 10.5 12z",
        )
    }
    val Check by lazy { icon("M5 12.5l4.5 4.5L19 7.5") }
    val Scan by lazy {
        icon("M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3M4 12h16")
    }
    val Plug by lazy { icon("M9 3v5M15 3v5M6 8h12v3a6 6 0 0 1-12 0zM12 17v4") }
    val Battery by lazy { icon(rect(3f, 7f, 16f, 10f, 2.5f), "M22 11v2") }
    val Motor by lazy {
        icon(circle(12f, 12f, 8.5f), circle(12f, 12f, 2.2f), "M12 3.5v6.3M4.6 16.3l5.5-3.2M19.4 16.3l-5.5-3.2")
    }

    /** Four-tile grid: "switch to the Detailed (cockpit) view". */
    val Grid by lazy {
        icon(
            rect(4f, 4f, 7f, 7f, 1.5f),
            rect(13f, 4f, 7f, 7f, 1.5f),
            rect(4f, 13f, 7f, 7f, 1.5f),
            rect(13f, 13f, 7f, 7f, 1.5f),
        )
    }

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
