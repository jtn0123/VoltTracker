package com.volttracker.obdpoc;

import java.util.UUID;

/**
 * Static ELM327 connection parameters and the OBD probe command lists used by {@link
 * ObdPollingEngine}. Kept in one place — and package-private so the unit tests can assert these
 * lists stay well-formed.
 */
final class ObdProbes {

    private ObdProbes() {}

    static final UUID ELM327_SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    static final long CONNECT_TIMEOUT_MS = 15000L;
    // After this many consecutive failed reconnects the OBD loop gives up; GPS keeps logging.
    static final int MAX_RECONNECT_ATTEMPTS = 6;
    static final String[] PROTOCOL_PROBES = {"ATSP0", "ATSP6", "ATSP7", "ATSP8"};
    static final String[] CAPABILITY_PROBES = {"0100", "0120", "0140", "0160", "0180", "01A0"};
    // 015B is the standard mode-01 hybrid-battery SOC PID; it answers on the normal bus
    // with no ATSH header, so it is probed alongside the other live-data PIDs.
    static final String[] LIVE_PROBES = {
        "ATRV", "010D", "010C", "0105", "0104", "0111", "0149", "015B", "0142", "011F", "012F",
        "010F", "015C", "01A6"
    };
    // Community-validated Chevy Volt mode-22 PIDs (Volt PID community sheet, see
    // docs/volt-pids-community-sheet.csv). ATSH selects the controller before each group;
    // decode formulas live in ObdProtocol.
    static final String[] VOLT_7E1_PROBES = {
        "222429", // HV pack voltage
        "222414", // HV pack current (signed: discharge positive)
        "222883", // motor A current
        "222884", // motor B current
        "222885", // motor A voltage
        "222886", // motor B voltage
        "222487", // EV distance this cycle
        "222889" // PRNDL / gear selector state
    };
    static final String[] VOLT_7E0_PROBES = {
        "22119F", // engine oil life remaining
        "22119F01", // engine oil life remaining, GM sub-parameter variant
        "221154", // engine oil temperature
        "22203F" // engine torque
    };
    static final String[] VOLT_7E2_PROBES = {
        "221940", // transmission temperature
        "22194001" // transmission temperature, GM sub-parameter variant
    };
    static final String[] VOLT_7E4_PROBES = {
        "22434F", // HV battery temperature
        "224368", // charger AC input voltage
        "224369", // charger AC input current
        "22436B", // charger HV output voltage
        "22436C", // charger HV output current
        "224373", // charging mode
        "22437D", // last charge AC energy
        "2243A5", // HV battery charge count
        "2243AF", // raw high-resolution HV battery SOC
        "224531", // charging level
        "228334", // displayed HV battery SOC
        "2241B2", // battery coolant pump RPM
        "2241B4", // battery coolant valve raw position/state
        "2241B6", // battery coolant heater power
        "22801E", // outside air temperature raw
        "22801F" // outside air temperature filtered
    };
    // Diagnostic-only TPMS discovery candidates. OBDb marks the 7E0 candidates unsupported for the
    // 2018 Volt test data, and public references do not give a reliable universal Volt TPMS PID.
    // Keep these out of live polling until a real scan stores positive responses.
    static final String[] TPMS_7E0_DISCOVERY_PROBES = {
        "22248E", // candidate tire pressure, front-left
        "22248F", // candidate tire pressure, front-right
        "222490", // candidate tire pressure, rear-right
        "222491", // candidate tire pressure, rear-left
        "22C901", // candidate grouped tire pressures
        "22C902" // candidate grouped tire temperatures
    };
    static final String[] TPMS_760_DISCOVERY_PROBES = {
        "224051", // candidate TPMS slot 1
        "224052", // candidate TPMS slot 2
        "224053", // candidate TPMS slot 3
        "224054" // candidate TPMS slot 4
    };
}
