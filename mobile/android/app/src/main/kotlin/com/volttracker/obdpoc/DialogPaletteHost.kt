package com.volttracker.obdpoc

import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.DialogInterface
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.volttracker.obdpoc.ui.theme.VoltPalette

/**
 * An Activity whose platform dialogs should wear the Compose theme. ComposeDashboardActivity sets
 * [dialogPalette] from the chosen appearance; the classic dashboard doesn't implement this, so its
 * dialogs keep the platform look.
 */
interface DialogPaletteHost {
    val dialogPalette: VoltPalette?
}

/**
 * Shows the dialog and, under a [DialogPaletteHost], recolours it to the current Volt palette:
 * card surface and radius, text colours, accent buttons in sentence case. Without that, a car
 * control or clear-codes confirmation popped up as a dark Material 1 box over the Latte screens.
 * The light/dark base (title and field colours) comes from the host's alertDialogTheme overlay.
 */
fun AlertDialog.Builder.showStyled(): AlertDialog {
    val dialog = show()
    val palette = context.paletteHost()?.dialogPalette ?: return dialog
    val density = context.resources.displayMetrics.density
    dialog.window?.setBackgroundDrawable(
        GradientDrawable().apply {
            setColor(palette.surface.toArgb())
            cornerRadius = DIALOG_RADIUS_DP * density
        },
    )
    dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(palette.muted.toArgb())
    listOf(DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL)
        .mapNotNull(dialog::getButton)
        .forEach { button ->
            button.isAllCaps = false
            button.setTextColor(palette.volt.toArgb())
        }
    return dialog
}

/** Builder.context is a ContextThemeWrapper around the Activity, so walk down to the host. */
private tailrec fun Context.paletteHost(): DialogPaletteHost? =
    when (this) {
        is DialogPaletteHost -> this
        is ContextWrapper -> baseContext?.paletteHost()
        else -> null
    }

private const val DIALOG_RADIUS_DP = 22f
