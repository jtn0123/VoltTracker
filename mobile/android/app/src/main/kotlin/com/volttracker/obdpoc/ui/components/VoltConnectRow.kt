package com.volttracker.obdpoc.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Offered while no session is live (Drive and Charge): reconnect the last adapter, or preview
 * with demo data. Hidden mid-handshake so a second tap can't start a replacement session; the
 * two buttons wrap onto two lines at large text sizes instead of clipping.
 */
@Composable
fun ConnectRow(
    connected: Boolean,
    connecting: Boolean,
    onConnect: () -> Unit,
    onStartDemo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (connected || connecting) return
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        VoltButton(text = "Connect", accent = true, onClick = onConnect)
        VoltButton(text = "Demo", icon = VoltIcons.Play, onClick = onStartDemo)
    }
}
