package com.volttracker.obdpoc.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Corner radii of the design language (mockups `.card` 20px, compact cards 16px, `.btn` 14px,
 * `.seg` 12px, pills and chips fully round). Use these instead of ad-hoc `RoundedCornerShape(n.dp)`.
 */
object VoltShapes {
    /** Full-width cards and the map card. */
    val CardRadius = 20.dp
    val card = RoundedCornerShape(CardRadius)

    /** Compact cards: half-width tiles, list rows, toasts, the map's trip chip. */
    val TileRadius = 16.dp
    val tile = RoundedCornerShape(TileRadius)

    /** Buttons and inline editors. */
    val control = RoundedCornerShape(14.dp)

    /** Text fields and segmented-control tracks. */
    val field = RoundedCornerShape(12.dp)

    /** The raised segment inside a segmented control; small chips like the lit gear. */
    val inner = RoundedCornerShape(9.dp)

    /** Pills and chips: fully rounded ends. */
    val chip = RoundedCornerShape(50)
}

/** Spacing scale (mockups `.content` 16px margin, 16px card padding, 10–12px gaps). */
object VoltSpacing {
    /** Page side margin. */
    val screen = 16.dp

    /** Padding inside a full card. */
    val card = 16.dp

    /** Padding inside a compact card / tile. */
    val tile = 14.dp

    /** Gap between sibling tiles in a grid. */
    val tileGap = 10.dp

    /** Gap between buttons and other controls in a row. */
    val controlGap = 12.dp
}
