package com.volttracker.obdpoc;

/** Shared thresholds for deciding whether a telemetry sample proves the vehicle is active. */
public final class VehicleActivityThresholds {

    /** OBD speed above this is enough evidence that the vehicle is in a drive window. */
    public static final double MOVING_SPEED_KPH = 5.0;

    /** RPM above this means the car is awake even if the GPS speed is momentarily zero. */
    public static final int ENGINE_READY_RPM = 300;

    /** 12V bus above this suggests the DC-DC converter is active, not a sleeping adapter. */
    public static final double READY_VOLTAGE = 13.0;

    /** Small pack-power noise is ignored; above this the car is doing real drivetrain work. */
    public static final double ACTIVE_POWER_KW = 1.0;

    /** Pack-current fallback for rows where power_kw is unavailable. */
    public static final double ACTIVE_PACK_CURRENT_A = 3.0;

    private VehicleActivityThresholds() {}
}
