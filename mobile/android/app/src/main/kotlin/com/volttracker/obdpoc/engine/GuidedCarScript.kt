package com.volttracker.obdpoc.engine

import com.volttracker.obdpoc.SwcanField

/** How a guided step runs and when it is over. */
enum class GuidedStepKind {
    /** Speaks, listening meanwhile, and moves on once the phone has finished talking. */
    TALK,

    /** Times bus switches ([SwitchBenchmark]); nothing for the driver to do. */
    BENCHMARK,

    /** Waits for [GuidedStep.expect], then a few seconds more for what follows it. */
    EVENT,

    /** Listens for a fixed time: nothing decodes it yet, so what changed is logged for later. */
    TIMED,

    /** Waits for the car to switch off, then keeps listening to the shutdown burst. */
    POWER_OFF,

    /** A normal drive, in one-minute listens with an HS poll cycle between, until the car switches off. */
    DRIVE,
}

/** What an EVENT step listens for: a reading of [field] that [matches], given what it was before. */
class GuidedExpect(
    val field: SwcanField,
    private val test: (before: Any?, now: Any) -> Boolean,
) {
    fun matches(
        before: Any?,
        now: Any,
    ): Boolean = test(before, now)

    companion object {
        fun isOneOf(
            field: SwcanField,
            vararg values: String,
        ) = GuidedExpect(field) { _, now -> now in values }

        fun atLeast(
            field: SwcanField,
            min: Double,
        ) = GuidedExpect(field) { _, now -> now is Double && now >= min }

        fun atMost(
            field: SwcanField,
            max: Double,
        ) = GuidedExpect(field) { _, now -> now is Double && now <= max }

        /** Any new value: for a switch whose starting state isn't known. */
        fun changes(field: SwcanField) = GuidedExpect(field) { before, now -> before != now }
    }
}

/** One thing the guided car test asks for. */
class GuidedStep(
    /** Logged with what the step heard, e.g. `driver_door_open`. */
    val id: String,
    val kind: GuidedStepKind,
    /** What the phone says to start the step ("" for nothing). */
    val say: String,
    /**
     * How long the step listens once the phone has finished speaking: an EVENT's give-up, a TIMED
     * step's length, a DRIVE's whole length. A BENCHMARK's cycle count instead.
     */
    val maxMs: Long,
    val expect: GuidedExpect? = null,
)

/**
 * The guided car test, in order: a timing check of the bus switch, then every body action the
 * Car tab reads or might (locks, a window, each door, hatch, hood, charge port and fuel doors,
 * A/C, seat heat), the car switching off and on, and a normal drive. Parked steps need the car on
 * and in Park; the driver gets out for the doors, hatch, hood and charge port.
 */
object GuidedCarScript {
    /** Cycles per benchmark step: two steps per way of switching. */
    const val BENCH_CYCLES = 10L
    const val BENCH_SEPARATE = "bench_separate"
    const val BENCH_BATCHED = "bench_batched"

    private const val ACT_MS = 30_000L
    private const val WALK_MS = 45_000L
    private const val HEAT_MS = 8_000L

    private fun event(
        id: String,
        say: String,
        expect: GuidedExpect,
        maxMs: Long = ACT_MS,
    ) = GuidedStep(id, GuidedStepKind.EVENT, say, maxMs, expect)

    private fun timed(
        id: String,
        say: String,
        maxMs: Long = HEAT_MS,
    ) = GuidedStep(id, GuidedStepKind.TIMED, say, maxMs)

    private fun door(
        id: String,
        name: String,
        field: SwcanField,
        maxMs: Long,
    ) = listOf(
        event("${id}_open", "Open the $name.", GuidedExpect.isOneOf(field, "open"), maxMs),
        event("${id}_close", "Close it.", GuidedExpect.isOneOf(field, "closed"), maxMs),
    )

    private fun seatHeat(
        id: String,
        name: String,
    ) = listOf(
        timed("${id}_3", "Press the $name seat heater button once."),
        timed("${id}_2", "Press it again."),
        timed("${id}_1", "And again."),
        timed("${id}_off", "Once more, to turn it off."),
    )

    private fun rearSeat(
        id: String,
        name: String,
    ) = listOf(
        timed("${id}_on", "If you have heated rear seats, turn on the $name one. If not, just wait.", 10_000L),
        timed("${id}_off", "Turn it off.", 10_000L),
    )

    private val POWER_OFF_MODES = arrayOf("off", "accessory")

    val steps: List<GuidedStep> =
        listOf(
            GuidedStep(
                "intro",
                GuidedStepKind.TALK,
                "Guided car test. The car should be on and in Park, somewhere you can open every " +
                    "door, the hatch and the hood. I'll say what to do, one step at a time. " +
                    "Skip or stop it from the Car tab.",
                0L,
            ),
            GuidedStep(
                BENCH_SEPARATE,
                GuidedStepKind.BENCHMARK,
                "First, about three minutes of adapter timing. Nothing to do, just stay in Park.",
                BENCH_CYCLES,
            ),
            GuidedStep("${BENCH_SEPARATE}_2", GuidedStepKind.BENCHMARK, "", BENCH_CYCLES),
            GuidedStep(BENCH_BATCHED, GuidedStepKind.BENCHMARK, "", BENCH_CYCLES),
            GuidedStep("${BENCH_BATCHED}_2", GuidedStepKind.BENCHMARK, "", BENCH_CYCLES),
            event(
                "lock",
                "Lock the doors with the switch on your door.",
                GuidedExpect.isOneOf(SwcanField.LOCK_STATE, "locked"),
            ),
            event("unlock", "Now unlock them.", GuidedExpect.isOneOf(SwcanField.LOCK_STATE, "unlocked")),
            event(
                "driver_window_down",
                "Put your window all the way down.",
                GuidedExpect.atLeast(SwcanField.WINDOW_FL, 60.0),
            ),
            event("driver_window_up", "Now close it.", GuidedExpect.atMost(SwcanField.WINDOW_FL, 1.0)),
        ) +
            door("driver_door", "driver's door", SwcanField.DOOR_FL, ACT_MS) +
            door("passenger_door", "front passenger door", SwcanField.DOOR_FR, WALK_MS) +
            door("rear_left_door", "rear left door", SwcanField.DOOR_RL, WALK_MS) +
            door("rear_right_door", "rear right door", SwcanField.DOOR_RR, WALK_MS) +
            listOf(
                event("hatch_open", "Open the hatch.", GuidedExpect.isOneOf(SwcanField.TRUNK, "open"), WALK_MS),
                event("hatch_close", "Close the hatch.", GuidedExpect.isOneOf(SwcanField.TRUNK, "closed"), WALK_MS),
                event(
                    "hood_open",
                    "Pull the hood release inside, then lift the hood.",
                    GuidedExpect.isOneOf(SwcanField.HOOD, "open"),
                    60_000L,
                ),
                event("hood_close", "Close the hood.", GuidedExpect.isOneOf(SwcanField.HOOD, "closed"), WALK_MS),
                timed("charge_port", "Open the charge port door, then close it again.", 25_000L),
                timed(
                    "fuel_door",
                    "Back in the car, press the fuel door button. When the fuel door opens, go and close it.",
                    60_000L,
                ),
                event(
                    "ac_press",
                    "Back in your seat, press the A C button.",
                    GuidedExpect.changes(SwcanField.AC_STATE),
                    WALK_MS,
                ),
                event("ac_press_again", "Press it again.", GuidedExpect.changes(SwcanField.AC_STATE)),
            ) +
            seatHeat("seat_driver", "driver's") +
            seatHeat("seat_passenger", "passenger") +
            rearSeat("seat_rear_left", "left") +
            rearSeat("seat_rear_right", "right") +
            listOf(
                GuidedStep(
                    "power_off",
                    GuidedStepKind.POWER_OFF,
                    "Close all the doors and turn the car off. Stay in your seat.",
                    60_000L,
                    GuidedExpect.isOneOf(SwcanField.POWER_MODE, *POWER_OFF_MODES),
                ),
                event(
                    "power_on",
                    "Now turn the car back on.",
                    GuidedExpect.isOneOf(SwcanField.POWER_MODE, "run"),
                    120_000L,
                ),
                GuidedStep(
                    "drive",
                    GuidedStepKind.DRIVE,
                    "Last part: drive normally for about twenty minutes. When you're done, park and turn " +
                        "the car off, and the test finishes by itself.",
                    35 * 60_000L,
                    GuidedExpect.isOneOf(SwcanField.POWER_MODE, *POWER_OFF_MODES),
                ),
            )
}
