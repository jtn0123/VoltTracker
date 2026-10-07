package com.volttracker.obdpoc.engine

import com.volttracker.obdpoc.SwcanField

/** How a guided step runs and when it is over. */
enum class GuidedStepKind {
    /**
     * Speaks, listening meanwhile, and waits for "I can hear it" on the Car tab: the check that the
     * phone's voice reaches the driver and that the body bus is heard at all.
     */
    CONFIRM,

    /** Times bus switches ([SwitchBenchmark]); nothing for the driver to do. */
    BENCHMARK,

    /** Waits for [GuidedStep.expect], then a few seconds more for what follows it. */
    EVENT,

    /** Listens for a fixed time: nothing decodes it yet, so what changed is logged for later. */
    TIMED,

    /** Waits for the car to switch off, then records the shutdown for a fixed time. */
    POWER_OFF,

    /**
     * A normal drive, recorded on the body bus throughout (live HS data paused), until the car is
     * switched off; then the shutdown is recorded for a fixed time and the session ends.
     */
    DRIVE,
}

/** What a step needs before it may start. */
enum class GuidedPhase {
    /** In Park ([SwcanListenRunner.Io.isInPark]): the driver may get out. */
    PARKED,

    /** In Park, or switched off (the body bus last said off or accessory). */
    PARKED_OR_OFF,

    /** Nothing: the drive. */
    DRIVING,
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
        fun changes(field: SwcanField) = GuidedExpect(field) { before, now -> before != null && before != now }
    }
}

/** One thing the guided car test asks for. */
class GuidedStep(
    /** Logged with what the step heard, e.g. `driver_door_open`. */
    val id: String,
    val kind: GuidedStepKind,
    /** What the phone says once it is listening ("" for nothing). */
    val say: String,
    /**
     * How long the step listens once the phone has finished speaking: an EVENT's give-up, a TIMED
     * step's length, a CONFIRM's wait for the tap, a DRIVE's whole length.
     */
    val maxMs: Long,
    val expect: GuidedExpect? = null,
    val phase: GuidedPhase = GuidedPhase.PARKED,
    /** A BENCHMARK's cycle count. */
    val cycles: Int = 0,
)

/**
 * The guided car test, in order: a voice check, a timing check of the bus switch, then every body
 * action the Car tab reads or might (locks, a window, each door, hatch, hood, A/C, seat heat), the
 * car switching off, the charge port and fuel doors with the car off, the car switching on, and a
 * normal drive. Each instruction is spoken once the phone is listening for it, one action at a time,
 * and the driver waits for the next. Parked steps need the car in Park; the driver gets out for the
 * doors, hatch, hood, charge port and fuel door.
 */
object GuidedCarScript {
    /** Cycles per benchmark step: two steps per way of switching. */
    const val BENCH_CYCLES = 10
    const val BENCH_SEPARATE = "bench_separate"
    const val BENCH_BATCHED = "bench_batched"

    private const val ACT_MS = 30_000L
    private const val WALK_MS = 45_000L
    private const val SEAT_MS = 12_000L
    private const val CONFIRM_MS = 90_000L

    private fun event(
        id: String,
        say: String,
        expect: GuidedExpect,
        maxMs: Long = ACT_MS,
        phase: GuidedPhase = GuidedPhase.PARKED,
    ) = GuidedStep(id, GuidedStepKind.EVENT, say, maxMs, expect, phase)

    private fun timed(
        id: String,
        say: String,
        maxMs: Long,
        phase: GuidedPhase = GuidedPhase.PARKED,
    ) = GuidedStep(id, GuidedStepKind.TIMED, say, maxMs, phase = phase)

    private fun door(
        id: String,
        name: String,
        field: SwcanField,
        maxMs: Long,
    ) = listOf(
        event("${id}_open", "Open the $name.", GuidedExpect.isOneOf(field, "open"), maxMs),
        event("${id}_close", "Close it.", GuidedExpect.isOneOf(field, "closed"), maxMs),
    )

    /** Four presses, each its own step: the lamps are read, not assumed, so the levels aren't named. */
    private fun frontSeat(
        id: String,
        name: String,
        field: SwcanField,
    ) = listOf(
        event("${id}_press_1", "Press the $name seat heater button once.", GuidedExpect.changes(field), SEAT_MS),
    ) +
        (2..4).map { event("${id}_press_$it", "Press it once more.", GuidedExpect.changes(field), SEAT_MS) }

    private fun rearSeat(
        id: String,
        name: String,
        field: SwcanField,
    ) = listOf(
        event(
            "${id}_press",
            "If you have heated rear seats, press the $name one's button once. If not, just wait.",
            GuidedExpect.changes(field),
            SEAT_MS,
        ),
        timed("${id}_off", "Now press it until it's off. If you don't have them, just wait.", SEAT_MS),
    )

    private fun bench(
        id: String,
        say: String = "",
    ) = GuidedStep(id, GuidedStepKind.BENCHMARK, say, 0L, cycles = BENCH_CYCLES)

    private val POWER_OFF_MODES = arrayOf("off", "accessory")

    val steps: List<GuidedStep> =
        listOf(
            GuidedStep(
                "intro",
                GuidedStepKind.CONFIRM,
                "Guided car test. Turn the phone's volume up, so you can hear me outside the car. Park " +
                    "outdoors with the parking brake on, and keep the key with you. The car should be on and in " +
                    "Park, somewhere you can open every door, the hatch and the hood. I'll " +
                    "say each step once I'm listening for it. Do one thing at a time, and wait for me before " +
                    "the next. If you can hear me, tap I can hear it, on the Car tab.",
                CONFIRM_MS,
            ),
            bench(BENCH_SEPARATE, "First, about three minutes of adapter timing. Nothing to do, just stay in Park."),
            bench("${BENCH_SEPARATE}_2"),
            bench(BENCH_BATCHED),
            bench("${BENCH_BATCHED}_2"),
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
                event(
                    "ac_press",
                    "Back in your seat, press the A C button.",
                    GuidedExpect.changes(SwcanField.AC_STATE),
                    WALK_MS,
                ),
                event("ac_press_again", "Press it again.", GuidedExpect.changes(SwcanField.AC_STATE)),
            ) +
            frontSeat("seat_driver", "driver's", SwcanField.SEAT_HEAT_FL) +
            frontSeat("seat_passenger", "passenger", SwcanField.SEAT_HEAT_FR) +
            rearSeat("seat_rear_left", "left", SwcanField.SEAT_HEAT_RL) +
            rearSeat("seat_rear_right", "right", SwcanField.SEAT_HEAT_RR) +
            listOf(
                GuidedStep(
                    "power_off",
                    GuidedStepKind.POWER_OFF,
                    "Close all the doors and turn the car off. Stay in your seat until I speak again.",
                    60_000L,
                    GuidedExpect.isOneOf(SwcanField.POWER_MODE, *POWER_OFF_MODES),
                ),
                timed(
                    "charge_port_open",
                    "Get out and open the charge port door.",
                    30_000L,
                    GuidedPhase.PARKED_OR_OFF,
                ),
                timed("charge_port_close", "Close it.", 20_000L, GuidedPhase.PARKED_OR_OFF),
                timed(
                    "fuel_door_open",
                    "Back in the car, press the fuel door button. It can take a few seconds to open.",
                    45_000L,
                    GuidedPhase.PARKED_OR_OFF,
                ),
                timed(
                    "fuel_door_close",
                    "Go and close the fuel door, then get back in.",
                    45_000L,
                    GuidedPhase.PARKED_OR_OFF,
                ),
                event(
                    "power_on",
                    "Close your door and turn the car back on.",
                    GuidedExpect.isOneOf(SwcanField.POWER_MODE, "run"),
                    120_000L,
                    GuidedPhase.PARKED_OR_OFF,
                ),
                GuidedStep(
                    "drive",
                    GuidedStepKind.DRIVE,
                    "Last part: drive normally for about twenty minutes, and leave the phone alone while you " +
                        "drive. Live data pauses while I record. " +
                        "When you're done, park and turn the car off, and the test finishes by itself.",
                    35 * 60_000L,
                    GuidedExpect.isOneOf(SwcanField.POWER_MODE, *POWER_OFF_MODES),
                    GuidedPhase.DRIVING,
                ),
            )
}
