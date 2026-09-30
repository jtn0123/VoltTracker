package com.volttracker.obdpoc.ui.receipt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.volttracker.obdpoc.ui.components.IconSquare
import com.volttracker.obdpoc.ui.components.PillTone
import com.volttracker.obdpoc.ui.components.VoltButton
import com.volttracker.obdpoc.ui.components.VoltIcons
import com.volttracker.obdpoc.ui.components.VoltLabel
import com.volttracker.obdpoc.ui.components.VoltListDivider
import com.volttracker.obdpoc.ui.components.VoltPanel
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltType

/**
 * The body of a receipt page under its hero: the measured lines, the costs (or a nudge to set
 * rates), the savings sentence, and the share button with any [extra] buttons below it.
 */
@Composable
fun ReceiptBody(
    receipt: Receipt,
    onShare: () -> Unit,
    noRatesHint: String,
    extra: @Composable () -> Unit = {},
) {
    Spacer(Modifier.height(10.dp))
    ReceiptCard("Details", receipt.details)
    Spacer(Modifier.height(10.dp))
    if (receipt.costs.isEmpty()) {
        VoltPanel {
            VoltLabel("Cost")
            Text(
                text = noRatesHint,
                style = VoltType.caption,
                color = VoltColors.textSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    } else {
        ReceiptCard("Cost", receipt.costs, totalLast = true)
    }
    receipt.saved?.let {
        Spacer(Modifier.height(10.dp))
        SavedNote(it)
    }
    Spacer(Modifier.height(16.dp))
    VoltButton("Share", Modifier.fillMaxWidth(), accent = true, icon = VoltIcons.Share, onClick = onShare)
    extra()
}

/** A labelled card of receipt lines; [totalLast] sets the last line in bold, as a total. */
@Composable
fun ReceiptCard(
    label: String,
    lines: List<ReceiptLine>,
    totalLast: Boolean = false,
) {
    VoltPanel(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)) {
        VoltLabel(label, Modifier.padding(bottom = 6.dp))
        lines.forEachIndexed { i, line ->
            val total = totalLast && i == lines.lastIndex
            if (i > 0) VoltListDivider()
            ReceiptLineRow(line, total)
        }
    }
}

@Composable
private fun ReceiptLineRow(
    line: ReceiptLine,
    total: Boolean,
) {
    val spoken = listOfNotNull(line.label, line.value, line.note?.let { if (it == "est.") "estimated" else it })
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 11.dp)
                .clearAndSetSemantics { contentDescription = spoken.joinToString(", ") },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = line.label,
            style = if (total) VoltType.bodyStrong else VoltType.body,
            color = if (total) VoltColors.textPrimary else VoltColors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        line.note?.let { Text(it, style = VoltType.caption, color = VoltColors.textTertiary) }
        Text(
            text = line.value,
            style = VoltType.body.copy(fontWeight = if (total) FontWeight.Bold else FontWeight.SemiBold),
            color = VoltColors.textPrimary,
            textAlign = TextAlign.End,
        )
    }
}

/** The savings sentence, with the electric bolt. */
@Composable
private fun SavedNote(text: String) {
    VoltPanel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconSquare(VoltIcons.Bolt, tone = PillTone.EV)
            Column(Modifier.weight(1f)) {
                Text(text, style = VoltType.body, color = VoltColors.textPrimary)
            }
        }
    }
}
