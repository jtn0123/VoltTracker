package com.volttracker.obdpoc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Open enhanced/candidate PID catalog for the Volt profile. */
public final class EnhancedPidProfiles {
    public static final String STATUS_CANDIDATE = "candidate";
    public static final String STATUS_CONFIRMED = "confirmed";
    public static final String STATUS_REJECTED = "rejected_on_this_vehicle";
    public static final String STATUS_DIAGNOSTIC_ONLY = "diagnostic_only";
    public static final String STAGE_PASSIVE = "passive";
    public static final String STAGE_LOW_RISK = "low-risk";
    public static final String STAGE_TIRES = "tires";
    public static final String STAGE_EXPERIMENTAL = "experimental";

    private static final String HS_CAN = "hs-can";
    private static final String GMLAN = "gmlan";
    private static final String BROADCAST = "";
    private static final long RETRY_ONCE_PER_DRIVE_MS = 8L * 60L * 60L * 1000L;
    private static final long RETRY_CANDIDATE_MS = 2L * 60L * 60L * 1000L;
    private static final long RETRY_REJECTED_MS = 7L * 24L * 60L * 60L * 1000L;

    public static final List<EnhancedPidProfile> ALL;

    static {
        List<EnhancedPidProfile> profiles = new ArrayList<>();
        add(
                profiles,
                "odometer.standard.01a6",
                "odometer",
                HS_CAN,
                BROADCAST,
                "01A6",
                "odometer",
                "km",
                "once_per_drive",
                STAGE_PASSIVE,
                "safe",
                RETRY_ONCE_PER_DRIVE_MS,
                STATUS_CANDIDATE,
                "SAE J1979 optional PID",
                "This car returned NO DATA; keep as candidate only.");
        add(
                profiles,
                "odometer.passive.120",
                "odometer",
                HS_CAN,
                "CAN:120",
                "CAN:120",
                "odometer",
                "km",
                "passive",
                STAGE_PASSIVE,
                "safe",
                RETRY_ONCE_PER_DRIVE_MS,
                STATUS_CANDIDATE,
                "GM Volt reverse engineering wiki",
                "Broadcast frame 0x120 is the next safer odometer target; not an OBD PID.");
        add(
                profiles,
                "maintenance.oil_life.22119f",
                "maintenance",
                HS_CAN,
                "ATSH7E0",
                "22119F",
                "engine oil life",
                "%",
                "diagnostic_only",
                STAGE_LOW_RISK,
                "low",
                RETRY_REJECTED_MS,
                STATUS_CANDIDATE,
                "ScanGauge GM CANSF oil life",
                "Real car returned a negative response.");
        add(
                profiles,
                "maintenance.oil_life.22119f01",
                "maintenance",
                HS_CAN,
                "ATSH7E0",
                "22119F01",
                "engine oil life",
                "%",
                "diagnostic_only",
                STAGE_LOW_RISK,
                "low",
                RETRY_REJECTED_MS,
                STATUS_CANDIDATE,
                "ScanGauge GM CANSF oil life selector variant",
                "Real car returned a negative response.");
        add(
                profiles,
                "maintenance.oil_temp.221154",
                "maintenance",
                HS_CAN,
                "ATSH7E0",
                "221154",
                "engine oil temperature",
                "C",
                "thermal",
                STAGE_LOW_RISK,
                "low",
                RETRY_CANDIDATE_MS,
                STATUS_CONFIRMED,
                "Volt community PID sheet",
                "Confirmed on the real car.");
        add(
                profiles,
                "transmission.temp.221940",
                "transmission",
                HS_CAN,
                "ATSH7E2",
                "221940",
                "transmission temperature",
                "C",
                "diagnostic_only",
                STAGE_LOW_RISK,
                "low",
                RETRY_REJECTED_MS,
                STATUS_CANDIDATE,
                "ScanGauge and OBDLink GM trans temp references",
                "Real car returned NO DATA.");
        add(
                profiles,
                "transmission.temp.22194001",
                "transmission",
                HS_CAN,
                "ATSH7E2",
                "22194001",
                "transmission temperature",
                "C",
                "diagnostic_only",
                STAGE_LOW_RISK,
                "low",
                RETRY_REJECTED_MS,
                STATUS_CANDIDATE,
                "OBDLink GM trans temp selector variant",
                "Real car returned NO DATA.");
        add(
                profiles,
                "ambient.outside_temp.0146",
                "ambient",
                HS_CAN,
                BROADCAST,
                "0146",
                "outside air temperature",
                "C",
                "slow",
                STAGE_LOW_RISK,
                "low",
                RETRY_CANDIDATE_MS,
                STATUS_CANDIDATE,
                "SAE J1979 PID 46",
                "Standard optional PID; promote only after this car returns 41 46.");
        add(
                profiles,
                "engine.intake_air_temp.010f",
                "engine",
                HS_CAN,
                BROADCAST,
                "010F",
                "intake air temperature",
                "C",
                "slow",
                STAGE_LOW_RISK,
                "low",
                RETRY_CANDIDATE_MS,
                STATUS_CANDIDATE,
                "SAE J1979 PID 0F",
                "Useful sanity signal for air/thermal context when supported.");
        add(
                profiles,
                "engine.absolute_load.0143",
                "engine",
                HS_CAN,
                BROADCAST,
                "0143",
                "absolute engine load",
                "%",
                "warm",
                STAGE_LOW_RISK,
                "low",
                RETRY_CANDIDATE_MS,
                STATUS_CANDIDATE,
                "SAE J1979 PID 43",
                "Closest safe standard proxy while true torque remains enhanced/candidate.");
        add(
                profiles,
                "ev.distance.remaining.224373",
                "ev",
                HS_CAN,
                "ATSH7E4",
                "224373",
                "EV range / charging mode candidate",
                "",
                "diagnostic_only",
                STAGE_EXPERIMENTAL,
                "medium",
                RETRY_REJECTED_MS,
                STATUS_CANDIDATE,
                "Prior VoltTracker field scan",
                "Treat carefully: prior evidence showed charging mode, not charger HV output power.");
        add(
                profiles,
                "thermal.battery_coolant_pump_rpm",
                "thermal",
                HS_CAN,
                "ATSH7E4",
                "22435A",
                "battery coolant pump RPM candidate",
                "rpm",
                "diagnostic_only",
                STAGE_EXPERIMENTAL,
                "medium",
                RETRY_REJECTED_MS,
                STATUS_CANDIDATE,
                "Volt community enhanced candidate",
                "Do not poll live until a positive 62 frame and decode are verified.");
        add(
                profiles,
                "thermal.battery_coolant_valve_state",
                "thermal",
                HS_CAN,
                "ATSH7E4",
                "22435B",
                "battery coolant valve state candidate",
                "",
                "diagnostic_only",
                STAGE_EXPERIMENTAL,
                "medium",
                RETRY_REJECTED_MS,
                STATUS_CANDIDATE,
                "Volt community enhanced candidate",
                "Good value if found; keep as candidate-only until decoded.");
        add(
                profiles,
                "battery.cell_group_voltage.22434f",
                "battery",
                HS_CAN,
                "ATSH7E4",
                "22434F",
                "cell group voltage candidate",
                "V",
                "diagnostic_only",
                STAGE_EXPERIMENTAL,
                "medium",
                RETRY_REJECTED_MS,
                STATUS_CANDIDATE,
                "Volt community enhanced candidate",
                "High value for pack health, but only safe as a narrow candidate probe.");
        addTpms(profiles, "22248E", "front-left");
        addTpms(profiles, "22248F", "front-right");
        addTpms(profiles, "222490", "rear-right");
        addTpms(profiles, "222491", "rear-left");
        addTpms(profiles, "22C901", "grouped pressures");
        addTpms(profiles, "22C902", "grouped temperatures");
        addTpmsReceiver(profiles, "224051", "slot 1");
        addTpmsReceiver(profiles, "224052", "slot 2");
        addTpmsReceiver(profiles, "224053", "slot 3");
        addTpmsReceiver(profiles, "224054", "slot 4");
        ALL = Collections.unmodifiableList(profiles);
    }

    private EnhancedPidProfiles() {}

    public static EnhancedPidProfile find(String header, String command) {
        String cleanHeader = clean(header);
        String cleanCommand = clean(command);
        for (EnhancedPidProfile profile : ALL) {
            if (profile.command.equals(cleanCommand) && profile.header.equals(cleanHeader)) {
                return profile;
            }
        }
        for (EnhancedPidProfile profile : ALL) {
            if (profile.command.equals(cleanCommand) && profile.header.isEmpty()) {
                return profile;
            }
        }
        return null;
    }

    public static List<EnhancedPidProfile> forCategory(String category) {
        String cleanCategory = category == null ? "" : category.trim().toLowerCase(Locale.US);
        List<EnhancedPidProfile> matches = new ArrayList<>();
        for (EnhancedPidProfile profile : ALL) {
            if (profile.category.equals(cleanCategory)) {
                matches.add(profile);
            }
        }
        return Collections.unmodifiableList(matches);
    }

    public static List<EnhancedPidProfile> passiveProfiles() {
        List<EnhancedPidProfile> matches = new ArrayList<>();
        for (EnhancedPidProfile profile : ALL) {
            if ("passive".equals(profile.pollLane)) {
                matches.add(profile);
            }
        }
        return Collections.unmodifiableList(matches);
    }

    public static List<EnhancedPidProfile> forStage(String stage) {
        String normalized = normalizeStage(stage);
        List<EnhancedPidProfile> matches = new ArrayList<>();
        for (EnhancedPidProfile profile : ALL) {
            if (profile.scanStage.equals(normalized)) {
                matches.add(profile);
            }
        }
        return Collections.unmodifiableList(matches);
    }

    public static String normalizeStage(String stage) {
        String cleanStage = stage == null ? "" : stage.trim().toLowerCase(Locale.US);
        if (STAGE_PASSIVE.equals(cleanStage)
                || STAGE_LOW_RISK.equals(cleanStage)
                || STAGE_TIRES.equals(cleanStage)
                || STAGE_EXPERIMENTAL.equals(cleanStage)) {
            return cleanStage;
        }
        return STAGE_TIRES;
    }

    public static JSONArray catalogJson() {
        JSONArray items = new JSONArray();
        for (EnhancedPidProfile profile : ALL) {
            JSONObject item = new JSONObject();
            try {
                item.put("key", profile.key);
                item.put("category", profile.category);
                item.put("network", profile.network);
                item.put("protocol", profile.protocol);
                item.put("header", profile.header);
                item.put("command", profile.command);
                item.put("pid", profile.pid);
                item.put("name", profile.name);
                item.put("unit", profile.unit);
                item.put("pollLane", profile.pollLane);
                item.put("scanStage", profile.scanStage);
                item.put("risk", profile.risk);
                item.put("retryAfterMs", profile.retryAfterMs);
                item.put("validationStatus", profile.validationStatus);
                item.put("source", profile.source);
                item.put("notes", profile.notes);
                items.put(item);
            } catch (JSONException ignored) {
                // Static catalog strings are safe.
            }
        }
        return items;
    }

    public static boolean isPositiveResponse(String command, String rawResponse) {
        String cleanCommand = clean(command);
        String raw = clean(rawResponse);
        if (cleanCommand.isEmpty() || raw.isEmpty()) {
            return false;
        }
        if (raw.contains("NO DATA")
                || raw.contains("CAN ERROR")
                || raw.contains("UNABLE TO CONNECT")
                || raw.contains("STOPPED")
                || raw.contains("ERROR")
                || raw.contains("7F")) {
            return false;
        }
        String compact = raw.replace(" ", "");
        if (cleanCommand.startsWith("01") && cleanCommand.length() >= 4) {
            return compact.contains("41" + cleanCommand.substring(2, 4));
        }
        if (cleanCommand.startsWith("22") && cleanCommand.length() >= 6) {
            String pid = cleanCommand.substring(2, Math.min(cleanCommand.length(), 6));
            return compact.contains("62" + pid);
        }
        if (cleanCommand.startsWith("09") && cleanCommand.length() >= 4) {
            return compact.contains("49" + cleanCommand.substring(2, 4));
        }
        return false;
    }

    private static void addTpms(List<EnhancedPidProfile> profiles, String command, String wheel) {
        add(
                profiles,
                "tpms.7e0." + command.toLowerCase(Locale.US),
                "tpms",
                HS_CAN,
                "ATSH7E0",
                command,
                "candidate tire pressure " + wheel,
                "kPa",
                "diagnostic_only",
                STAGE_TIRES,
                "medium",
                RETRY_REJECTED_MS,
                STATUS_DIAGNOSTIC_ONLY,
                "community TPMS candidate",
                "Do not promote until a real car returns a positive 62 frame.");
    }

    private static void addTpmsReceiver(
            List<EnhancedPidProfile> profiles, String command, String slot) {
        add(
                profiles,
                "tpms.760." + command.toLowerCase(Locale.US),
                "tpms",
                GMLAN,
                "ATSH760",
                command,
                "candidate tire receiver " + slot,
                "",
                "diagnostic_only",
                STAGE_TIRES,
                "medium",
                RETRY_REJECTED_MS,
                STATUS_DIAGNOSTIC_ONLY,
                "GM TPMS receiver candidate",
                "Do not promote until a real car returns a positive 62 frame.");
    }

    private static void add(
            List<EnhancedPidProfile> profiles,
            String key,
            String category,
            String network,
            String header,
            String command,
            String name,
            String unit,
            String pollLane,
            String scanStage,
            String risk,
            long retryAfterMs,
            String validationStatus,
            String source,
            String notes) {
        profiles.add(
                new EnhancedPidProfile(
                        key,
                        category,
                        network,
                        "elm327",
                        header,
                        command,
                        ObdElmDecode.pidForCommand(command),
                        name,
                        unit,
                        pollLane,
                        scanStage,
                        risk,
                        retryAfterMs,
                        validationStatus,
                        source,
                        notes));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.US);
    }
}
