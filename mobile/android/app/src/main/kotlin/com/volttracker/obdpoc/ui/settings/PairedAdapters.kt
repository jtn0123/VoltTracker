package com.volttracker.obdpoc.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.volttracker.obdpoc.ui.components.VoltButton
import com.volttracker.obdpoc.ui.components.VoltIcons
import com.volttracker.obdpoc.ui.components.VoltLabel
import com.volttracker.obdpoc.ui.components.VoltListDivider
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltType

/** A Bluetooth device already paired in Android's settings. */
data class PairedAdapter(
    val name: String,
    val address: String,
    /** The name looks like an OBD adapter (OBDLink, Vgate, ELM327…); listed first. */
    val likelyObd: Boolean,
)

/** Why the paired list can or can't be read. */
enum class AdapterListState { READY, NEEDS_PERMISSION, BLUETOOTH_OFF, NO_BLUETOOTH }

/**
 * Settings → Adapter's picker: every paired device, OBD-looking ones first, tap to use one. It
 * replaces the jump to the classic dashboard that Connect used to make with no adapter chosen.
 */
@Composable
internal fun PairedAdaptersSection(
    state: SettingsUiState,
    onCommand: (SettingsCommand) -> Unit,
) {
    VoltLabel("Paired adapters", Modifier.padding(bottom = 4.dp))
    when (state.adapterList) {
        AdapterListState.NEEDS_PERMISSION ->
            Blocked(
                "Allow Nearby devices so Volt Tracker can see your paired adapters.",
                "Allow",
            ) { onCommand(SettingsCommand.AllowBluetooth) }
        AdapterListState.BLUETOOTH_OFF ->
            Blocked("Bluetooth is off.", "Turn on Bluetooth") { onCommand(SettingsCommand.AllowBluetooth) }
        AdapterListState.NO_BLUETOOTH -> Note("This phone has no Bluetooth.")
        AdapterListState.READY ->
            if (state.pairedAdapters.isEmpty()) {
                Blocked(
                    "No paired devices yet. Plug the adapter into the car, turn the car on, then pair it " +
                        "in Android's Bluetooth settings.",
                    "Open Bluetooth settings",
                ) { onCommand(SettingsCommand.OpenBluetoothSettings) }
            } else {
                state.pairedAdapters.forEachIndexed { i, adapter ->
                    if (i > 0) VoltListDivider()
                    AdapterRow(adapter, chosen = adapter.address == state.selectedAdapterAddress) {
                        onCommand(SettingsCommand.PickAdapter(adapter.address, adapter.name))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Note("Not listed? Pair it in Android's Bluetooth settings first.")
                Spacer(Modifier.height(8.dp))
                VoltButton(
                    text = "Open Bluetooth settings",
                    onClick = { onCommand(SettingsCommand.OpenBluetoothSettings) },
                )
            }
    }
    Spacer(Modifier.height(12.dp))
    VoltListDivider()
}

@Composable
private fun Blocked(
    message: String,
    action: String,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        Note(message)
        VoltButton(text = action, accent = true, onClick = onClick)
    }
}

@Composable
private fun AdapterRow(
    adapter: PairedAdapter,
    chosen: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = chosen, onClick = onClick, role = Role.RadioButton)
                .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = adapter.name.ifBlank { "Unnamed device" },
                style = VoltType.body,
                color = VoltColors.textPrimary,
            )
            Text(
                text = if (adapter.likelyObd) "OBD adapter" else "Other Bluetooth device",
                style = VoltType.caption,
                color = if (adapter.likelyObd) VoltColors.accent else VoltColors.textTertiary,
            )
        }
        if (chosen) {
            Icon(
                VoltIcons.Check,
                contentDescription = "In use",
                tint = VoltColors.accent,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
