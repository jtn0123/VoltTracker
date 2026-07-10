package com.volttracker.obdpoc

import android.bluetooth.BluetoothAdapter

internal const val BRIDGE_MAX_ADDRESS_LEN = 64
internal const val BRIDGE_MAX_NAME_LEN = 256

// General-purpose cap for short identifier-ish bridge inputs (e.g. route keys,
// session ids, short labels and numeric id strings). The trip-label path uses
// ObdTripLabels.MAX_LABEL_LEN so the user-visible label cap is defined once.
internal const val BRIDGE_MAX_LABEL_LEN = 128
internal const val BRIDGE_MAX_STAGE_LEN = 32
internal const val BRIDGE_MAX_DETAIL_LEN = 4096
internal const val BRIDGE_MAX_DTC_LEN = 16
internal const val BRIDGE_MAX_PASSPHRASE_LEN = 256

internal fun bridgeSafe(
    value: String?,
    maxLen: Int,
): String {
    val trimmed = value?.trim() ?: return ""
    if (trimmed.length <= maxLen) {
        return trimmed
    }
    var cut = maxLen
    if (cut > 0 && Character.isHighSurrogate(trimmed[cut - 1])) {
        cut -= 1
    }
    return trimmed.substring(0, cut)
}

/** Bounds untrusted WebView text and removes characters that can forge extra logcat records. */
internal fun bridgeLogSafe(
    value: String?,
    maxLen: Int,
): String =
    buildString {
        for (character in bridgeSafe(value, maxLen)) {
            append(
                if (
                    Character.isISOControl(character) ||
                    Character.getType(character) == Character.FORMAT.toInt()
                ) {
                    '_'
                } else {
                    character
                },
            )
        }
    }

internal fun validBridgeBluetoothAddress(address: String?): Boolean =
    address != null && BluetoothAdapter.checkBluetoothAddress(address.trim())

internal fun parseBridgePositiveId(value: String?): Long =
    try {
        val parsed = bridgeSafe(value, BRIDGE_MAX_LABEL_LEN).toLong()
        if (parsed > 0L) parsed else -1L
    } catch (ex: RuntimeException) {
        -1L
    }
