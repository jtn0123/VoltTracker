package com.volttracker.obdpoc.ui.receipt

import java.util.Locale

/** One "label … value" line of a receipt; [note] is a muted qualifier such as "est.". */
data class ReceiptLine(
    val label: String,
    val value: String,
    val note: String? = null,
)

/**
 * A drive or charge written up like a receipt: a [title] and [whenLine], the headline figures,
 * what it measured ([details]), what it cost ([costs], empty until rates are set), and a plain
 * sentence on what it [saved] against gas, if anything.
 */
data class Receipt(
    val title: String,
    val whenLine: String,
    val details: List<ReceiptLine>,
    val costs: List<ReceiptLine> = emptyList(),
    val saved: String? = null,
) {
    /** The receipt as plain text for the share sheet. */
    fun shareText(): String =
        buildString {
            appendLine(title)
            appendLine(whenLine)
            appendLine()
            (details + costs).forEach { line ->
                append(line.label).append(": ").append(line.value)
                line.note?.let { append(" (").append(it).append(')') }
                appendLine()
            }
            saved?.let {
                appendLine()
                appendLine(it)
            }
            appendLine()
            append(SIGNATURE)
        }

    companion object {
        const val SIGNATURE = "— VoltTracker"
    }
}

/** "$1.42"; a negative amount reads "-$0.30". */
fun money(value: Double): String {
    val text = String.format(Locale.US, "$%.2f", kotlin.math.abs(value))
    return if (value < -HALF_CENT) "-$text" else text
}

private const val HALF_CENT = 0.005
