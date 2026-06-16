package com.volttracker.obdpoc.widget

/**
 * Pure presentation logic for the home-screen widget: given a [WidgetSnapshot] and the current
 * wall-clock time, decides WHAT to show. Holds no Android framework dependencies so it is fully
 * unit-testable; the actual RemoteViews binding and string-resource lookup live in the provider.
 *
 * The formatter returns semantic results ([WidgetDisplay]) rather than localized strings: the SOC
 * text is computed here (numeric, locale-agnostic), while the status line and freshness line are
 * expressed as enums the provider maps to `strings.xml`. This keeps localization in resources and
 * the branching logic here, where it can be tested cheaply.
 */
object WidgetStateFormatter {
    private const val MS_PER_MINUTE = 60L * 1000L

    /** Beyond this age the displayed data is considered stale and flagged as such. */
    const val STALE_AFTER_MS = 15L * MS_PER_MINUTE

    /** Below this age we show "just now" instead of a minute count. */
    const val JUST_NOW_UNDER_MS = MS_PER_MINUTE

    private const val MS_PER_HOUR = 60L * MS_PER_MINUTE
    private const val HOURS_PER_DAY = 24L

    /** The freshness phrasing for the "updated …" line. */
    enum class Freshness {
        NONE,
        JUST_NOW,
        MINUTES,
        HOURS,
        DAYS,
    }

    /** The status line shown under the SOC. Mapped to a string by the provider. */
    enum class Status {
        /** No data ever written — prompt the user to open the app. */
        NO_DATA,
        CHARGING,
        CONNECTED,
        DISCONNECTED,
    }

    /**
     * Resolved widget contents.
     *
     * @param socText the big SOC value, e.g. `"82%"` or `"—"` when unknown.
     * @param status which status line to render.
     * @param freshness which "updated" phrasing to render.
     * @param freshnessValue the numeric quantity for [Freshness.MINUTES]/[Freshness.HOURS]/
     *   [Freshness.DAYS] (otherwise 0).
     * @param stale true when the data is older than [STALE_AFTER_MS] (provider may dim it).
     */
    data class WidgetDisplay(
        val socText: String,
        val status: Status,
        val freshness: Freshness,
        val freshnessValue: Int,
        val stale: Boolean,
    )

    // The formatter is framework-free and cannot read R.string, so this literal must stay in sync
    // with R.string.widget_soc_placeholder (the provider's resource for the same unknown-SOC case).
    private const val SOC_UNKNOWN_TEXT = "—"

    /** Formats SOC as a percent, or an em-dash placeholder when unknown. */
    fun socText(snapshot: WidgetSnapshot): String = if (snapshot.hasSoc()) "${snapshot.socPct}%" else SOC_UNKNOWN_TEXT

    /** Decides the status line given the snapshot. */
    fun status(snapshot: WidgetSnapshot): Status =
        when {
            !snapshot.hasData() -> Status.NO_DATA
            snapshot.charging || snapshot.vehicleState == VEHICLE_STATE_CHARGING -> Status.CHARGING
            snapshot.connected -> Status.CONNECTED
            else -> Status.DISCONNECTED
        }

    /**
     * Decides the freshness phrasing and value. [nowMs] is injected so tests are deterministic.
     * A snapshot timestamp in the future (clock skew) is treated as "just now".
     *
     * Freshness is measured from the LAST SAMPLE arrival ([WidgetSnapshot.freshnessAtMs]), not the
     * last display-field change — so a steady charge/parked period with flat-but-fresh samples does
     * not falsely age out. (Report item B7.)
     */
    fun freshness(
        snapshot: WidgetSnapshot,
        nowMs: Long,
    ): Pair<Freshness, Int> {
        if (!snapshot.hasData()) {
            return Freshness.NONE to 0
        }
        val ageMs = (nowMs - snapshot.freshnessAtMs()).coerceAtLeast(0L)
        return when {
            ageMs < JUST_NOW_UNDER_MS -> Freshness.JUST_NOW to 0
            ageMs < MS_PER_HOUR -> Freshness.MINUTES to (ageMs / MS_PER_MINUTE).toInt()
            ageMs < HOURS_PER_DAY * MS_PER_HOUR -> Freshness.HOURS to (ageMs / MS_PER_HOUR).toInt()
            else -> Freshness.DAYS to (ageMs / (HOURS_PER_DAY * MS_PER_HOUR)).toInt()
        }
    }

    /**
     * True when [snapshot]'s last SAMPLE is older than [STALE_AFTER_MS] relative to [nowMs]. Keyed to
     * the sample arrival, not the last display change, so a flat-but-live charge is not called stale.
     */
    fun isStale(
        snapshot: WidgetSnapshot,
        nowMs: Long,
    ): Boolean = snapshot.hasData() && (nowMs - snapshot.freshnessAtMs()) >= STALE_AFTER_MS

    /** Resolves the full display in one call. */
    fun format(
        snapshot: WidgetSnapshot,
        nowMs: Long,
    ): WidgetDisplay {
        val (freshness, value) = freshness(snapshot, nowMs)
        return WidgetDisplay(
            socText = socText(snapshot),
            status = status(snapshot),
            freshness = freshness,
            freshnessValue = value,
            stale = isStale(snapshot, nowMs),
        )
    }

    /** Keeps the formatter independent of the classifier enum while matching its payload key. */
    internal const val VEHICLE_STATE_CHARGING = "charging"
}
