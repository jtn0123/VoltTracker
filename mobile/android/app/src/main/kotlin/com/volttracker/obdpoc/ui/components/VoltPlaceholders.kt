package com.volttracker.obdpoc.ui.components

/**
 * The one placeholder for a figure the car hasn't reported (or that has gone stale): a bare em
 * dash with no unit, so "— mi" or "-- V" never suggests a value. TalkBack reads [NOT_REPORTED].
 */
const val DASH = "—"

/** What TalkBack says for a [DASH]. */
const val NOT_REPORTED = "Not reported"

/** [DASH] for a missing value, else the value. */
fun orDash(value: String?): String = value ?: DASH

/** "38 mi", or a bare [DASH] (no unit) when [value] is missing. */
fun withUnit(
    value: String?,
    unit: String,
    separator: String = " ",
): String = if (value == null) DASH else "$value$separator$unit"

/** The spoken form of a figure: [NOT_REPORTED] for a [DASH]. */
fun spoken(text: String): String = if (text == DASH) NOT_REPORTED else text
