package com.volttracker.obdpoc;

import java.util.UUID;

/**
 * Static ELM327 connection parameters and the OBD probe command lists used by
 * {@link ObdPollingEngine}. Kept in one place — and package-private so the unit tests
 * can assert these lists stay well-formed.
 */
final class ObdProbes {

    private ObdProbes() {
    }

    static final UUID ELM327_SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    static final long CONNECT_TIMEOUT_MS = 15000L;
    // After this many consecutive failed reconnects the OBD loop gives up; GPS keeps logging.
    static final int MAX_RECONNECT_ATTEMPTS = 6;
    static final String[] PROTOCOL_PROBES = {"ATSP0", "ATSP6", "ATSP7", "ATSP8"};
    static final String[] CAPABILITY_PROBES = {"0100", "0120", "0140", "0160"};
    // 015B is the standard mode-01 hybrid-battery SOC PID; it answers on the normal bus
    // with no ATSH header, so it is probed alongside the other live-data PIDs.
    static final String[] LIVE_PROBES =
            {"ATRV", "010D", "010C", "0105", "0104", "0111", "015B", "0142", "011F", "012F", "015C"};
    // Community-validated Chevy Volt mode-22 PIDs (Volt PID community sheet, see
    // docs/volt-pids-community-sheet.csv). ATSH selects the controller before each group;
    // decode formulas live in ObdProtocol.
    static final String[] VOLT_7E1_PROBES = {
            "222429",  // HV pack voltage
            "222414"   // HV pack current (signed: discharge positive)
    };
    static final String[] VOLT_7E4_PROBES = {
            "22434F",  // HV battery temperature
            "224368",  // charger AC input voltage
            "224369",  // charger AC input current
            "22436B",  // charger HV output voltage
            "22436C",  // charger HV output current
            "224373",  // charger HV output power
            "22437D"   // last charge AC energy
    };
}
