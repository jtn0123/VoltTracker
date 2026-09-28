package com.volttracker.obdpoc.ui.drive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volttracker.obdpoc.ui.components.IconCircleButton
import com.volttracker.obdpoc.ui.components.VoltButton
import com.volttracker.obdpoc.ui.components.VoltIcons
import com.volttracker.obdpoc.ui.components.VoltScreen
import com.volttracker.obdpoc.ui.components.ambientAlpha
import com.volttracker.obdpoc.ui.components.connectionDot
import com.volttracker.obdpoc.ui.components.voltAmbient
import com.volttracker.obdpoc.ui.theme.LocalVoltPalette
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltPalette
import com.volttracker.obdpoc.ui.theme.VoltShapes
import com.volttracker.obdpoc.ui.theme.VoltTheme
import com.volttracker.obdpoc.ui.theme.VoltType
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
        VoltScreen(
            title = "Drive",
            subtitle = driveSubtitle(state, detailed),
            dot = connectionDot(state.connected),
            actions = {
                if (detailed && state.connected) OutsideTempChip(state.ambientF)
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
                ConnectRowIfNeeded(state, onConnect, onStartDemo)
                CockpitContent(state)
            } else {
                ArcGauge(state, Modifier.align(Alignment.CenterHorizontally))
                ConnectRowIfNeeded(state, onConnect, onStartDemo)
                RangeCard(state)
                FocusTiles(state)
                if (showEnergyFlow) EnergyFlowCard(state)
            }
        }
        AnimatedVisibility(
            visible = toastVisible,
            enter = fadeIn() + slideInVertically { -it / 4 },
            exit = fadeOut() + slideOutVertically { -it / 4 },
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
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

/**
 * App-bar subtitle: the link and what the car is doing. The cockpit adds the live signal
 * count; while not connected it is the connection status itself.
 */
fun driveSubtitle(
    state: DriveUiState,
    detailed: Boolean,
): String {
    if (!state.connected) return state.statusLabel
    val signals = if (detailed && state.signalCount > 0) " · ${state.signalCount} signals" else ""
    return when (state.phase) {
        DrivePhase.DRIVE -> if (detailed) "Live · 1 Hz$signals" else "Live · ${state.adapterLabel}"
        DrivePhase.CHARGING -> "Connected · charging$signals"
        DrivePhase.PARKED -> "Connected · parked$signals"
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

/** Offered while no session is live: reconnect the last adapter, or preview with demo data. */
@Composable
private fun ConnectRowIfNeeded(
    state: DriveUiState,
    onConnect: () -> Unit,
    onStartDemo: () -> Unit,
) {
    // Hidden mid-handshake so a second tap can't start a replacement session.
    if (state.connected || state.connecting) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    ) {
        VoltButton(text = "Connect", accent = true, onClick = onConnect)
        VoltButton(text = "Demo", icon = VoltIcons.Play, onClick = onStartDemo)
    }
}

/** The cockpit's outside-temperature chip (mockups `.chip`). */
@Composable
private fun OutsideTempChip(ambientF: Int) {
    Box(
        modifier =
            Modifier
                .height(26.dp)
                .background(VoltColors.surfaceElevated, VoltShapes.chip)
                .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$ambientF°F",
            style = VoltType.value.copy(fontSize = 13.sp),
            color = VoltColors.textSecondary,
        )
    }
}

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
