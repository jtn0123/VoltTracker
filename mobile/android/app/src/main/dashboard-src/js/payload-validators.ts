// payload-validators.ts — warn-only runtime shape checks for the critical
// native -> dashboard payloads (setStatus / setStorage / setAppState; shapes
// documented in docs/bridge-abi.md). The native side and the dashboard drift
// independently, and a renamed/mistyped field otherwise fails silently as a
// "--" tile. Each setter's parse path calls VD.validatePayload(kind, parsed)
// after VD.parsePayload.
//
// Contract: NEVER throws, never blocks rendering — a finding is a single
// console.warn, deduped per distinct (kind, field, issue) so a 1Hz status
// stream can't spam logcat. Fields are only type-checked when PRESENT:
// JS-side callers legitimately send partial payloads (e.g. a local
// setStatus({state, detail})), so absence is not a finding — except for
// setStatus.state, which every producer (native and local) always sends.
const VD = window.VoltDashboard;

// expected `typeof`; "object" additionally rejects arrays.
type FieldTypes = Record<string, string>;
type PayloadSpec = { required: string[]; fields: FieldTypes };

const PAYLOAD_SPECS: Record<string, PayloadSpec> = {
  setStatus: {
    required: ["state"],
    fields: {
      state: "string",
      detail: "string",
      blocked: "boolean",
      bluetoothReady: "boolean",
      lastAddress: "string",
      lastName: "string"
    }
  },
  setStorage: {
    required: [],
    fields: {
      sessionCount: "number",
      sampleCount: "number",
      eventCount: "number",
      pidObservationCount: "number",
      diagnosticCodeCount: "number",
      locationSampleCount: "number",
      tripSegmentCount: "number",
      chargeSessionCount: "number",
      batterySnapshotCount: "number",
      cellSnapshotCount: "number"
    }
  },
  setAppState: {
    required: [],
    fields: {
      app: "object",
      permissions: "object",
      adapter: "object",
      session: "object",
      vehicle: "object",
      gps: "object",
      lifecycle: "object",
      latestTelemetry: "object",
      storage: "object"
    }
  }
};

const warnedPayloadIssues = new Set<string>();

function warnPayloadIssueOnce(key: string, message: string) {
  if (warnedPayloadIssues.has(key)) return;
  warnedPayloadIssues.add(key);
  try {
    if (typeof console !== "undefined" && console && console.warn) {
      console.warn(message);
    }
  } catch (ignored) {}
  // Also land in the rolling app log via the native bridge (token-bucketed on the
  // Kotlin side), so field reports carry shape drift without an adb session.
  try {
    const bridge = window.VoltTrackerAndroid;
    if (bridge && typeof bridge.logClientError === "function") {
      bridge.logClientError("payload.shape", message);
    }
  } catch (ignored) {}
}

function matchesExpectedType(value: unknown, expected: string) {
  if (expected === "object") {
    return typeof value === "object" && !Array.isArray(value);
  }
  return typeof value === expected;
}

function validatePayload(kind: string, payload: unknown) {
  try {
    const spec = PAYLOAD_SPECS[kind];
    if (!spec) return;
    if (payload == null || typeof payload !== "object" || Array.isArray(payload)) {
      warnPayloadIssueOnce(
        kind + ":not-object",
        "payload check: " + kind + " payload is not an object (got " +
          (Array.isArray(payload) ? "array" : typeof payload) + ")"
      );
      return;
    }
    const record = payload as Record<string, unknown>;
    // Native error envelopes ({ok:false, error}) flow through the same
    // setters by design — they are not a shape violation.
    if (record.ok === false) return;
    spec.required.forEach((field) => {
      if (record[field] == null) {
        warnPayloadIssueOnce(
          kind + "." + field + ":missing",
          "payload check: " + kind + "." + field + " is missing"
        );
      }
    });
    Object.keys(spec.fields).forEach((field) => {
      const value = record[field];
      if (value == null) return;
      const expected = spec.fields[field];
      if (!matchesExpectedType(value, expected)) {
        warnPayloadIssueOnce(
          kind + "." + field + ":" + typeof value,
          "payload check: " + kind + "." + field + " expected " + expected +
            ", got " + (Array.isArray(value) ? "array" : typeof value)
        );
      }
    });
  } catch (ignored) {
    // Warn-only by contract: validation must never break a render pass.
  }
}

VD.validatePayload = validatePayload;

export {};
