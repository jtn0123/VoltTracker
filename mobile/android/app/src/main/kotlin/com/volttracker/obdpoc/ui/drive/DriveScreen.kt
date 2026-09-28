package com.volttracker.obdpoc.ui.drive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.volttracker.obdpoc.ui.components.ConnectRow
import com.volttracker.obdpoc.ui.components.IconCircleButton
import com.volttracker.obdpoc.ui.components.VoltChip
import com.volttracker.obdpoc.ui.components.VoltIcons
import com.volttracker.obdpoc.ui.components.VoltScreen
import com.volttracker.obdpoc.ui.components.ambientAlpha
import com.volttracker.obdpoc.ui.components.connectionDot
import com.volttracker.obdpoc.ui.components.voltAmbient
import com.volttracker.obdpoc.ui.theme.LocalVoltPalette
import com.volttracker.obdpoc.ui.theme.VoltPalette
import com.volttracker.obdpoc.ui.theme.VoltTheme
import kotlinx.coroutines.delay

/**
 * The Drive tab. Focus (default) is Direction A: the Arc ring that morphs between the power
 * gauge, the parked battery gauge and charge progress, with range, three tiles and the optional
 * energy-flow card. Detailed is Direction C, the cockpit. An "Engine on" toast appears when the
 * range extender starts.
 */
@Composable
fun DriveScreen(
    state: DriveUiState,
    modifier: Modifier = Modifier,
    onConnect: () -> Unit = {},
    onStartDemo: () -> Unit = {},
    showEnergyFlow: Boolean = true,
    onSetDetailed: (Boolean) -> Unit = {},
    initialToast: Boolean = false,
) {
    var detailed by rememberSaveable(state.detailed) { mutableStateOf(state.detailed) }
    val toastVisible = engineToastVisible(state, initialToast)
    val pal = LocalVoltPalette.current
    val glow = ambientColor(pal, state)
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .voltAmbient(glow),
    ) {
        val outside = outsideTempLabel(state)
        val largeText = LocalDensity.current.fontScale > HEADER_CHIP_MAX_SCALE
        VoltScreen(
            title = "Drive",
            subtitle = driveSubtitle(state),
            dot = connectionDot(state.connected),
            statusSubtitle = true,
            actions = {
                // At the larger text sizes the chip would crowd the status line; it moves below.
                if (detailed && !largeText) outside?.let { VoltChip(it) }
                IconCircleButton(
                    icon = if (detailed) VoltIcons.Drive else VoltIcons.Grid,
                    contentDescription = if (detailed) "Focus view" else "Detailed view",
                    onClick = {
                        detailed = !detailed
                        onSetDetailed(detailed)
                    },
                )
            },
        ) {
            if (detailed) {
                if (largeText) outside?.let { VoltChip(it, Modifier.padding(bottom = 8.dp)) }
                ConnectRow(state.connected, state.connecting, onConnect, onStartDemo)
                CockpitContent(state)
            } else {
                ArcGauge(state, Modifier.align(Alignment.CenterHorizontally))
                ConnectRow(state.connected, state.connecting, onConnect, onStartDemo)
                RangeCard(state)
                FocusTiles(state)
                if (showEnergyFlow) EnergyFlowCard(state)
            }
        }
        AnimatedVisibility(
            visible = toastVisible,
            enter = fadeIn() + slideInVertically { -it / 4 },
            exit = fadeOut() + slideOutVertically { -it / 4 },
            // Over the ring (below the app bar), not the tiles the driver is reading.
            modifier = Modifier.align(Alignment.TopCenter).padding(start = 12.dp, end = 12.dp, top = TOAST_TOP),
        ) {
            EngineOnToast(atReserve = state.atReserve)
        }
    }
}

/** True for [TOAST_MS] after the engine starts mid-drive (EV → gas); any other change hides it. */
@Composable
private fun engineToastVisible(
    state: DriveUiState,
    initial: Boolean,
): Boolean {
    var visible by remember { mutableStateOf(initial) }
    var lastMode by remember { mutableStateOf(state.mode) }
    LaunchedEffect(state.mode) {
        val started = lastMode == DriveMode.EV && state.mode == DriveMode.GAS && state.phase == DrivePhase.DRIVE
        lastMode = state.mode
        if (started) {
            visible = true
            delay(TOAST_MS)
            visible = false
        } else if (!initial) {
            visible = false
        }
    }
    return visible
}

private const val TOAST_MS = 3_600L

/** Above this text scale the cockpit's outside-temperature chip leaves the app bar. */
private const val HEADER_CHIP_MAX_SCALE = 1.15f

/** "64°F outside" for the cockpit chip, or null while not connected or not reported. */
fun outsideTempLabel(state: DriveUiState): String? =
    state.ambientF?.takeIf { state.connected }?.let { "${state.units.tempText(it.toDouble())} outside" }

private val TOAST_TOP = 72.dp

/** App-bar subtitle: the link and what the car is doing; while not connected, the connection status. */
fun driveSubtitle(state: DriveUiState): String {
    if (!state.connected) return state.statusLabel
    return when (state.phase) {
        DrivePhase.DRIVE -> "Live · ${state.adapterLabel}"
        DrivePhase.CHARGING -> "Connected · charging"
        DrivePhase.PARKED -> "Connected · parked"
    }
}

/** The screen's top glow (mockups `ambient`): tinted by what the car is doing. */
private fun ambientColor(
    pal: VoltPalette,
    state: DriveUiState,
): Color {
    val a = ambientAlpha(pal)
    return when {
        !state.connected -> Color.Transparent
        state.phase == DrivePhase.CHARGING -> pal.ev.copy(alpha = a)
        state.phase == DrivePhase.PARKED -> pal.volt.copy(alpha = a * PARKED_AMBIENT)
        else -> powerColor(pal, state.powerRole).copy(alpha = a)
    }
}

private const val PARKED_AMBIENT = 0.45f

@Preview(widthDp = 412, heightDp = 1100)
@Composable
private fun DriveScreenPreview() {
    VoltTheme { DriveScreen(DriveUiState.demo) }
}

@Preview(widthDp = 412, heightDp = 1100)
@Composable
private fun DriveScreenDetailedPreview() {
    VoltTheme { DriveScreen(DriveUiState.demo.copy(detailed = true)) }
}
