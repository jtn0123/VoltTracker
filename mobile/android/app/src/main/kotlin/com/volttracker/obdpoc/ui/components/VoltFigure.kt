package com.volttracker.obdpoc.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltFonts
import com.volttracker.obdpoc.ui.theme.VoltType

/**
 * A labelled headline figure (mockups `.kv`): caps label over a 26sp value with a small muted
 * [unit]. The unit is left off a [DASH] placeholder, which TalkBack reads as "Not reported".
 */
@Composable
fun VoltFigure(
    label: String,
    value: String,
    unit: String?,
    modifier: Modifier = Modifier,
    valueColor: Color = VoltColors.textPrimary,
) {
    Column(modifier = modifier) {
        VoltLabel(label)
        Spacer(Modifier.height(6.dp))
        Text(
            text =
                buildAnnotatedString {
                    append(value)
                    if (unit != null && value != DASH) {
                        withStyle(unitStyle(FIGURE_UNIT_SP)) {
                            append(" ${unbreakableUnit(unit)}")
                        }
                    }
                },
            style = VoltType.value.copy(fontSize = FIGURE_VALUE_SP.sp),
            color = valueColor,
            modifier = if (value == DASH) Modifier.semantics { contentDescription = NOT_REPORTED } else Modifier,
        )
    }
}

/**
 * A unit that wraps as one piece: "kWh/100 km" split after the slash in a narrow column, leaving
 * "km" alone on the next line. Non-breaking spaces, plus a word joiner after each slash.
 */
fun unbreakableUnit(unit: String): String = unit.replace(" ", "\u00A0").replace("/", "/\u2060")

/** The small muted unit after a number ("kWh", "mi", "%"), in the label face. */
@Composable
fun unitStyle(sizeSp: Int): SpanStyle =
    SpanStyle(fontFamily = VoltFonts.hanken, fontSize = sizeSp.sp, color = VoltColors.textSecondary)

private const val FIGURE_VALUE_SP = 26
private const val FIGURE_UNIT_SP = 13
