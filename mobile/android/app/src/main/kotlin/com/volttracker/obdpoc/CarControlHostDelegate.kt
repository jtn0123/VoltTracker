package com.volttracker.obdpoc

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.LinearLayout
import com.volttracker.obdpoc.service.ObdService
import org.json.JSONObject

/** Car controls as seen by the dashboard bridge. */
interface CarControlCommands {
    /** `{available, enabled, unlocked, unlockedRemainingMs, pinLockedOut}`. */
    fun getCarControlStateJson(): String

    /** Off: immediate. On: native warning + set-PIN dialog; only enabled once a valid PIN is set. */
    fun setCarControlsEnabled(enabled: Boolean)

    /** Native confirmation (plus PIN outside the unlock window), then hands the command to the service. */
    fun requestCarControl(command: String?)

    /** Ends the PIN unlock window now. */
    fun relockCarControls()

    companion object {
        val UNAVAILABLE: CarControlCommands =
            object : CarControlCommands {
                override fun getCarControlStateJson(): String = "{\"available\":false}"

                override fun setCarControlsEnabled(enabled: Boolean) = Unit

                override fun requestCarControl(command: String?) = Unit

                override fun relockCarControls() = Unit
            }
    }
}

/**
 * Owns every car-controls interaction that needs the user: the opt-in (warning + PIN setup) and the
 * per-command confirmation (naming the action, with the PIN unless it was entered in the last
 * [CarControlAuth.UNLOCK_WINDOW_MS]). All of it is NATIVE UI, so dashboard JavaScript can only ask
 * for a command by name; it cannot supply a PIN, skip the confirmation, or send frames.
 *
 * A confirmed command becomes a one-shot grant in [CarControlAuth] and an [ObdService.ACTION_CAR_CONTROL]
 * intent; the engine re-checks everything (opt-in, grant, parked, adapter) before transmitting.
 */
class CarControlHostDelegate(
    private val activity: Activity,
    prefs: () -> SharedPreferences?,
    private val publishAppState: () -> Unit,
    private val toast: (String) -> Unit,
    private val hasLiveSession: () -> Boolean = ObdService::hasActiveSession,
    private val dispatch: (CarCommand) -> Unit = { command -> defaultDispatch(activity, command) },
    private val clock: () -> Long = System::currentTimeMillis,
) : CarControlCommands {
    private val settings = CarControlSettings(prefs)

    override fun getCarControlStateJson(): String {
        val now = clock()
        return JSONObject()
            .put("available", true)
            .put("enabled", settings.isEnabled())
            .put("unlocked", CarControlAuth.isUnlocked(now))
            .put("unlockedRemainingMs", CarControlAuth.unlockedRemainingMs(now))
            .put("pinLockedOut", CarControlAuth.isPinLockedOut(now))
            .toString()
    }

    override fun setCarControlsEnabled(enabled: Boolean) {
        activity.runOnUiThread {
            if (!enabled) {
                settings.disable()
                CarControlAuth.relock()
                publishAppState()
                toast("Car controls turned off")
            } else if (!settings.isEnabled()) {
                showEnableDialog()
            }
        }
    }

    override fun requestCarControl(command: String?) {
        val parsed = CarCommand.fromWireName(bridgeSafe(command, MAX_COMMAND_LEN)) ?: return
        activity.runOnUiThread {
            val now = clock()
            when {
                !settings.isEnabled() -> toast("Turn on car controls in Settings first.")
                CarControlAuth.isPinLockedOut(now) -> toast("Too many wrong PINs. Try again in a few minutes.")
                !hasLiveSession() -> toast("Connect to the car first. Commands only run in a live session.")
                else -> showCommandDialog(parsed, needPin = !CarControlAuth.isUnlocked(now))
            }
        }
    }

    override fun relockCarControls() {
        CarControlAuth.relock()
        publishAppState()
    }

    private fun showEnableDialog() {
        val pin = pinField("New PIN (4-8 digits)")
        val repeat = pinField("Repeat PIN")
        show(
            AlertDialog
                .Builder(activity)
                .setTitle("Turn on car controls (experimental)?")
                .setMessage(ENABLE_WARNING)
                .setView(column(pin, repeat))
                .setPositiveButton("Turn on") { _, _ ->
                    val first = pin.text.toString()
                    when {
                        !CarControlSettings.isValidPin(
                            first,
                        ) -> toast("The PIN must be 4 to 8 digits. Car controls stay off.")
                        first != repeat.text.toString() -> toast("The PINs did not match. Car controls stay off.")
                        settings.enable(first) -> {
                            CarControlAuth.relock()
                            publishAppState()
                            toast("Car controls turned on")
                        }
                        else -> toast("Could not save the PIN. Car controls stay off.")
                    }
                }.setNegativeButton(R.string.dialog_cancel, null),
        )
    }

    private fun showCommandDialog(
        command: CarCommand,
        needPin: Boolean,
    ) {
        val pin = if (needPin) pinField("PIN") else null
        val builder =
            AlertDialog
                .Builder(activity)
                .setTitle("${command.label}?")
                .setMessage(commandWarning(command))
                .setPositiveButton(command.label) { _, _ -> onCommandConfirmed(command, pin?.text?.toString()) }
                .setNegativeButton(R.string.dialog_cancel, null)
        if (pin != null) builder.setView(column(pin))
        show(builder)
    }

    private fun onCommandConfirmed(
        command: CarCommand,
        pin: String?,
    ) {
        val now = clock()
        if (pin != null && !CarControlAuth.recordPinAttempt(settings.verifyPin(pin), now)) {
            toast(
                if (CarControlAuth.isPinLockedOut(
                        now,
                    )
                ) {
                    "Too many wrong PINs. Locked for 5 minutes."
                } else {
                    "Wrong PIN. Nothing was sent."
                },
            )
            return
        }
        if (!CarControlAuth.isUnlocked(now)) {
            toast("Enter the PIN again. Nothing was sent.")
            return
        }
        CarControlAuth.confirm(command, now)
        dispatch(command)
        publishAppState()
        toast("Sending: ${command.label}")
    }

    private fun pinField(hint: String): EditText =
        EditText(activity).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(CarControlSettings.MAX_PIN_LENGTH))
        }

    private fun column(vararg fields: EditText): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (DIALOG_PADDING_DP * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            fields.forEach(::addView)
        }

    private fun show(builder: AlertDialog.Builder) {
        try {
            builder.showStyled()
        } catch (ex: RuntimeException) {
            // The activity can be finishing between the bridge call and the UI thread.
            Log.w(AppPrefs.LOG_TAG, "car-control dialog failed", ex)
        }
    }

    companion object {
        private const val MAX_COMMAND_LEN = 32
        private const val DIALOG_PADDING_DP = 20

        const val ENABLE_WARNING =
            "Experimental. VoltTracker will be able to send body commands (lock, unlock, lights and " +
                "horn, remote start, windows) to your car through the OBD port. The command frames " +
                "come from the open-source OVMS project and have NOT been tested on your car by " +
                "VoltTracker. They only run with an OBDLink adapter, during a live connection, with " +
                "the car parked, and after you confirm each one. A wrong command could leave the car " +
                "unlocked, running, or with windows open. Set a PIN to continue; it is required for " +
                "every command (once entered it stays valid for 5 minutes)."

        @JvmStatic
        fun commandWarning(command: CarCommand): String {
            val specific =
                when (command) {
                    CarCommand.LOCK -> "The doors will lock."
                    CarCommand.UNLOCK -> "The doors will unlock. Anyone nearby can open the car."
                    CarCommand.FLASH_LIGHTS -> "The exterior lights will flash."
                    CarCommand.LOCATE -> "The horn will sound and the lights will flash."
                    CarCommand.REMOTE_START ->
                        "The car will remote start and run cabin climate. Only do this where the car is ventilated."
                    CarCommand.REMOTE_STOP -> "A running remote start will stop."
                    CarCommand.WINDOWS_DOWN -> "All four windows will open fully."
                    CarCommand.WINDOWS_UP ->
                        "All four windows will close fully. Make sure nothing and no one is in the way."
                }
            return "$specific\n\nSent to the car through the OBD adapter. Experimental: not yet verified on your car."
        }

        private fun defaultDispatch(
            activity: Activity,
            command: CarCommand,
        ) {
            val intent =
                Intent(activity, ObdService::class.java)
                    .setAction(ObdService.ACTION_CAR_CONTROL)
                    .putExtra(ObdService.EXTRA_CAR_COMMAND, command.wireName)
            try {
                activity.startService(intent)
            } catch (ex: RuntimeException) {
                Log.w(AppPrefs.LOG_TAG, "car-control dispatch failed", ex)
            }
        }
    }
}
