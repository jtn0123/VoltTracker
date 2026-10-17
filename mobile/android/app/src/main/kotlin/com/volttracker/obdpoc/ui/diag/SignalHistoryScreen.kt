package com.volttracker.obdpoc.ui.diag

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.volttracker.obdpoc.signals.DiagnosticSignal
import com.volttracker.obdpoc.signals.DiagnosticSignals
import com.volttracker.obdpoc.signals.SignalPoint
import com.volttracker.obdpoc.ui.components.ButtonStyle
import com.volttracker.obdpoc.ui.components.LocalVoltPrefs
import com.volttracker.obdpoc.ui.components.VoltButton
import com.volttracker.obdpoc.ui.components.VoltEmptyState
import com.volttracker.obdpoc.ui.components.VoltLabel
import com.volttracker.obdpoc.ui.components.VoltPanel
import com.volttracker.obdpoc.ui.components.VoltScreen
import com.volttracker.obdpoc.ui.theme.LocalVoltPalette
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltType
import java.text.SimpleDateFormat
import java.util.Date

/** Saved signals remain available without a connection. Selection never asks the car for more data. */
@Composable
fun SignalHistoryScreen(
    history: SignalHistoryUiState,
    metric: Boolean = false,
    demo: Boolean = false,
    onBack: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onSession: (Long) -> Unit = {},
    onExport: () -> Unit = {},
) {
    LaunchedEffect(demo) { onRefresh() }
    val state = history.takeIf { it.demo == demo } ?: SignalHistoryUiState(demo = demo)
    val recording = state.recording
    var selectedKey by rememberSaveable { mutableStateOf(DiagnosticSignals.CHARTS.first().key) }
    val signal = DiagnosticSignals.CHARTS.firstOrNull { it.key == selectedKey } ?: DiagnosticSignals.CHARTS.first()
    val clock24h = LocalVoltPrefs.current.clock24h
    val locale = LocalLocale.current.platformLocale
    val stamp =
        remember(
            clock24h,
            locale,
        ) { SimpleDateFormat(if (clock24h) "MMM d · HH:mm" else "MMM d · h:mm a", locale) }
    VoltScreen(
        title = "Signal history",
        subtitle = if (demo) "Demo recording" else "Recorded on this phone",
        onBack = onBack,
    ) {
        if (state.error != null) Text(state.error, style = VoltType.body, color = VoltColors.textPrimary)
        if (state.loading) Text("Loading recorded signals…", style = VoltType.body, color = VoltColors.textSecondary)
        if (recording == null && !state.loading && state.error == null) {
            VoltEmptyState(
                "No recordings yet",
                body = "Connect the adapter and record a session. Its signals will be available here afterward.",
            )
        }
        if (recording != null) {
            HistoryPicker(
                label = "Recording",
                selected = stamp.format(Date(recording.session.startedAtMs)),
                options = state.sessions.map { it.id.toString() to stamp.format(Date(it.startedAtMs)) },
                enabled = !state.loading && !state.exporting,
                onPick = { it.toLongOrNull()?.let(onSession) },
            )
            Spacer(Modifier.height(12.dp))
            HistoryPicker("Signal", signal.label, DiagnosticSignals.CHARTS.map { it.key to it.label }, onPick = {
                selectedKey =
                    it
            })
            Spacer(Modifier.height(16.dp))
            VoltPanel {
                VoltLabel(signal.label, unit = if (signal.unit == "°C" && !metric) "°F" else signal.unit)
                val points = recording.series[signal.key].orEmpty()
                SignalHistoryChart(points, signal, metric, recording.firstAtMs, recording.lastAtMs)
            }
            Text(
                "${recording.session.sampleCount} samples · full session · gaps mean unavailable readings",
                style = VoltType.caption,
                color = VoltColors.textSecondary,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            VoltButton(
                text = if (state.exporting) "Exporting…" else "Export diagnostic CSV",
                onClick = onExport,
                enabled = !state.loading && !state.exporting && !demo,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                if (demo) {
                    "Demo recordings can't be exported."
                } else {
                    "All recorded samples, fixed units and reading ages. " +
                        "Route and vehicle identity are excluded."
                },
                style = VoltType.caption,
                color = VoltColors.textSecondary,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            )
        }
        VoltButton(
            "Refresh recordings",
            onClick = onRefresh,
            enabled = !state.loading && !state.exporting,
            style = ButtonStyle.SECONDARY,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun HistoryPicker(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    enabled: Boolean = true,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column {
        VoltLabel(label, Modifier.padding(bottom = 6.dp))
        Box {
            VoltButton(selected, onClick = {
                open = true
            }, enabled = enabled, style = ButtonStyle.SECONDARY, modifier = Modifier.fillMaxWidth())
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = VoltColors.surface) {
                options.forEach { (id, title) ->
                    DropdownMenuItem(text = { Text(title, color = VoltColors.textPrimary) }, onClick = {
                        open = false
                        onPick(id)
                    })
                }
            }
        }
    }
}

@Composable
private fun SignalHistoryChart(
    points: List<SignalPoint>,
    signal: DiagnosticSignal,
    metric: Boolean,
    firstAtMs: Long,
    lastAtMs: Long,
) {
    val display =
        points.map {
            it.copy(
                value =
                    it.value?.let { v ->
                        if (signal.unit == "°C" &&
                            !metric
                        ) {
                            v * 1.8 + 32
                        } else {
                            v
                        }
                    },
            )
        }
    val values = display.mapNotNull { it.value }
    if (values.isEmpty()) {
        Text(
            "This signal wasn't recorded in this session.",
            style = VoltType.body,
            color = VoltColors.textSecondary,
            modifier = Modifier.padding(vertical = 24.dp),
        )
        return
    }
    val min = values.min()
    val max = values.max()
    val range = (max - min).coerceAtLeast(1.0)
    val lo = min - range * 0.1
    val hi = max + range * 0.1
    val pal = LocalVoltPalette.current
    val locale = LocalLocale.current.platformLocale
    val time = SimpleDateFormat(if (LocalVoltPrefs.current.clock24h) "HH:mm" else "h:mm a", locale)
    val lowLabel = String.format(locale, "%.1f", min)
    val highLabel = String.format(locale, "%.1f", max)
    Text(
        "Low $lowLabel · high $highLabel",
        style = VoltType.caption,
        color = VoltColors.textSecondary,
        modifier = Modifier.padding(vertical = 10.dp),
    )
    Canvas(
        Modifier.fillMaxWidth().height(180.dp).semantics {
            contentDescription =
                "${signal.label} over the recording, low $lowLabel, high $highLabel. Gaps are unavailable readings."
        },
    ) {
        val inset = 4.dp.toPx()
        val w = size.width - 2 * inset
        val h = size.height - 2 * inset
        val span = (lastAtMs - firstAtMs).coerceAtLeast(1L).toDouble()
        repeat(3) { i ->
            val y = inset + h * i / 2
            drawLine(pal.line, Offset(inset, y), Offset(inset + w, y), strokeWidth = 1.dp.toPx())
        }
        val path = Path()
        var previous: SignalPoint? = null
        for (point in display) {
            val value = point.value
            if (value == null) {
                previous = null
                continue
            }
            val at =
                Offset(
                    inset + w * ((point.atMs - firstAtMs) / span).toFloat(),
                    inset + h * (1 - (value - lo) / (hi - lo)).toFloat(),
                )
            if (previous == null ||
                previous.segment != point.segment
            ) {
                path.moveTo(at.x, at.y)
            } else {
                path.lineTo(at.x, at.y)
            }
            drawCircle(pal.ev, 2.dp.toPx(), at)
            previous = point
        }
        drawPath(path, pal.ev, style = Stroke(2.dp.toPx()))
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(time.format(Date(firstAtMs)), style = VoltType.caption, color = VoltColors.textSecondary)
        Text(time.format(Date(lastAtMs)), style = VoltType.caption, color = VoltColors.textSecondary)
    }
}
