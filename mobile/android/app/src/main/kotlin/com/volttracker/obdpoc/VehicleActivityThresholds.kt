package com.volttracker.obdpoc

/** Shared thresholds for deciding whether a telemetry sample proves the vehicle is active. */
object VehicleActivityThresholds {
    /** OBD speed above this is enough evidence that the vehicle is in a drive window. */
    const val MOVING_SPEED_KPH = 5.0

    /** RPM above this means the car is awake even if the GPS speed is momentarily zero. */
    const val ENGINE_READY_RPM = 300

    /** 12V bus above this suggests the DC-DC converter is active, not a sleeping adapter. */
    const val READY_VOLTAGE = 13.0

    /** Small pack-power noise is ignored; above this the car is doing real drivetrain work. */
    const val ACTIVE_POWER_KW = 1.0

    /** Pack-current fallback for rows where power_kw is unavailable. */
    const val ACTIVE_PACK_CURRENT_A = 3.0
}
