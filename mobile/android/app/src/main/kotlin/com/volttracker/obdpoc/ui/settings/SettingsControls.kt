package com.volttracker.obdpoc.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.volttracker.obdpoc.BRIDGE_MAX_PASSPHRASE_LEN
import com.volttracker.obdpoc.ui.components.VoltButton
import com.volttracker.obdpoc.ui.components.VoltIcons
import com.volttracker.obdpoc.ui.components.VoltSegmented
import com.volttracker.obdpoc.ui.components.VoltSwitch
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltShapes
import com.volttracker.obdpoc.ui.theme.VoltType

// The building blocks the Settings detail pages are made of.

/** One settings row: label (+ optional subtitle) with a trailing control. */
@Composable
internal fun SettingRow(
    label: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(text = label, style = VoltType.body, color = VoltColors.textPrimary)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(text = subtitle, style = VoltType.caption, color = VoltColors.textTertiary)
            }
        }
        trailing()
    }
}

/** A whole row that flips a switch: TalkBack reads it as one switch with its label. */
@Composable
internal fun ToggleRow(
    label: String,
    on: Boolean,
    subtitle: String? = null,
    onChange: (Boolean) -> Unit,
) {
    SettingRow(
        label = label,
        subtitle = subtitle,
        modifier = Modifier.toggleable(value = on, role = Role.Switch, onValueChange = onChange),
    ) { VoltSwitch(on) }
}

@Composable
internal fun Value(value: String) {
    Text(text = value, style = VoltType.body, color = VoltColors.textSecondary)
}

@Composable
internal fun Note(text: String) {
    Text(text = text, style = VoltType.caption, color = VoltColors.textSecondary)
}

/** A row showing a stored value; tapping it opens the editor. */
@Composable
internal fun ValueRow(
    label: String,
    value: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    SettingRow(
        label = label,
        subtitle = subtitle,
        modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Edit $label", onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Value(value)
            Icon(
                VoltIcons.ChevronRight,
                contentDescription = null,
                tint = VoltColors.textTertiary,
                modifier = Modifier.padding(start = 4.dp).size(18.dp),
            )
        }
    }
}

/** A label over a segmented choice (units, text size, thresholds). */
@Composable
internal fun ChoiceRow(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    subtitle: String? = null,
    onSelect: (Int) -> Unit,
) {
    SettingRow(label = label, subtitle = subtitle) {}
    VoltSegmented(
        options = options,
        selectedIndex = selectedIndex,
        role = Role.RadioButton,
        onSelect = onSelect,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    )
}

/** What a [NumberEditor] edits: its title, the allowed range, and how to show the unit. */
internal data class NumberField(
    val title: String,
    val unit: String,
    val min: Double,
    val max: Double,
    /** Whether "Clear" is offered (the value may be unset). */
    val clearable: Boolean = true,
) {
    /** The typed text as a value inside the range, or null when it isn't a number. */
    fun parse(text: String): Double? =
        text
            .trim()
            .removePrefix("$")
            .toDoubleOrNull()
            ?.takeIf { it.isFinite() }
            ?.coerceIn(min, max)

    /** The allowed range in words: "From $0 to $2 per kWh", "From 50% to 100%", "From 5 to 150 mpg". */
    val rangeLabel: String
        get() {
            val low = SettingsUiState.formatNumber(min)
            val high = SettingsUiState.formatNumber(max)
            return when {
                unit.startsWith("$/") -> "From $$low to $$high per ${unit.removePrefix("$/")}"
                unit == "%" -> "From $low% to $high%"
                else -> "From $low to $high $unit"
            }
        }
}

/** The inline error under a [NumberEditor] field: shown once something non-numeric is typed. */
internal fun numberError(
    text: String,
    parsed: Double?,
): String? = if (text.isNotBlank() && parsed == null) "Enter a number" else null

/**
 * An inline number editor that opens under the row it edits. Out-of-range entries are clamped, and
 * the range is shown up front, as in the classic dashboard's inputs. [onSave] gets null for "Clear".
 */
@Composable
internal fun NumberEditor(
    field: NumberField,
    initial: Double?,
    onDismiss: () -> Unit,
    onSave: (Double?) -> Unit,
) {
    var text by rememberSaveable(field.title) { mutableStateOf(initial?.let(SettingsUiState::formatNumber).orEmpty()) }
    val parsed = field.parse(text)
    InlineEditor(
        caption = field.rangeLabel,
        error = numberError(text, parsed),
        text = text,
        onTextChange = { text = it.take(MAX_CHARS) },
        fieldDescription = field.title,
        keyboardType = KeyboardType.Decimal,
        onDismiss = onDismiss,
        leading = {
            if (field.clearable) {
                VoltButton(text = "Clear", onClick = { onSave(null) })
            }
        },
        confirm = {
            VoltButton(text = "Save", accent = true, enabled = parsed != null, onClick = { parsed?.let(onSave) })
        },
    )
}

/**
 * An inline, masked passphrase entry for backup and restore. [onConfirm] gets null when it was
 * left blank. The passphrase lives only in this composable's state (never saved across process death).
 */
@Composable
internal fun PassphraseEditor(
    hint: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    InlineEditor(
        caption = hint,
        text = text,
        onTextChange = { text = it.take(BRIDGE_MAX_PASSPHRASE_LEN) },
        fieldDescription = "Passphrase",
        keyboardType = KeyboardType.Password,
        visualTransformation = PasswordVisualTransformation(),
        onDismiss = onDismiss,
        confirm = {
            VoltButton(
                text = confirmLabel,
                accent = true,
                onClick = { onConfirm(text.ifBlank { null }) },
            )
        },
    )
}

/** The shared chrome of the inline editors: caption, one text field, and a button row. */
@Composable
private fun InlineEditor(
    caption: String,
    text: String,
    onTextChange: (String) -> Unit,
    fieldDescription: String,
    keyboardType: KeyboardType,
    onDismiss: () -> Unit,
    confirm: @Composable () -> Unit,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    error: String? = null,
    leading: @Composable () -> Unit = {},
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .clip(VoltShapes.control)
                .background(VoltColors.surfaceElevated)
                .padding(14.dp),
    ) {
        Text(text = caption, style = VoltType.caption, color = VoltColors.textTertiary)
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            singleLine = true,
            textStyle = VoltType.value.copy(color = VoltColors.textPrimary),
            cursorBrush = SolidColor(VoltColors.accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = visualTransformation,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, VoltColors.line2, VoltShapes.field)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .semantics { contentDescription = fieldDescription }
                    .testTag("settings-number-input"),
        )
        if (error != null) {
            Text(
                text = error,
                style = VoltType.caption,
                color = VoltColors.alert,
                modifier = Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            leading()
            VoltButton(text = "Cancel", onClick = onDismiss)
            confirm()
        }
    }
}

private const val MAX_CHARS = 8
