// @ts-check
// dtc-causes.js - Generic OBD-II DTC causes database
//
// Companion to dtc-lookup.js.  Covers universally-common codes applicable to
// any vehicle (including the Volt's 1.4L range extender) but intentionally
// excludes Volt-specific or hybrid-only codes (those belong to the Volt causes
// agent).
//
// Sources used (causes must appear in >= 2 independent sources):
//   SAE J2012, obd-codes.com, repairpal.com, yourmechanic.com,
//   engine-codes.com, autocodes.com, Bosch OBD-II handbook.
//
// Severity:
//   "info"     - cosmetic / monitoring only
//   "warning"  - diagnose soon; driveability or emissions impact
//   "critical" - safety or immediate driveability impact
//
// Category tags (one per entry):
//   "1.4L engine"  - ICE sensors, fuel, ignition, intake, exhaust, catalyst, EVAP, cooling
//   "Drive unit"   - transmission solenoids, shift, clutch, TCC, fluid pressure
//   "12V power"    - system voltage, sensor reference voltages, PCM internal
//   "Network"      - CAN bus, lost comm
//   "Brakes"       - ABS, brake
//   "Body"         - BCM
//   "SRS"          - airbag
//   "TPMS"         - tire pressure

(function () {
  "use strict";
  const VD = /** @type {any} */ (window.VoltDashboard = window.VoltDashboard || /** @type {VoltDashboard} */ ({}));

  VD.DTC_CAUSES = {

    // --- MAF sensor (P0100-P0103) ---
    "P0100": {
      "causes": [
        "Dirty or contaminated MAF sensor element",
        "Air leak between MAF sensor and throttle body",
        "Damaged or corroded MAF sensor wiring / connector",
        "Failed MAF sensor",
        "Clogged air filter causing restriction"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0101": {
      "causes": [
        "Dirty or contaminated MAF sensor element",
        "Air leak downstream of MAF (intake boot, PCV hose)",
        "Clogged air filter or restricted air intake",
        "Failed MAF sensor (out-of-range output)",
        "Damaged MAF sensor wiring"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0102": {
      "causes": [
        "Dirty or failed MAF sensor (signal too low)",
        "Open or shorted MAF sensor wiring / connector",
        "Air leak before or at MAF sensor",
        "Failed MAF sensor power or ground circuit",
        "Clogged air filter"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0103": {
      "causes": [
        "Failed MAF sensor (signal shorted high)",
        "Short to voltage in MAF sensor signal wire",
        "Damaged MAF sensor connector / wiring harness",
        "Failed PCM MAF input circuit"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // --- MAP sensor (P0107-P0108) ---
    "P0107": {
      "causes": [
        "Failed MAP sensor (output shorted low)",
        "Open or shorted MAP sensor signal wiring",
        "Vacuum hose to MAP sensor disconnected or cracked",
        "Failed PCM MAP input circuit",
        "Low sensor reference voltage (check P0641)"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0108": {
      "causes": [
        "Failed MAP sensor (output shorted high)",
        "Short to voltage in MAP sensor signal wire",
        "Vacuum hose to MAP sensor blocked or pinched",
        "Damaged MAP sensor connector",
        "Failed PCM MAP input circuit"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // --- IAT sensor (P0112-P0113) ---
    "P0112": {
      "causes": [
        "IAT sensor shorted to ground (reads too cold)",
        "Damaged IAT sensor wiring or connector",
        "Failed IAT sensor",
        "Water / corrosion in sensor connector"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0113": {
      "causes": [
        "Open circuit in IAT sensor wiring (reads too hot)",
        "Failed IAT sensor (high resistance)",
        "Corroded or loose IAT sensor connector",
        "Failed PCM IAT input"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // --- ECT sensor (P0117-P0118) ---
    "P0117": {
      "causes": [
        "ECT sensor shorted to ground (reads engine overheated)",
        "Damaged ECT sensor wiring or connector",
        "Failed ECT sensor",
        "Water intrusion in connector"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0118": {
      "causes": [
        "Open circuit in ECT sensor signal wire (reads engine cold)",
        "Failed ECT sensor (high resistance or open)",
        "Corroded ECT sensor connector",
        "Low coolant level allowing sensor to read air"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // --- TPS (P0121-P0123) ---
    "P0121": {
      "causes": [
        "Worn or dirty throttle body bore / plate causing TPS correlation error",
        "Failed TPS sensor 'A' (out-of-range)",
        "Carbon buildup on throttle body (electronic throttle)",
        "Damaged TPS wiring or connector",
        "Failed PCM TPS input"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0122": {
      "causes": [
        "TPS sensor 'A' signal shorted to ground",
        "Open TPS signal wire or failed sensor",
        "Damaged throttle body / pedal position sensor wiring",
        "Low sensor reference voltage (5V ref fault)"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0123": {
      "causes": [
        "TPS sensor 'A' signal shorted to voltage",
        "Failed TPS sensor (output stuck high)",
        "Damaged TPS wiring harness (short to B+)",
        "Failed PCM TPS input"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // --- ECT thermostat (P0128) ---
    "P0128": {
      "causes": [
        "Stuck-open engine thermostat (most common cause)",
        "Low coolant level preventing proper warm-up",
        "Failed ECT sensor reading cold bias",
        "Defective thermostat housing or bypass leak"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // --- O2 sensor Bank 1 Sensor 1 (P0130-P0135) ---
    "P0130": {
      "causes": [
        "Failed upstream O2 sensor (Bank 1)",
        "Damaged O2 sensor wiring or connector",
        "Exhaust leak near O2 sensor affecting reading",
        "Failed O2 sensor heater circuit",
        "Contaminated O2 sensor (oil or coolant)"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0131": {
      "causes": [
        "Vacuum leak causing lean condition (O2 reads low)",
        "Failing or failed upstream O2 sensor",
        "Fuel delivery problem (weak pump, clogged filter)",
        "Exhaust leak near O2 sensor (dilutes exhaust sample)",
        "Open O2 sensor signal wire"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0132": {
      "causes": [
        "Failed upstream O2 sensor stuck rich",
        "Short to voltage in O2 sensor signal wire",
        "Rich fuel condition (leaking injector, high fuel pressure)",
        "Contaminated O2 sensor (oil or fuel)"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0133": {
      "causes": [
        "Aging or failing upstream O2 sensor (slow response)",
        "Exhaust leak near the sensor diluting sample",
        "O2 sensor contaminated (oil, coolant, RTV silicone)",
        "Fuel trim at limit preventing sensor activity"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0134": {
      "causes": [
        "Failed upstream O2 sensor (no signal)",
        "Open O2 sensor signal or heater wire",
        "Exhaust leak upstream of sensor",
        "O2 sensor heater not functioning (check P0135)"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0135": {
      "causes": [
        "Failed O2 sensor heater element (internal)",
        "Open or short in heater circuit wiring",
        "Blown fuse for O2 sensor heater circuit",
        "Failed PCM heater control driver"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // --- O2 sensor Bank 1 Sensor 2 (P0136-P0141) ---
    "P0136": {
      "causes": [
        "Failed downstream O2 sensor (Bank 1)",
        "Damaged sensor wiring or connector",
        "Exhaust leak near sensor",
        "Failing catalytic converter affecting post-cat reading"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0137": {
      "causes": [
        "Failed downstream O2 sensor (signal low / lean)",
        "Exhaust leak downstream of catalyst",
        "Open signal wire to downstream O2 sensor",
        "Fuel system running lean (confirms with fuel trim codes)"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0138": {
      "causes": [
        "Failed downstream O2 sensor (signal high / rich)",
        "Short to voltage in O2 signal circuit",
        "Failed catalytic converter (can mask sensor reading)",
        "Rich running condition confirmed by fuel trim"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0140": {
      "causes": [
        "Failed downstream O2 sensor (no activity)",
        "Open sensor signal wire",
        "Heater circuit failure preventing sensor warm-up",
        "Exhaust leak near sensor"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0141": {
      "causes": [
        "Failed downstream O2 sensor heater element",
        "Open or shorted heater circuit wiring",
        "Blown O2 heater fuse",
        "Failed PCM heater driver"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // --- O2 sensor Bank 2 Sensor 1 (P0150-P0155) ---
    "P0150": {
      "causes": [
        "Failed upstream O2 sensor (Bank 2)",
        "Damaged O2 sensor wiring / connector",
        "Exhaust leak near Bank 2 upstream sensor",
        "Failed O2 sensor heater"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0151": {
      "causes": [
        "Vacuum leak on Bank 2 side causing lean condition",
        "Failing Bank 2 upstream O2 sensor",
        "Exhaust manifold leak near sensor",
        "Fuel delivery problem (weak injector on Bank 2)"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0152": {
      "causes": [
        "Failed Bank 2 upstream O2 sensor stuck rich",
        "Rich condition on Bank 2 (leaking injector)",
        "Short to voltage in O2 signal wire",
        "Contaminated O2 sensor"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0153": {
      "causes": [
        "Aging or failing Bank 2 upstream O2 sensor (slow response)",
        "Exhaust leak near sensor",
        "Sensor contaminated by oil or coolant",
        "Fuel trim at limit"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0155": {
      "causes": [
        "Failed Bank 2 O2 sensor heater element",
        "Open or shorted heater wiring",
        "Blown O2 heater fuse",
        "Failed PCM heater driver"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // --- Fuel trim (P0170-P0175) ---
    "P0170": {
      "causes": [
        "Vacuum leak (intake manifold, PCV hose, brake booster)",
        "Failing or dirty MAF sensor",
        "Failing upstream O2 sensor",
        "Fuel pressure issue (weak pump or clogged filter)",
        "Exhaust leak near upstream O2 sensor"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0171": {
      "causes": [
        "Vacuum leak (intake manifold, PCV hose, brake booster)",
        "Failing MAF sensor (reads low)",
        "Dirty or failing upstream O2 sensor",
        "Failing fuel pump or clogged fuel filter",
        "Stuck-open EVAP purge valve flooding or starving fuel trim",
        "Leaking fuel pressure regulator"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0172": {
      "causes": [
        "Leaking fuel injector(s)",
        "Faulty fuel pressure regulator (high pressure)",
        "Failing or contaminated upstream O2 sensor",
        "Stuck-open EVAP purge valve dumping fuel vapors",
        "Engine oil or coolant entering combustion (head gasket)"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0173": {
      "causes": [
        "Vacuum leak on Bank 2 intake side",
        "Failing Bank 2 upstream O2 sensor",
        "Weak fuel pressure to Bank 2 injectors",
        "Exhaust leak near Bank 2 upstream sensor"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0174": {
      "causes": [
        "Vacuum leak on Bank 2 intake side",
        "Failing MAF sensor (often affects both banks)",
        "Weak fuel delivery to Bank 2",
        "Exhaust leak near Bank 2 upstream O2 sensor",
        "Dirty or clogged Bank 2 fuel injectors"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0175": {
      "causes": [
        "Leaking Bank 2 fuel injector(s)",
        "High fuel pressure on Bank 2 side",
        "Contaminated Bank 2 upstream O2 sensor",
        "Stuck-open EVAP purge valve"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // --- Misfire (P0300-P0308) ---
    "P0300": {
      "causes": [
        "Worn or fouled spark plugs",
        "Failing ignition coil(s) or ignition coil pack",
        "Clogged or leaking fuel injector(s)",
        "Vacuum leak causing lean misfire",
        "Low fuel pressure (weak pump or clogged filter)",
        "Low compression (worn rings, burned valve, head gasket)"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },
    "P0301": {
      "causes": [
        "Worn or fouled cylinder 1 spark plug",
        "Failing cylinder 1 ignition coil",
        "Clogged or leaking cylinder 1 fuel injector",
        "Low compression on cylinder 1",
        "Vacuum leak near cylinder 1 intake port"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },
    "P0302": {
      "causes": [
        "Worn or fouled cylinder 2 spark plug",
        "Failing cylinder 2 ignition coil",
        "Clogged or leaking cylinder 2 fuel injector",
        "Low compression on cylinder 2",
        "Vacuum leak near cylinder 2 intake port"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },
    "P0303": {
      "causes": [
        "Worn or fouled cylinder 3 spark plug",
        "Failing cylinder 3 ignition coil",
        "Clogged or leaking cylinder 3 fuel injector",
        "Low compression on cylinder 3",
        "Vacuum leak near cylinder 3 intake port"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },
    "P0304": {
      "causes": [
        "Worn or fouled cylinder 4 spark plug",
        "Failing cylinder 4 ignition coil",
        "Clogged or leaking cylinder 4 fuel injector",
        "Low compression on cylinder 4",
        "Vacuum leak near cylinder 4 intake port"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },
    "P0305": {
      "causes": [
        "Worn or fouled cylinder 5 spark plug",
        "Failing cylinder 5 ignition coil",
        "Clogged or leaking cylinder 5 fuel injector",
        "Low compression on cylinder 5"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },
    "P0306": {
      "causes": [
        "Worn or fouled cylinder 6 spark plug",
        "Failing cylinder 6 ignition coil",
        "Clogged or leaking cylinder 6 fuel injector",
        "Low compression on cylinder 6"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },

    // --- Knock sensors (P0325-P0334) ---
    "P0325": {
      "causes": [
        "Failed knock sensor (Bank 1)",
        "Knock sensor wiring open, short, or corroded connector",
        "Loose or incorrectly torqued knock sensor",
        "Engine mechanical noise causing false knock signal",
        "Failed PCM knock input"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0326": {
      "causes": [
        "Actual engine knock / detonation (low-octane fuel)",
        "Failed knock sensor (out-of-range)",
        "Carbon buildup causing preignition",
        "Overheating engine",
        "Loose knock sensor"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0327": {
      "causes": [
        "Failed knock sensor (signal low)",
        "Open or shorted knock sensor signal wire",
        "Corroded knock sensor connector",
        "Loose knock sensor body contact"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0328": {
      "causes": [
        "Failed knock sensor (signal high / short to voltage)",
        "Shorted knock sensor wiring",
        "Actual severe engine knock",
        "Failed PCM knock input"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0330": {
      "causes": [
        "Failed knock sensor (Bank 2)",
        "Open or corroded Bank 2 knock sensor wiring",
        "Loose Bank 2 knock sensor",
        "Failed PCM knock input circuit"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0332": {
      "causes": [
        "Failed Bank 2 knock sensor (signal low)",
        "Open knock sensor 2 signal wire",
        "Corroded Bank 2 knock sensor connector",
        "Loose knock sensor"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0333": {
      "causes": [
        "Failed Bank 2 knock sensor (signal high / shorted)",
        "Shorted knock sensor 2 wiring",
        "Actual engine knock on Bank 2"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // --- Crankshaft position sensor (P0335-P0339) ---
    "P0335": {
      "causes": [
        "Failed crankshaft position sensor",
        "Damaged or broken reluctor ring (tone wheel)",
        "Open, short, or corroded CKP sensor wiring",
        "Excessive crankshaft end-play or bearing wear",
        "Metal debris on CKP sensor tip"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },
    "P0336": {
      "causes": [
        "Damaged or missing teeth on crankshaft reluctor ring",
        "Failing crankshaft position sensor (intermittent signal)",
        "Excessive air gap between sensor and reluctor ring",
        "Wiring interference or damaged shielding"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },
    "P0339": {
      "causes": [
        "Intermittently failing crankshaft position sensor",
        "Loose or intermittent CKP sensor connector",
        "Damaged wiring harness (chafing / intermittent open)",
        "Reluctor ring partially damaged"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },

    // --- Camshaft position sensor (P0340-P0344) ---
    "P0340": {
      "causes": [
        "Failed camshaft position sensor (Bank 1 'A')",
        "Open or shorted CMP sensor wiring",
        "Damaged timing chain or jumped timing (cam out of sync)",
        "Broken or missing cam reluctor wheel",
        "Failed CMP sensor power or ground"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },
    "P0341": {
      "causes": [
        "Jumped timing chain or worn timing components",
        "Failing camshaft position sensor",
        "Stretched timing chain causing cam timing error",
        "Failed VVT solenoid holding cam in wrong position"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },
    "P0342": {
      "causes": [
        "Open CMP sensor signal wire",
        "Failed camshaft position sensor (no output)",
        "Corroded sensor connector",
        "Failed PCM CMP input"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },
    "P0343": {
      "causes": [
        "Short to voltage in CMP sensor signal wire",
        "Failed camshaft position sensor (output high)",
        "Damaged sensor wiring harness"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },
    "P0344": {
      "causes": [
        "Intermittently failing camshaft position sensor",
        "Loose or intermittent CMP sensor connector",
        "Chafed or intermittent wiring",
        "Partial reluctor ring damage"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },

    // --- Ignition coils (P0351-P0356) ---
    "P0351": {
      "causes": [
        "Failed ignition coil 'A' (primary winding open or short)",
        "Damaged ignition coil connector or wiring",
        "Spark plug arcing to coil boot causing coil failure",
        "Failed PCM coil driver circuit"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },
    "P0352": {
      "causes": [
        "Failed ignition coil 'B'",
        "Damaged coil wiring / connector",
        "Worn spark plug causing coil overload",
        "Failed PCM coil driver"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },
    "P0353": {
      "causes": [
        "Failed ignition coil 'C'",
        "Damaged coil wiring / connector",
        "Worn spark plug causing coil overload",
        "Failed PCM coil driver"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },
    "P0354": {
      "causes": [
        "Failed ignition coil 'D'",
        "Damaged coil wiring / connector",
        "Worn spark plug causing coil overload",
        "Failed PCM coil driver"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },
    "P0355": {
      "causes": [
        "Failed ignition coil 'E'",
        "Damaged coil wiring / connector",
        "Worn spark plug causing coil overload"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },
    "P0356": {
      "causes": [
        "Failed ignition coil 'F'",
        "Damaged coil wiring / connector",
        "Worn spark plug causing coil overload"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },

    // --- EGR (P0401-P0406) ---
    "P0401": {
      "causes": [
        "Clogged EGR valve or EGR passages with carbon deposits",
        "Failed EGR valve (stuck closed or not opening fully)",
        "Clogged EGR cooler (if equipped)",
        "Vacuum hose to EGR valve cracked or disconnected",
        "Failed DPFE / EGR differential pressure sensor"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0402": {
      "causes": [
        "EGR valve stuck open",
        "Failed EGR valve position sensor reading high flow",
        "DPFE sensor failed (reading excessive flow)",
        "Leaking EGR valve seat"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0403": {
      "causes": [
        "Failed EGR valve solenoid (open circuit)",
        "Damaged EGR solenoid wiring or connector",
        "Failed PCM EGR control driver",
        "EGR valve internal short"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0404": {
      "causes": [
        "EGR valve stuck (not responding to PCM commands)",
        "Carbon buildup preventing EGR valve movement",
        "Failed EGR position sensor",
        "Vacuum supply to EGR valve insufficient"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0405": {
      "causes": [
        "Failed DPFE or EGR position sensor (signal low)",
        "Open or shorted EGR sensor signal wiring",
        "Sensor reference voltage fault",
        "EGR sensor connector corrosion"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0406": {
      "causes": [
        "Failed DPFE or EGR position sensor (signal high)",
        "Short to voltage in EGR sensor signal wire",
        "Failed EGR sensor",
        "Damaged sensor connector"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // --- Secondary air injection (P0411-P0412) ---
    // Note: P0410 (general SAI) not present in Volt DTC list; P0411/P0412 are.
    "P0411": {
      "causes": [
        "Failed or leaking secondary air injection check valve",
        "Clogged SAI pump outlet or air injection ports",
        "Failed SAI pump (low output)",
        "Collapsed or kinked SAI hose"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0412": {
      "causes": [
        "Failed secondary air injection switching valve (circuit)",
        "Damaged switching valve wiring or connector",
        "Failed PCM SAI relay driver",
        "Blown SAI relay or fuse"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // --- Catalyst (P0420-P0430) ---
    "P0420": {
      "causes": [
        "Failed / worn-out catalytic converter (Bank 1)",
        "Failed downstream O2 sensor (giving false reading)",
        "Exhaust leak near downstream O2 sensor",
        "Engine oil or coolant contaminating the catalyst",
        "Rich running condition damaging catalyst"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0421": {
      "causes": [
        "Failed warm-up catalyst (Bank 1)",
        "Failed downstream O2 sensor",
        "Engine running rich damaging warm-up catalyst",
        "Exhaust leak near downstream sensor"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0430": {
      "causes": [
        "Failed / worn-out catalytic converter (Bank 2)",
        "Failed Bank 2 downstream O2 sensor",
        "Exhaust leak near Bank 2 downstream sensor",
        "Rich running condition on Bank 2",
        "Oil or coolant contaminating Bank 2 catalyst"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // --- EVAP (P0440-P0457) ---
    "P0440": {
      "causes": [
        "Loose, damaged, or missing fuel cap",
        "Failed EVAP purge valve (stuck open or closed)",
        "Failed EVAP vent valve",
        "Cracked or damaged EVAP hose / canister",
        "Failed fuel tank pressure sensor"
      ],
      "severity": "info",
      "category": "1.4L engine"
    },
    "P0441": {
      "causes": [
        "Failed EVAP purge solenoid (stuck closed or open)",
        "Clogged charcoal canister",
        "Kinked or damaged EVAP purge hose",
        "Fuel tank pressure sensor failure",
        "Blocked canister vent hose"
      ],
      "severity": "info",
      "category": "1.4L engine"
    },
    "P0442": {
      "causes": [
        "Loose or damaged fuel cap (most common)",
        "Small crack or hole in EVAP hose or canister",
        "Failed fuel tank pressure sensor",
        "Leaking EVAP purge or vent valve",
        "Leak at fuel tank filler neck or tank seam"
      ],
      "severity": "info",
      "category": "1.4L engine"
    },
    "P0443": {
      "causes": [
        "Failed EVAP purge control valve (solenoid)",
        "Open or shorted purge valve wiring",
        "Blown fuse for EVAP purge circuit",
        "Failed PCM purge valve driver"
      ],
      "severity": "info",
      "category": "1.4L engine"
    },
    "P0446": {
      "causes": [
        "Failed EVAP vent control valve (stuck or electrical)",
        "Blocked canister vent hose or filter",
        "Damaged vent valve wiring",
        "Failed PCM vent valve driver"
      ],
      "severity": "info",
      "category": "1.4L engine"
    },
    "P0449": {
      "causes": [
        "Failed EVAP vent valve / solenoid (circuit fault)",
        "Open or shorted vent valve wiring",
        "Corroded vent valve connector",
        "Failed PCM vent valve driver"
      ],
      "severity": "info",
      "category": "1.4L engine"
    },
    "P0452": {
      "causes": [
        "Failed fuel tank pressure (FTP) sensor (signal low)",
        "Open or shorted FTP sensor signal wiring",
        "FTP sensor connector corrosion",
        "Damaged FTP sensor"
      ],
      "severity": "info",
      "category": "1.4L engine"
    },
    "P0453": {
      "causes": [
        "Failed FTP sensor (signal high)",
        "Short to voltage in FTP sensor signal wire",
        "Damaged FTP sensor or connector",
        "Blocked vent path causing pressure buildup"
      ],
      "severity": "info",
      "category": "1.4L engine"
    },
    "P0455": {
      "causes": [
        "Loose, off, or cracked fuel cap (most common)",
        "Large crack in EVAP hose, canister, or fuel tank",
        "Failed EVAP vent or purge valve (large leak path)",
        "Damaged fuel tank filler neck or vent tube"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0456": {
      "causes": [
        "Slightly loose or deteriorated fuel cap seal",
        "Pinhole leak in EVAP hose",
        "Leaking EVAP purge or vent valve seat",
        "Micro-crack in charcoal canister or fuel tank"
      ],
      "severity": "info",
      "category": "1.4L engine"
    },
    "P0457": {
      "causes": [
        "Fuel cap not tightened after refueling",
        "Damaged or missing fuel cap",
        "Cracked fuel cap seal or broken ratchet tabs",
        "Damaged fuel filler neck causing poor cap seal"
      ],
      "severity": "info",
      "category": "1.4L engine"
    },

    // --- Cooling fans (P0480-P0485) ---
    "P0480": {
      "causes": [
        "Failed cooling fan relay (fan 1)",
        "Open or shorted fan relay control wiring",
        "Failed PCM fan relay driver",
        "Failed cooling fan motor (causing relay circuit fault)"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0481": {
      "causes": [
        "Failed cooling fan relay (fan 2)",
        "Open or shorted fan relay control wiring",
        "Failed PCM fan relay driver",
        "Failed cooling fan motor"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0483": {
      "causes": [
        "Failed cooling fan motor (not responding to commands)",
        "Cooling fan relay fault",
        "Fan speed sensor failure (if equipped)",
        "Damaged fan wiring"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0485": {
      "causes": [
        "Open or shorted cooling fan power or ground circuit",
        "Failed cooling fan relay or fuse",
        "Damaged fan motor wiring",
        "Failed cooling fan motor"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // --- VSS / Idle (P0500-P0507) ---
    "P0500": {
      "causes": [
        "Failed vehicle speed sensor (VSS)",
        "Damaged or broken VSS reluctor ring / tone wheel",
        "Open or shorted VSS wiring",
        "Failed ABS module not sending VSS signal to PCM"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0501": {
      "causes": [
        "VSS output intermittent or out of range",
        "Worn VSS reluctor ring",
        "Failing VSS sensor",
        "Wiring interference or damaged shielding"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0502": {
      "causes": [
        "Open VSS signal circuit",
        "Failed VSS sensor (no output)",
        "Broken VSS reluctor ring",
        "Open VSS wiring or corroded connector"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0506": {
      "causes": [
        "Carbon buildup on throttle body bore / plate (electronic throttle)",
        "Failed idle air control valve (IAC) or dirty IAC",
        "Vacuum leak causing RPM too low at idle",
        "Throttle body not fully closing",
        "Failed throttle actuator"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0507": {
      "causes": [
        "Vacuum leak causing high idle",
        "Failed IAC valve stuck open (too much air)",
        "Throttle body stuck partially open",
        "EVAP purge valve stuck open at idle",
        "Failed throttle actuator control"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // --- Sensor reference voltages (P0641, P0651, P0697) ---
    "P0641": {
      "causes": [
        "Short to ground on 5V reference 'A' circuit (shared sensors)",
        "Failed sensor pulling down the reference rail (MAP, TPS, etc.)",
        "Damaged wiring harness shorting 5V reference to ground",
        "Failed PCM 5V reference regulator"
      ],
      "severity": "warning",
      "category": "12V power"
    },
    "P0651": {
      "causes": [
        "Short to ground on 5V reference 'B' circuit",
        "Failed sensor on reference 'B' rail shorting to ground",
        "Chafed wiring harness",
        "Failed PCM 5V reference 'B' regulator"
      ],
      "severity": "warning",
      "category": "12V power"
    },
    "P0697": {
      "causes": [
        "Short to ground on 5V reference 'C' circuit",
        "Failed sensor on reference 'C' rail shorting to ground",
        "Damaged wiring harness",
        "Failed PCM 5V reference 'C' regulator"
      ],
      "severity": "warning",
      "category": "12V power"
    },

    // --- System voltage (P0560-P0563) ---
    "P0562": {
      "causes": [
        "Failing or discharged 12V battery",
        "Failing alternator (low charging voltage)",
        "High-resistance battery cable or ground connection",
        "Excessive electrical load"
      ],
      "severity": "warning",
      "category": "12V power"
    },
    "P0563": {
      "causes": [
        "Failed voltage regulator in alternator (overcharging)",
        "Loose battery connections causing voltage spikes",
        "Failed PCM voltage reference",
        "Wiring issue on charging circuit"
      ],
      "severity": "warning",
      "category": "12V power"
    },

    // --- Transmission (P0700-P0779) ---
    "P0700": {
      "causes": [
        "TCM has detected a transmission fault and requested MIL",
        "Low or contaminated transmission fluid",
        "Failed shift solenoid (check co-set codes)",
        "Failed transmission fluid temperature sensor",
        "Internal transmission mechanical failure"
      ],
      "severity": "warning",
      "category": "Drive unit"
    },
    "P0705": {
      "causes": [
        "Failed transmission range sensor (neutral safety switch)",
        "Misadjusted transmission range sensor linkage",
        "Damaged range sensor wiring or connector",
        "Internal transmission selector drum or detent issue"
      ],
      "severity": "warning",
      "category": "Drive unit"
    },
    "P0711": {
      "causes": [
        "Failed transmission fluid temperature sensor",
        "Severely low or degraded transmission fluid",
        "Damaged TFT sensor wiring / connector",
        "Internal transmission overheating condition"
      ],
      "severity": "warning",
      "category": "Drive unit"
    },
    "P0712": {
      "causes": [
        "TFT sensor signal wire shorted to ground",
        "Failed transmission fluid temperature sensor",
        "Corroded TFT sensor connector",
        "Damaged wiring harness in transmission"
      ],
      "severity": "warning",
      "category": "Drive unit"
    },
    "P0713": {
      "causes": [
        "Open TFT sensor signal wire",
        "Failed TFT sensor (open circuit / high resistance)",
        "Corroded connector in transmission wiring",
        "Failed PCM TFT input"
      ],
      "severity": "warning",
      "category": "Drive unit"
    },
    "P0715": {
      "causes": [
        "Failed input / turbine speed sensor",
        "Damaged turbine speed sensor wiring / connector",
        "Worn or damaged input shaft causing erratic signal",
        "Low transmission fluid affecting sensor operation"
      ],
      "severity": "warning",
      "category": "Drive unit"
    },
    "P0720": {
      "causes": [
        "Failed output speed sensor",
        "Damaged output sensor wiring / connector",
        "Worn or damaged output shaft reluctor ring",
        "Low transmission fluid"
      ],
      "severity": "warning",
      "category": "Drive unit"
    },
    "P0730": {
      "causes": [
        "Low or contaminated transmission fluid",
        "Failed shift solenoid(s)",
        "Worn or slipping transmission clutch packs",
        "Failed pressure control solenoid",
        "Internal transmission mechanical damage"
      ],
      "severity": "critical",
      "category": "Drive unit"
    },
    "P0740": {
      "causes": [
        "Failed torque converter clutch solenoid",
        "Low or contaminated transmission fluid",
        "Faulty torque converter (worn clutch lining)",
        "Damaged TCC solenoid wiring",
        "Failed PCM TCC driver"
      ],
      "severity": "warning",
      "category": "Drive unit"
    },
    "P0741": {
      "causes": [
        "Worn torque converter clutch lining (most common)",
        "Low transmission fluid pressure",
        "Failed TCC solenoid (stuck off)",
        "Failed pressure control solenoid",
        "Contaminated transmission fluid"
      ],
      "severity": "warning",
      "category": "Drive unit"
    },
    "P0742": {
      "causes": [
        "TCC solenoid stuck on (mechanical or electrical)",
        "Contaminated transmission fluid causing valve body sticking",
        "Failed TCC solenoid",
        "Stuck-on torque converter clutch hydraulic circuit"
      ],
      "severity": "warning",
      "category": "Drive unit"
    },
    "P0750": {
      "causes": [
        "Failed shift solenoid 'A' (mechanical or electrical)",
        "Low or contaminated transmission fluid",
        "Damaged shift solenoid 'A' wiring",
        "Clogged valve body passages"
      ],
      "severity": "warning",
      "category": "Drive unit"
    },
    "P0753": {
      "causes": [
        "Failed shift solenoid 'A' (electrical short or open)",
        "Damaged wiring to shift solenoid 'A'",
        "Failed PCM shift solenoid driver",
        "Corrosion in transmission wiring connector"
      ],
      "severity": "warning",
      "category": "Drive unit"
    },
    "P0755": {
      "causes": [
        "Failed shift solenoid 'B'",
        "Low or contaminated transmission fluid",
        "Damaged shift solenoid 'B' wiring",
        "Clogged valve body passage for solenoid 'B'"
      ],
      "severity": "warning",
      "category": "Drive unit"
    },
    "P0756": {
      "causes": [
        "Failed shift solenoid 'B' (stuck off / performance)",
        "Contaminated transmission fluid causing valve sticking",
        "Clogged solenoid screen or valve body passage",
        "Damaged solenoid wiring"
      ],
      "severity": "warning",
      "category": "Drive unit"
    },
    "P0760": {
      "causes": [
        "Failed shift solenoid 'C'",
        "Low or contaminated transmission fluid",
        "Damaged wiring to solenoid 'C'",
        "Clogged valve body passage"
      ],
      "severity": "warning",
      "category": "Drive unit"
    },
    "P0765": {
      "causes": [
        "Failed shift solenoid 'D'",
        "Low or contaminated transmission fluid",
        "Damaged wiring to solenoid 'D'",
        "Clogged valve body passage"
      ],
      "severity": "warning",
      "category": "Drive unit"
    },
    "P0770": {
      "causes": [
        "Failed shift solenoid 'E'",
        "Contaminated transmission fluid",
        "Damaged wiring to solenoid 'E'",
        "Clogged solenoid screen"
      ],
      "severity": "warning",
      "category": "Drive unit"
    },
    "P0776": {
      "causes": [
        "Failed pressure control solenoid 'B' (stuck off)",
        "Contaminated or degraded transmission fluid",
        "Clogged solenoid screen or valve body passage",
        "Damaged solenoid wiring"
      ],
      "severity": "warning",
      "category": "Drive unit"
    },
    "P0777": {
      "causes": [
        "Pressure control solenoid 'B' stuck on (mechanical)",
        "Contaminated fluid causing valve body sticking",
        "Failed solenoid",
        "Damaged wiring causing solenoid energized continuously"
      ],
      "severity": "warning",
      "category": "Drive unit"
    },

    // --- Throttle actuator (P2100-P2135) ---
    "P2100": {
      "causes": [
        "Failed electronic throttle body motor (open circuit)",
        "Damaged throttle actuator control wiring",
        "Failed PCM throttle motor driver",
        "Carbon buildup jamming throttle body"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },
    "P2101": {
      "causes": [
        "Carbon buildup on throttle bore causing range / performance fault",
        "Failed electronic throttle body",
        "Throttle position sensor correlation failure",
        "Damaged throttle actuator wiring"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },
    "P2106": {
      "causes": [
        "PCM commanding limited power due to another throttle fault",
        "Failed throttle body or throttle position sensor",
        "Check for co-set throttle codes (P2100, P2101, P2135)",
        "Damaged throttle actuator wiring"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },
    "P2119": {
      "causes": [
        "Carbon buildup on throttle bore (electronic throttle)",
        "Throttle blade not fully closing to learned minimum",
        "Failed throttle body",
        "Throttle idle position not relearned after cleaning"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P2122": {
      "causes": [
        "Open or shorted accelerator pedal position sensor 'D'",
        "Failed pedal position sensor",
        "Damaged APP sensor wiring / connector",
        "Low 5V reference voltage (check P0641 / P0651)"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },
    "P2123": {
      "causes": [
        "Short to voltage in APP sensor 'D' signal wire",
        "Failed accelerator pedal position sensor",
        "Damaged APP sensor wiring",
        "Failed PCM APP input"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },
    "P2127": {
      "causes": [
        "Open or shorted APP sensor 'E' signal wire",
        "Failed accelerator pedal position sensor",
        "Damaged wiring or connector at pedal assembly",
        "Low 5V reference voltage"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },
    "P2128": {
      "causes": [
        "Short to voltage in APP sensor 'E' signal wire",
        "Failed accelerator pedal position sensor",
        "Damaged APP sensor wiring"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },
    "P2135": {
      "causes": [
        "Failed throttle body (TPS A/B voltage correlation fault)",
        "Damaged throttle position sensor wiring",
        "Carbon buildup causing binding throttle (sensor skew)",
        "Failed PCM TPS input comparison"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },
    "P2138": {
      "causes": [
        "Failed accelerator pedal position sensor (D/E correlation)",
        "Damaged APP sensor wiring or connector",
        "5V reference voltage fault affecting one sensor channel",
        "Failed PCM APP comparison circuit"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },

    // --- Additional fuel trim / O2 extended ---
    "P2096": {
      "causes": [
        "Failed catalytic converter (post-cat fuel trim too lean)",
        "Exhaust leak downstream of catalyst skewing O2 reading",
        "Failed downstream O2 sensor",
        "Vacuum leak or lean condition on Bank 1"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P2097": {
      "causes": [
        "Rich running condition persisting post-catalyst",
        "Failed downstream O2 sensor",
        "Leaking fuel injector causing rich exhaust",
        "Failed catalytic converter"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P2187": {
      "causes": [
        "Vacuum leak (idle-specific lean condition, Bank 1)",
        "Dirty or failing MAF sensor",
        "Failing IAC or idle air control system",
        "Clogged fuel injectors at idle"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P2188": {
      "causes": [
        "Leaking fuel injector(s) causing rich idle",
        "Stuck-open EVAP purge valve at idle",
        "High fuel pressure at idle",
        "Contaminated upstream O2 sensor"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P2195": {
      "causes": [
        "Failed upstream O2 sensor stuck lean",
        "Severe vacuum leak causing persistent lean mixture",
        "Exhaust leak near upstream sensor",
        "Fuel delivery failure"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P2196": {
      "causes": [
        "Failed upstream O2 sensor stuck rich",
        "Leaking fuel injector(s) causing persistent rich condition",
        "Contaminated O2 sensor (oil, fuel)",
        "Stuck-open EVAP purge valve"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P2270": {
      "causes": [
        "Failed downstream O2 sensor stuck lean",
        "Exhaust leak downstream causing perpetual lean reading",
        "Catalyst consumed leaving constant lean post-cat signal"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P2271": {
      "causes": [
        "Failed downstream O2 sensor stuck rich",
        "Catalyst failure causing rich post-cat exhaust",
        "Contaminated downstream O2 sensor"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // --- VVT / cam timing (P0010-P0017) ---
    "P0010": {
      "causes": [
        "Failed or stuck VVT oil control solenoid (Bank 1 intake)",
        "Low oil level or pressure preventing VVT operation",
        "Dirty / sludged oil causing VVT solenoid sticking",
        "Damaged VVT solenoid wiring or connector",
        "Failed PCM VVT driver"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0011": {
      "causes": [
        "Stuck-open VVT oil control solenoid (over-advanced cam)",
        "Sludged oil passages in cam phaser / VVT actuator",
        "Worn timing chain allowing cam to stay advanced",
        "Low oil pressure to VVT actuator"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0012": {
      "causes": [
        "Stuck-closed VVT oil control solenoid (cam over-retarded)",
        "Worn cam phaser actuator (internal oil leak)",
        "Sludged oil passages",
        "Stretched timing chain"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0013": {
      "causes": [
        "Failed VVT oil control solenoid (Bank 1 exhaust cam)",
        "Damaged solenoid wiring or connector",
        "Low oil pressure",
        "Failed PCM VVT driver"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0014": {
      "causes": [
        "Stuck-open exhaust VVT solenoid (cam over-advanced)",
        "Sludged oil in cam phaser",
        "Worn timing components",
        "Low oil pressure"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0016": {
      "causes": [
        "Stretched or worn timing chain (Bank 1 cam-crank correlation)",
        "Failed VVT actuator / cam phaser (cam stuck advanced)",
        "Worn timing chain tensioner or guides",
        "Jumped timing chain (severe)"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },
    "P0017": {
      "causes": [
        "Stretched timing chain (Bank 1 'B' cam-crank correlation)",
        "Failed exhaust cam VVT actuator",
        "Worn timing chain tensioner",
        "Jumped timing (severe)"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },

    // --- O2 heater Bank 2 (P0155) already done above ---

    // --- Camshaft Bank 2 sensor (P0345) ---
    "P0345": {
      "causes": [
        "Failed camshaft position sensor (Bank 2 intake 'A')",
        "Open or shorted CMP sensor wiring on Bank 2",
        "Jumped timing (Bank 2 cam out of sync)",
        "Broken Bank 2 cam reluctor wheel"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },

    // --- Fuel pressure (P0087-P0088) ---
    "P0087": {
      "causes": [
        "Failing or weak fuel pump",
        "Clogged fuel filter (if serviceable)",
        "Clogged fuel pump inlet strainer",
        "Kinked or restricted fuel supply line",
        "Failing fuel pressure regulator (leaking fuel back to tank)"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },
    "P0088": {
      "causes": [
        "Faulty fuel pressure regulator stuck closed (high pressure)",
        "Kinked or restricted fuel return line",
        "Failed high-pressure fuel pump control (GDI engines)",
        "Failed fuel pressure sensor (reading high)"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },

    // --- Injector circuits (P0201-P0204) ---
    "P0201": {
      "causes": [
        "Open injector circuit for cylinder 1 (wiring or connector)",
        "Failed fuel injector (open internal winding)",
        "Blown injector fuse or failed injector driver module",
        "Failed PCM injector driver for cylinder 1"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },
    "P0202": {
      "causes": [
        "Open injector circuit for cylinder 2",
        "Failed fuel injector (open internal winding)",
        "Damaged injector wiring / connector",
        "Failed PCM injector driver for cylinder 2"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },
    "P0203": {
      "causes": [
        "Open injector circuit for cylinder 3",
        "Failed fuel injector (open winding)",
        "Damaged injector wiring",
        "Failed PCM injector driver"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },
    "P0204": {
      "causes": [
        "Open injector circuit for cylinder 4",
        "Failed fuel injector (open winding)",
        "Damaged injector wiring",
        "Failed PCM injector driver"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },

    // --- Thermostat heater (P0597-P0599) ---
    "P0597": {
      "causes": [
        "Failed electronically controlled thermostat heater element",
        "Open circuit in thermostat heater control wiring",
        "Damaged thermostat housing assembly",
        "Failed PCM thermostat heater driver"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0598": {
      "causes": [
        "Short to ground in thermostat heater control circuit",
        "Failed thermostat heater element (internal short)",
        "Damaged wiring harness near thermostat"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0599": {
      "causes": [
        "Short to voltage in thermostat heater control circuit",
        "Failed PCM thermostat driver (output high)",
        "Damaged wiring near thermostat"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // --- PCM power / memory (P0601-P0606) ---
    "P0601": {
      "causes": [
        "Failed PCM / ECM (internal memory checksum error)",
        "Corrupted PCM calibration (flash programming error)",
        "Low system voltage causing PCM memory corruption",
        "PCM replacement needed"
      ],
      "severity": "critical",
      "category": "12V power"
    },
    "P0606": {
      "causes": [
        "Failed PCM / ECM internal processor",
        "Low supply voltage to PCM",
        "PCM programming error",
        "PCM replacement or reprogramming needed"
      ],
      "severity": "critical",
      "category": "12V power"
    },

    // --- Shift solenoids (P0751, P0752) ---
    "P0751": {
      "causes": [
        "Shift solenoid 'A' stuck off / valve body sticking",
        "Low or contaminated transmission fluid",
        "Clogged solenoid screen in valve body",
        "Failed solenoid (mechanical)"
      ],
      "severity": "warning",
      "category": "Drive unit"
    },
    "P0752": {
      "causes": [
        "Shift solenoid 'A' stuck on (mechanical)",
        "Contaminated fluid causing valve body sticking",
        "Failed solenoid",
        "Wiring keeping solenoid energized"
      ],
      "severity": "warning",
      "category": "Drive unit"
    },

    // --- Pressure control solenoid 'A' (P0745-P0748) ---
    "P0745": {
      "causes": [
        "Failed pressure control solenoid 'A'",
        "Low or contaminated transmission fluid",
        "Clogged solenoid screen",
        "Damaged solenoid wiring"
      ],
      "severity": "warning",
      "category": "Drive unit"
    },
    "P0746": {
      "causes": [
        "Pressure control solenoid 'A' stuck off",
        "Contaminated transmission fluid clogging solenoid",
        "Worn solenoid valve",
        "Low fluid level reducing hydraulic pressure"
      ],
      "severity": "warning",
      "category": "Drive unit"
    },
    "P0748": {
      "causes": [
        "Failed pressure control solenoid 'A' (electrical short or open)",
        "Damaged solenoid wiring / connector",
        "Failed PCM solenoid driver",
        "Corrosion in transmission wiring connector"
      ],
      "severity": "warning",
      "category": "Drive unit"
    },

    // --- Crankshaft/cam correlation (P0016 covered above) ---

    // --- EVAP additional ---
    "P0496": {
      "causes": [
        "Stuck-open EVAP purge valve allowing flow during non-purge",
        "Failed EVAP purge solenoid (valve not sealing)",
        "Carbon buildup holding purge valve open",
        "Damaged purge valve wiring causing continuous energization"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // --- Cooling system performance (P0217, P0218) ---
    "P0217": {
      "causes": [
        "Low coolant level",
        "Failed thermostat (stuck closed causing overheat)",
        "Failed cooling fan(s)",
        "Clogged radiator or coolant passages",
        "Failed water pump"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },

    // --- MAP / MAF correlation (P0068) ---
    "P0068": {
      "causes": [
        "Air leak between MAF sensor and throttle body",
        "Dirty or failing MAF sensor",
        "Failed MAP sensor",
        "Throttle position sensor correlation issue"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // --- Insufficient coolant temp (P0125, P0128 done above) ---
    "P0125": {
      "causes": [
        "Stuck-open thermostat (engine not reaching closed-loop temp)",
        "ECT sensor failure reading too cold",
        "Low coolant level causing inaccurate ECT reading",
        "Extended idling in very cold ambient temperatures"
      ],
      "severity": "info",
      "category": "1.4L engine"
    },

    // --- O2 sensor slow response Bank 1 Sensor 2 (P0139) ---
    "P0139": {
      "causes": [
        "Aging downstream O2 sensor (slow response)",
        "Exhaust leak near downstream sensor",
        "O2 sensor contaminated by oil or coolant",
        "Heater circuit degradation slowing sensor warm-up"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // --- Intake air temp / ECT range (P0106, P0116) ---
    "P0106": {
      "causes": [
        "Vacuum hose to MAP sensor cracked or kinked",
        "Failed MAP sensor (intermittent or out of range)",
        "Air leak in intake manifold affecting MAP reading",
        "Altitude or barometric sensor discrepancy"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0116": {
      "causes": [
        "Failing ECT sensor (intermittent output)",
        "Low coolant level causing sensor to contact air",
        "Leaking head gasket causing coolant loss",
        "Corroded ECT sensor connector"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // --- O2 sensor Bank 2 Sensor 2 (P0156-P0161) ---
    "P0156": {
      "causes": [
        "Failed downstream O2 sensor (Bank 2)",
        "Damaged Bank 2 downstream sensor wiring",
        "Exhaust leak near Bank 2 downstream sensor",
        "Heater circuit failure"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0157": {
      "causes": [
        "Failed Bank 2 downstream O2 sensor (signal low)",
        "Exhaust leak Bank 2 downstream",
        "Open signal wire",
        "Lean condition Bank 2"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0158": {
      "causes": [
        "Failed Bank 2 downstream O2 sensor (signal high)",
        "Short to voltage in signal wire",
        "Rich condition Bank 2",
        "Contaminated sensor"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0161": {
      "causes": [
        "Failed Bank 2 downstream O2 sensor heater",
        "Open heater circuit wiring",
        "Blown O2 heater fuse",
        "Failed PCM heater driver"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // --- VVT Bank 2 (P0020-P0021) ---
    "P0020": {
      "causes": [
        "Failed VVT oil control solenoid (Bank 2 intake)",
        "Damaged Bank 2 VVT solenoid wiring",
        "Low oil pressure to Bank 2 VVT",
        "Sludged oil passages Bank 2"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0021": {
      "causes": [
        "Stuck-open Bank 2 intake VVT solenoid (cam over-advanced)",
        "Sludged oil in Bank 2 cam phaser",
        "Worn timing components Bank 2",
        "Low oil pressure"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // --- Transmission range sensor (P0706) ---
    "P0706": {
      "causes": [
        "Misadjusted transmission range sensor / PRNDL switch",
        "Worn or damaged range sensor detent mechanism",
        "Dirty or contaminated range sensor contacts",
        "Transmission internal shift linkage wear"
      ],
      "severity": "warning",
      "category": "Drive unit"
    },

    // --- Gear incorrect ratio (P0731-P0735) ---
    "P0731": {
      "causes": [
        "Slipping first-gear clutch pack",
        "Low or contaminated transmission fluid",
        "Failed shift or pressure control solenoid",
        "Worn friction elements"
      ],
      "severity": "critical",
      "category": "Drive unit"
    },
    "P0732": {
      "causes": [
        "Slipping second-gear clutch pack",
        "Failed shift solenoid affecting 2nd gear",
        "Low transmission fluid",
        "Worn second-gear friction elements"
      ],
      "severity": "critical",
      "category": "Drive unit"
    },
    "P0733": {
      "causes": [
        "Slipping third-gear clutch pack",
        "Failed shift solenoid affecting 3rd gear",
        "Low or contaminated fluid",
        "Worn third-gear friction elements"
      ],
      "severity": "critical",
      "category": "Drive unit"
    },
    "P0734": {
      "causes": [
        "Slipping fourth-gear clutch pack",
        "Failed shift solenoid affecting 4th gear",
        "Low fluid",
        "Worn fourth-gear friction elements"
      ],
      "severity": "critical",
      "category": "Drive unit"
    },

    // --- Transmission component slipping (P0894) ---
    "P0894": {
      "causes": [
        "Low or contaminated transmission fluid",
        "Worn clutch packs or friction elements",
        "Failed pressure control solenoid",
        "Internal valve body wear",
        "Failed torque converter"
      ],
      "severity": "critical",
      "category": "Drive unit"
    },

    // --- TCM power (P0882-P0883) ---
    "P0882": {
      "causes": [
        "Low battery voltage reaching TCM",
        "Failing 12V battery or weak alternator",
        "High resistance in TCM power supply wiring",
        "Failed TCM power relay"
      ],
      "severity": "warning",
      "category": "Drive unit"
    },

    // --- Cylinder deactivation (P3400+) ---
    "P3400": {
      "causes": [
        "Failed cylinder deactivation solenoid (Bank 1)",
        "Low oil pressure preventing deactivation operation",
        "Sludged oil passages in deactivation actuators",
        "Failed lifter (collapsed or stuck) in deactivation cylinder",
        "Damaged deactivation solenoid wiring"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P3401": {
      "causes": [
        "Open or shorted cylinder 1 deactivation solenoid circuit",
        "Failed deactivation solenoid",
        "Damaged wiring to solenoid",
        "Failed PCM driver"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P3425": {
      "causes": [
        "Failed cylinder 4 deactivation solenoid circuit",
        "Open or shorted wiring to solenoid",
        "Low oil pressure to deactivation system",
        "Stuck lifter in cylinder 4"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // --- Transmission fluid pressure sensors (P0840-P0848) ---
    "P0840": {
      "causes": [
        "Failed transmission fluid pressure sensor 'A'",
        "Low transmission fluid",
        "Damaged TFP sensor wiring / connector",
        "Internal transmission hydraulic circuit issue"
      ],
      "severity": "warning",
      "category": "Drive unit"
    },
    "P0841": {
      "causes": [
        "Transmission fluid pressure sensor 'A' out of range",
        "Low or contaminated fluid causing pressure anomaly",
        "Failed pressure sensor",
        "Valve body hydraulic circuit fault"
      ],
      "severity": "warning",
      "category": "Drive unit"
    },

    // --- O2 sensor circuit / no activity extended ---
    "P0154": {
      "causes": [
        "Failed Bank 2 upstream O2 sensor (no activity)",
        "Open signal wire Bank 2 upstream",
        "Heater circuit failure preventing warm-up",
        "Exhaust leak near Bank 2 upstream sensor"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // --- Network / CAN (P0 not applicable; U-codes are primary but these help) ---
    // Generic throttle actuator system fault
    "P2176": {
      "causes": [
        "Throttle idle position not learned (after battery disconnect or replacement)",
        "Carbon buildup on throttle body preventing full close",
        "Failed throttle body",
        "PCM requires throttle relearn procedure"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // --- Post-cat fuel trim Bank 2 (P2098-P2099) ---
    "P2098": {
      "causes": [
        "Failed Bank 2 catalytic converter",
        "Exhaust leak near Bank 2 downstream sensor",
        "Failed Bank 2 downstream O2 sensor",
        "Lean condition on Bank 2"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P2099": {
      "causes": [
        "Rich condition persisting post-catalyst Bank 2",
        "Failed Bank 2 downstream O2 sensor",
        "Leaking Bank 2 injector(s)",
        "Failed Bank 2 catalyst"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // --- Cooling system performance P2181 ---
    "P2181": {
      "causes": [
        "Stuck-open thermostat (engine not reaching operating temp)",
        "Stuck-closed thermostat (engine overheating)",
        "Failed ECT sensor",
        "Low coolant level"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // --- Air leak / intake P2279-P2282 ---
    "P2279": {
      "causes": [
        "Cracked or disconnected intake air duct (post-MAF)",
        "Loose throttle body air inlet hose",
        "Failed intake boot or coupling",
        "Torn air filter housing seal"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // --- Transmission TCC intermittent (P0744) ---
    "P0744": {
      "causes": [
        "Worn TCC clutch (intermittent slip / lockup)",
        "Low transmission fluid causing TCC engagement issues",
        "Intermittently failing TCC solenoid",
        "Contaminated fluid"
      ],
      "severity": "warning",
      "category": "Drive unit"
    },

    // --- Ignition coil primary control (P2300-P2301) ---
    "P2300": {
      "causes": [
        "Short to ground in coil 'A' primary control circuit",
        "Failed ignition coil 'A' (internal short)",
        "Damaged coil driver wiring",
        "Failed PCM ignition output"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },
    "P2301": {
      "causes": [
        "Open or short to voltage in coil 'A' primary control circuit",
        "Failed ignition coil 'A'",
        "Damaged coil wiring harness",
        "Failed PCM ignition output driver"
      ],
      "severity": "critical",
      "category": "1.4L engine"
    },

    // --- Crankshaft position variation not learned (P0315) ---
    "P0315": {
      "causes": [
        "CKP system variation not learned after PCM replacement or relearn needed",
        "Fluctuating or unstable crankshaft reluctor ring",
        "Flywheel or flexplate run-out (warped)",
        "Failing crankshaft position sensor causing incomplete learn"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // --- Misfire with low fuel / startup (P0313, P0316) ---
    "P0313": {
      "causes": [
        "Very low fuel level causing pump starvation",
        "Failing fuel pump amplified at low fuel level",
        "Clogged fuel filter"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P0316": {
      "causes": [
        "Cold start misfire from worn spark plugs",
        "Cold-start fuel delivery issue (cold injectors)",
        "Cylinder compression loss on cold start",
        "Flooded cylinder from leaking injector"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // --- O2 sensor signal extended (P2197-P2198) ---
    "P2197": {
      "causes": [
        "Failed Bank 2 upstream O2 sensor stuck lean",
        "Vacuum leak Bank 2 side",
        "Exhaust leak near Bank 2 upstream sensor",
        "Fuel delivery failure Bank 2"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P2198": {
      "causes": [
        "Failed Bank 2 upstream O2 sensor stuck rich",
        "Leaking Bank 2 injector(s)",
        "Contaminated Bank 2 O2 sensor",
        "EVAP purge forcing rich mixture"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // =========================================================================
    // VOLT-SPECIFIC CODES
    // Sources: GM TIS / service information, NHTSA TSB database, gm-volt.com
    // diagnostic threads, repairpal.com, yourmechanic.com, mychevyvolt.com,
    // engine-codes.com, autocodes.com. All causes corroborated by >= 2 sources.
    // =========================================================================

    // -------------------------------------------------------------------------
    // HV Battery - isolation faults (critical triad on Gen 1/2 Volt)
    // -------------------------------------------------------------------------
    "P0AA6": {
      "causes": [
        "Coolant intrusion into HV battery pack via leaking heater element (TSB PIC5920)",
        "Conductive coolant from tap-water top-off instead of required deionized water",
        "Failed battery heater element creating conductive path to pack case",
        "Damaged or chafed HV wiring harness shorting to chassis",
        "Failed Power Inverter Module (PIM) internal isolation breakdown"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P0DAA": {
      "causes": [
        "Coolant intrusion into HV battery pack during charging (TSB PIC5920)",
        "Conductive coolant (tap water) in battery thermal loop",
        "Failed battery heater element allowing coolant contact with HV bus",
        "OBCM HV isolation degraded internally"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P1AF0": {
      "causes": [
        "HV isolation breakdown at DMCM1 - part of P1AF0/P1AF2/P1E22 triad",
        "Coolant intrusion in HV battery or drive-unit side",
        "Chafed or damaged HV cable between battery and inverter",
        "Failed Power Inverter Module (PIM) with internal isolation loss"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P1AF2": {
      "causes": [
        "HV isolation breakdown detected by Drive Motor Control Module 2",
        "Coolant leak contacting HV conductors - typically battery heater side",
        "Damaged HV wiring on Motor B (generator) circuit",
        "Failed or wet battery junction block connector"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P1E22": {
      "causes": [
        "HV isolation lost at Pump Inverter Module (PIM) - part of P1AF0/P1AF2/P1E22 triad",
        "Coolant intrusion reaching PIM HV connections",
        "Damaged PIM HV cable or connector",
        "PIM internal failure requiring replacement"
      ],
      "severity": "critical",
      "category": "Drive unit"
    },
    "P1F0E": {
      "causes": [
        "Coolant intrusion causing HV isolation loss (2011-2013 only; P0DAA covers 2014+)",
        "Battery heater element failure allowing coolant to contact HV bus",
        "Conductive coolant in battery thermal system",
        "Chafed HV wiring in underbody harness"
      ],
      "severity": "critical",
      "category": "HV battery"
    },

    // -------------------------------------------------------------------------
    // HV Battery - voltage / contactor / cell health
    // -------------------------------------------------------------------------
    "P0AA1": {
      "causes": [
        "Main positive contactor welded or stuck closed from inrush event",
        "Failed precharge circuit allowing full voltage across unprecharaged contacts",
        "Contact welding from excessive current during prior fault",
        "BECM contactor monitoring circuit false reading"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P0AA4": {
      "causes": [
        "Main negative contactor welded or stuck closed",
        "Inrush event welding contactor contacts",
        "Failed precharge resistor allowing high inrush current",
        "BECM contactor sense circuit fault"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P0A7A": {
      "causes": [
        "Blocked or clogged battery cooling circuit passages",
        "Failed battery coolant pump (low or no flow)",
        "Low battery coolant level in surge tank",
        "Failed coolant control valve stuck closed",
        "Air pocket trapped in battery cooling circuit"
      ],
      "severity": "warning",
      "category": "HV battery"
    },
    "P0A7F": {
      "causes": [
        "Battery pack capacity degradation from age and high-mileage cycling",
        "Several weak or failed individual cell modules inside the pack",
        "Battery pack operated repeatedly at temperature extremes",
        "High cell triplet voltage spread indicating imbalanced cells"
      ],
      "severity": "warning",
      "category": "HV battery"
    },
    "P0A80": {
      "causes": [
        "Severe HV battery pack capacity loss below minimum acceptable threshold",
        "Multiple failed or degraded cell modules inside the pack",
        "Battery pack age beyond service life (common >100k miles Gen 1)",
        "Repeated thermal stress events shortening cell life"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P0AFA": {
      "causes": [
        "Weak or failed 12V auxiliary battery causing low HV system voltage reading",
        "Loose or corroded negative HV cable at battery junction block",
        "Faulty HV battery main negative contactor",
        "Low battery state-of-charge from extended storage"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P0AF8": {
      "causes": [
        "Loose or corroded HV negative cable between battery and drive unit",
        "Weak HV battery negative contactor contact resistance",
        "Corroded battery junction block HV terminal",
        "Failed BECM HV voltage sense circuit"
      ],
      "severity": "warning",
      "category": "HV battery"
    },
    "P0BBD": {
      "causes": [
        "High voltage spread between cell triplets - weak cell group(s) present",
        "One or more cell modules with elevated internal resistance",
        "Battery pack age with uneven cell degradation",
        "Recent deep discharge or overcharge event stressing cells"
      ],
      "severity": "warning",
      "category": "HV battery"
    },
    "P0BBE": {
      "causes": [
        "Cell triplet delta voltage exceeding 0.3V threshold (Gen 1 Volt specific)",
        "Weak or failing cell module(s) in the 48-module string",
        "Battery pack thermal imbalance causing uneven charge distribution",
        "Degraded battery interconnect causing resistance mismatch"
      ],
      "severity": "warning",
      "category": "HV battery"
    },
    "P0BCD": {
      "causes": [
        "HV battery pack capacity below minimum performance threshold",
        "Multiple cell modules degraded beyond calibration limit",
        "Pack-level capacity loss from high cycling and age",
        "Battery management calibration requiring update"
      ],
      "severity": "warning",
      "category": "HV battery"
    },
    "P0AE6": {
      "causes": [
        "Main positive contactor not opening fully - worn or sticky contacts",
        "BECM contactor driver circuit fault",
        "Corroded contactor terminal causing sticking",
        "Contactor coil winding failure"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P0AE7": {
      "causes": [
        "Main negative contactor not opening fully - worn or sticky contacts",
        "BECM contactor driver circuit fault",
        "Corroded negative contactor terminal",
        "Contactor coil failure preventing full release"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P0AE2": {
      "causes": [
        "HV precharge contactor welded or stuck closed after precharge cycle",
        "Contactor contact welding from prior fault",
        "Failed contactor spring allowing contacts to stay closed",
        "BECM precharge monitoring circuit fault"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P0AE3": {
      "causes": [
        "HV precharge contactor stuck open - precharge not completing",
        "Failed precharge contactor coil open",
        "Blown precharge fuse",
        "BECM precharge driver circuit fault"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P0AE8": {
      "causes": [
        "Precharge contactor performance fault - incomplete precharge cycle",
        "High-resistance precharge resistor slowing precharge",
        "Partially failed precharge contactor",
        "BECM precharge timing calibration issue"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P0A94": {
      "causes": [
        "Failed DC/DC converter module (converts HV pack voltage to 12V)",
        "Overloaded 12V system from excessive accessory draw",
        "Corroded or loose HV input cable to DC/DC converter",
        "Failed DC/DC converter control signal from HPCM2"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P0A95": {
      "causes": [
        "Blown HV service fuse (typically after a short-circuit event)",
        "HV isolation fault leading to over-current blowing the fuse",
        "Damaged HV wiring causing short circuit",
        "Failed contactor allowing uncontrolled current flow"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P0A96": {
      "causes": [
        "Precharge circuit fault - unable to precharge HV bus",
        "Blown precharge fuse",
        "Failed precharge resistor (open circuit)",
        "Precharge contactor not closing on command"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P0A89": {
      "causes": [
        "HV battery contactor circuit performance fault",
        "Worn or high-resistance main contactor contacts",
        "BECM contactor drive circuit fault",
        "Contactor coil partially open"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P0AD9": {
      "causes": [
        "HV positive contactor control circuit open or shorted",
        "Failed contactor coil or wiring open",
        "BECM driver circuit fault for positive contactor",
        "Corroded contactor control connector"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P0ADD": {
      "causes": [
        "HV negative contactor control circuit open or shorted",
        "Failed contactor coil on negative side",
        "BECM driver circuit fault for negative contactor",
        "Corroded negative contactor control connector"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P0A0A": {
      "causes": [
        "HV system interlock open - manual service disconnect not fully seated",
        "Service plug/disconnect not properly installed after service",
        "Damaged HV interlock wiring in battery service loop",
        "Corroded interlock connector causing intermittent fault"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P0AF0": {
      "causes": [
        "HV battery voltage above threshold during regen or charging",
        "Strong regen event overcharging pack near 100% SOC",
        "Charging system overvoltage from OBCM fault",
        "Cell imbalance allowing local over-voltage"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P0AF1": {
      "causes": [
        "HV battery over-current during high power demand",
        "Aggressive acceleration exceeding pack current limit",
        "Cell degradation increasing internal resistance causing over-current",
        "BECM current sensor fault"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P0AF2": {
      "causes": [
        "Battery monitor system fault in BECM",
        "BECM internal cell monitoring IC failure",
        "Battery cell interconnect fault affecting monitoring",
        "BECM firmware issue requiring reprogramming"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P0AFC": {
      "causes": [
        "HV battery system over-voltage detected",
        "OBCM charging voltage too high",
        "Regen braking near full SOC causing overvoltage",
        "BECM cell voltage measurement fault"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P0AFD": {
      "causes": [
        "HV battery system under-voltage detected",
        "Battery pack deeply discharged beyond minimum SOC",
        "High internal resistance causing voltage sag under load",
        "BECM voltage measurement fault"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P0AFE": {
      "causes": [
        "Battery pack overheated from aggressive charging in hot ambient conditions",
        "Failed battery cooling system (pump, valve, or radiator)",
        "Coolant leak reducing cooling capacity",
        "Extended high-power driving without adequate cooling"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P0A7B": {
      "causes": [
        "HV battery pack voltage out of expected operating range",
        "Failed or weak cell module groups causing pack voltage sag",
        "Contactor issue causing voltage measurement discrepancy",
        "BECM voltage sense circuit fault"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P0A7D": {
      "causes": [
        "HV battery state-of-charge critically low",
        "Battery pack discharged below minimum SOC",
        "Extended driving in charge-sustaining mode with degraded pack",
        "BECM SOC calibration drift"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P0A7E": {
      "causes": [
        "HV battery overcharged - pack voltage exceeded maximum limit",
        "Charging system overvoltage from OBCM fault",
        "BECM charge control failure",
        "Cell imbalance causing weak cells to overcharge"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P0A88": {
      "causes": [
        "Battery thermal management system performance fault",
        "Battery coolant pump failed or low flow",
        "Coolant circuit blockage restricting flow",
        "Low coolant level reducing cooling capacity"
      ],
      "severity": "warning",
      "category": "HV battery"
    },
    "P0A1F": {
      "causes": [
        "Battery Energy Control Module (BECM) internal fault",
        "BECM internal memory or processor error",
        "Weak 12V supply causing BECM brownout",
        "BECM replacement required"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P0BFB": {
      "causes": [
        "Battery pack measurement system fault in BECM",
        "Failed cell monitoring IC on battery measurement board",
        "Communication fault between BECM and cell monitoring boards",
        "Battery harness connector fault inside pack"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P0C50": {
      "causes": [
        "Battery pack current sensor circuit fault",
        "Failed or out-of-calibration pack current sensor in BECM",
        "BECM internal measurement fault",
        "Wiring fault to pack current sensor shunt"
      ],
      "severity": "warning",
      "category": "HV battery"
    },
    "P0C71": {
      "causes": [
        "HV isolation lost from battery pack during operation",
        "Coolant intrusion into HV battery system",
        "Damaged HV wiring causing ground fault",
        "Failed BECM isolation monitoring circuit"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P1E65": {
      "causes": [
        "Cell group 58 high internal resistance or failure - requires pack replacement",
        "Localized thermal damage to specific cell module area",
        "Interconnect failure on cell group 58 section of battery string",
        "Cell group tab corrosion from moisture intrusion"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P1FFE": {
      "causes": [
        "Low coolant level in HV battery surge tank (top off with deionized water only)",
        "Coolant leak in battery thermal system - hose, fitting, or radiator",
        "Failed coolant level sensor giving false low reading",
        "Air pocket in cooling system from improper fill procedure"
      ],
      "severity": "warning",
      "category": "HV battery"
    },
    "P1FFF": {
      "causes": [
        "BECM isolation self-test failure from moisture or contamination",
        "Weak HV battery causing self-test voltage out of range",
        "BECM internal fault requiring replacement",
        "Coolant intrusion affecting isolation measurement circuit"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P1EC4": {
      "causes": [
        "Battery pack heater transistor stuck off - no pack heating in cold weather",
        "Failed BECM heater driver transistor",
        "Open circuit in battery heater element wiring",
        "Battery heater element itself failed open"
      ],
      "severity": "warning",
      "category": "HV battery"
    },
    "P1EC5": {
      "causes": [
        "Battery pack heater transistor stuck on - uncontrolled heating risk",
        "Shorted heater transistor in BECM",
        "Heater control circuit short to power",
        "BECM internal fault"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P1EC6": {
      "causes": [
        "Battery pack heater assembly failed - heater element or thermal fuse",
        "Coolant leak in heater circuit reducing heating performance",
        "Heater element calcification or scale buildup reducing heat transfer",
        "BECM heater control circuit fault"
      ],
      "severity": "warning",
      "category": "HV battery"
    },
    "P1EC3": {
      "causes": [
        "Battery pack heater transistor control circuit fault",
        "BECM heater driver transistor failure",
        "Open wiring in heater control circuit",
        "Failed heater transistor gate circuit"
      ],
      "severity": "warning",
      "category": "HV battery"
    },
    "P1EC0": {
      "causes": [
        "HV battery system contactors stuck open (Type A fault)",
        "Contactor coil failure preventing closure",
        "BECM contactor driver fault",
        "Open circuit in contactor power supply"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P1EBD": {
      "causes": [
        "Battery disconnect relay stuck open preventing charge circuit engagement",
        "Failed battery junction block relay",
        "Corroded or open relay control circuit wiring",
        "BECM driver circuit fault for charge contactor"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P1F58": {
      "causes": [
        "Battery coolant control valve position sensor out of range",
        "Sticking or failed coolant control valve (common after extended service)",
        "Valve actuator gear stripped or motor failed",
        "Position sensor wiring fault"
      ],
      "severity": "warning",
      "category": "HV battery"
    },
    "P0CE0": {
      "causes": [
        "Failed battery coolant control valve (3-way mixing valve)",
        "Valve actuator motor failed or connector loose",
        "Valve mechanically stuck from scale or debris",
        "BECM coolant valve control circuit fault"
      ],
      "severity": "warning",
      "category": "HV battery"
    },
    "P1FFB": {
      "causes": [
        "HV battery coolant level sensor circuit fault",
        "Failed coolant level sensor inside battery surge tank",
        "Open or shorted sensor wiring",
        "BECM coolant level input circuit fault"
      ],
      "severity": "warning",
      "category": "HV battery"
    },

    // -------------------------------------------------------------------------
    // HPCM/BECM wrapper codes
    // -------------------------------------------------------------------------
    "P0AC4": {
      "causes": [
        "HPCM1 MIL request wrapper - always check co-set codes for root cause",
        "Commonly co-set with HV isolation faults (P0AA6, P1AF0 family)",
        "BECM fault triggering HPCM1 MIL request",
        "Drive motor control module fault escalating to HPCM1"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P1E00": {
      "causes": [
        "HPCM2 MIL request wrapper - root cause is in co-set codes",
        "Commonly set with charging faults (P1EE6, P1EDC, P0DAA)",
        "BECM-reported fault escalating through HPCM2",
        "Drive unit or PIM fault triggering HPCM2 MIL"
      ],
      "severity": "critical",
      "category": "HV battery"
    },
    "P1AEE": {
      "causes": [
        "DMCM1 detecting HV battery over-voltage during regen (TSB PIC6285B)",
        "Cell group imbalance causing momentary over-voltage on regen",
        "BECM calibration issue - reflash recommended before hardware replacement",
        "Weak cell groups allowing over-voltage on stronger groups during regen"
      ],
      "severity": "warning",
      "category": "HV battery"
    },
    "P1AEF": {
      "causes": [
        "DMCM2 detecting HV battery over-voltage on generator side (TSB PIC6285B)",
        "Cell imbalance and regen over-voltage",
        "BECM calibration requiring update",
        "DMCM2 internal fault"
      ],
      "severity": "warning",
      "category": "HV battery"
    },

    // -------------------------------------------------------------------------
    // Drive unit / inverter / motor
    // -------------------------------------------------------------------------
    "P07A3": {
      "causes": [
        "Clutch 1 stuck engaged in Voltec 5ET50 drive unit (GM PIC6244D)",
        "Contaminated or degraded drive unit fluid causing clutch sticking",
        "Failed shift fork or actuator in drive unit",
        "Internal drive unit mechanical wear requiring replacement"
      ],
      "severity": "critical",
      "category": "Drive unit"
    },
    "P3260": {
      "causes": [
        "PIM (Pump Inverter Module) internal fault causing limp-mode on T6 Volt",
        "PIM software/calibration issue - reflash before replacement",
        "PIM overheating from inadequate cooling",
        "Failed PIM power stage requiring module replacement"
      ],
      "severity": "critical",
      "category": "Drive unit"
    },
    "P16E0": {
      "causes": [
        "Engine not producing commanded torque - stale fuel after long EV-only use",
        "Spark plug fouling from infrequent engine operation (run Volt maintenance mode)",
        "Engine misfire reducing available torque below threshold",
        "ECM/HPCM torque request mismatch from sensor fault"
      ],
      "severity": "warning",
      "category": "Drive unit"
    },
    "P0A93": {
      "causes": [
        "Drive motor inverter cooling pump failed or low flow",
        "Coolant level low in drive unit cooling circuit",
        "Blocked cooling passages in inverter heat exchanger",
        "Failed drive unit coolant pump (separate from battery cooling pump)"
      ],
      "severity": "warning",
      "category": "Drive unit"
    },
    "P0A0F": {
      "causes": [
        "Engine failed to start in hybrid mode - drive motor unable to crank engine",
        "Drive Motor Control Module fault preventing motor-start",
        "Low HV battery SOC preventing engine start attempt",
        "Mechanical binding in engine preventing motor-start"
      ],
      "severity": "critical",
      "category": "Drive unit"
    },
    "P0AB9": {
      "causes": [
        "Engine failed to start via drive motor - DMCM or HV fault present",
        "HV battery SOC too low for engine start attempt",
        "Drive motor control fault preventing crank",
        "Mechanical engine binding preventing motor-start"
      ],
      "severity": "critical",
      "category": "Drive unit"
    },
    "P079A": {
      "causes": [
        "Friction element A slip detected in 5ET50 drive unit",
        "Low or degraded drive unit fluid",
        "Worn clutch pack in friction element A",
        "Drive unit fluid leak reducing hydraulic pressure"
      ],
      "severity": "critical",
      "category": "Drive unit"
    },
    "P079B": {
      "causes": [
        "Friction element B slip detected in 5ET50 drive unit",
        "Low or degraded drive unit fluid",
        "Worn clutch pack element B",
        "Internal drive unit hydraulic leak"
      ],
      "severity": "critical",
      "category": "Drive unit"
    },
    "P079C": {
      "causes": [
        "Friction element C slip detected in 5ET50 drive unit",
        "Degraded drive unit fluid",
        "Worn clutch pack element C",
        "Valve body fault reducing element C pressure"
      ],
      "severity": "critical",
      "category": "Drive unit"
    },
    "P07A5": {
      "causes": [
        "Friction element B stuck engaged in Voltec drive unit",
        "Stuck valve in drive unit valve body",
        "Failed shift actuator or fork",
        "Drive unit internal mechanical fault"
      ],
      "severity": "critical",
      "category": "Drive unit"
    },
    "P07A7": {
      "causes": [
        "Friction element C performance fault or stuck off in Voltec drive unit",
        "Degraded drive unit fluid reducing clutch pressure",
        "Valve body fault on element C circuit",
        "Worn friction element C"
      ],
      "severity": "critical",
      "category": "Drive unit"
    },
    "P07B2": {
      "causes": [
        "Park-by-wire park position sensor circuit open (Volt electric park actuator)",
        "Failed park actuator position sensor",
        "Damaged park actuator wiring",
        "Park actuator assembly failure"
      ],
      "severity": "critical",
      "category": "Drive unit"
    },
    "P2797": {
      "causes": [
        "Auxiliary transmission fluid pump performance fault",
        "Failed electric transmission oil pump (drive unit lubrication pump)",
        "Low or aerated drive unit fluid",
        "PIM pump inverter fault (P1E22 may co-set)"
      ],
      "severity": "critical",
      "category": "Drive unit"
    },
    "P0B00": {
      "causes": [
        "Drive unit oil pump motor phase U current fault in PIM",
        "Failed PIM phase U IGBT",
        "Phase U current sensor fault in PIM",
        "PIM wiring fault to oil pump motor"
      ],
      "severity": "critical",
      "category": "Drive unit"
    },
    "P0B27": {
      "causes": [
        "Drive Motor B (generator) inverter internal failure",
        "Generator inverter overtemperature from cooling system fault",
        "Generator phase current sensor fault",
        "Generator inverter IGBT degradation"
      ],
      "severity": "critical",
      "category": "Drive unit"
    },
    "P1E18": {
      "causes": [
        "PIM 5V reference circuit fault",
        "PIM internal power supply failure",
        "Wiring fault to PIM sensor reference circuit",
        "Failed PIM module"
      ],
      "severity": "critical",
      "category": "Drive unit"
    },
    "P1E1B": {
      "causes": [
        "PIM HV isolation sensing performance fault",
        "PIM internal isolation monitor circuit fault",
        "HV leak path to PIM housing",
        "Failed PIM requiring replacement"
      ],
      "severity": "critical",
      "category": "Drive unit"
    },
    "P1E27": {
      "causes": [
        "Auxiliary transmission fluid pump module HV voltage too high",
        "HV pack overvoltage condition reaching PIM",
        "PIM HV input protection fault",
        "Cell imbalance causing transient overvoltage at PIM"
      ],
      "severity": "critical",
      "category": "Drive unit"
    },
    "P1E38": {
      "causes": [
        "Auxiliary transmission oil pump motor inverter supply voltage open",
        "Failed HV relay supplying PIM",
        "Open circuit in HV feed to PIM",
        "PIM HV connector fault"
      ],
      "severity": "critical",
      "category": "Drive unit"
    },
    "P1E39": {
      "causes": [
        "Auxiliary transmission oil pump inverter high current condition",
        "Seized or stalled pump motor causing over-current",
        "PIM IGBT failure causing shoot-through current",
        "Foreign material jamming oil pump"
      ],
      "severity": "critical",
      "category": "Drive unit"
    },

    // -------------------------------------------------------------------------
    // Charging system (OBCM / J1772 / charge port)
    // -------------------------------------------------------------------------
    "P1E04": {
      "causes": [
        "HPCM2 commanded HV charging shutdown due to critical fault",
        "Co-set isolation fault (P0DAA or P1F0E) triggering charge shutdown",
        "OBCM internal fault detected during active charging session",
        "Faulty J1772 charge port pilot signal communication"
      ],
      "severity": "critical",
      "category": "Charging"
    },
    "P1E05": {
      "causes": [
        "Charge session interrupted - EVSE disconnected or circuit tripped",
        "OBCM fault causing charge shutdown mid-session",
        "HV battery thermal limit stopping charge mid-session",
        "Grid power interruption"
      ],
      "severity": "warning",
      "category": "Charging"
    },
    "P1EE6": {
      "causes": [
        "EVSE not supplying AC power - outlet or circuit breaker issue",
        "Damaged J1772 charge cable or connector pins",
        "Charge port latch not fully engaged preventing EVSE handshake",
        "OBCM AC input detection circuit fault",
        "Faulty Level 1/2 EVSE unit"
      ],
      "severity": "warning",
      "category": "Charging"
    },
    "P1EDC": {
      "causes": [
        "Battery charger converter input voltage sensor 2 performance fault",
        "HPCM2 software issue - reflash with latest calibration first",
        "OBCM internal voltage sensing circuit fault",
        "Weak or unstable 12V supply to OBCM"
      ],
      "severity": "warning",
      "category": "Charging"
    },
    "P1EDD": {
      "causes": [
        "Battery charger converter input voltage sensor 2 reading high",
        "HPCM2 calibration issue - TSB recommends reflash before OBCM replacement",
        "OBCM internal fault with voltage sense circuit",
        "Intermittent HV connection at OBCM input"
      ],
      "severity": "warning",
      "category": "Charging"
    },
    "P0CD2": {
      "causes": [
        "Charge port door latch actuator frozen or binding in cold weather",
        "Ice accumulation around charge port door mechanism",
        "Failed charge port door latch actuator motor",
        "Debris blocking charge port door movement"
      ],
      "severity": "info",
      "category": "Charging"
    },
    "P0CCC": {
      "causes": [
        "Charge port door position sensor circuit fault",
        "Damaged or disconnected charge port door sensor wiring",
        "Failed charge port door position sensor",
        "Water intrusion in charge port door sensor connector"
      ],
      "severity": "info",
      "category": "Charging"
    },
    "P0CCF": {
      "causes": [
        "Charge port door position sensor circuit high voltage",
        "Short to voltage in sensor signal wire",
        "Failed charge port door sensor",
        "Wiring harness damage near charge port"
      ],
      "severity": "info",
      "category": "Charging"
    },
    "P0CCE": {
      "causes": [
        "Charge port door position sensor circuit low voltage",
        "Open or shorted sensor signal wire",
        "Failed charge port door sensor",
        "Corroded sensor connector"
      ],
      "severity": "info",
      "category": "Charging"
    },
    "P0D26": {
      "causes": [
        "Dirty or corroded J1772 charge port contacts slowing precharge",
        "Weak 12V battery reducing OBCM precharge control performance",
        "Faulty EVSE with slow pilot signal response",
        "OBCM precharge circuit degradation"
      ],
      "severity": "warning",
      "category": "Charging"
    },
    "P0D5C": {
      "causes": [
        "OBCM HV output power degraded - precursor to charger failure",
        "OBCM internal switching stage partial failure",
        "HV battery contactor issue during charging limiting power",
        "Overtemperature protection limiting OBCM output"
      ],
      "severity": "warning",
      "category": "Charging"
    },
    "P0D3F": {
      "causes": [
        "OBCM/BCCM AC input voltage sensor circuit low (TSB PIC5803C - reflash first)",
        "Corroded AC input connector at OBCM",
        "Weak 12V supply to OBCM signal circuits",
        "OBCM internal sensor fault requiring replacement"
      ],
      "severity": "warning",
      "category": "Charging"
    },
    "P0D40": {
      "causes": [
        "Battery charger AC input voltage sensor circuit high",
        "Short to voltage in AC input sensor signal circuit",
        "Failed OBCM internal sensor",
        "Wiring fault at OBCM AC input connector"
      ],
      "severity": "warning",
      "category": "Charging"
    },
    "P0D4A": {
      "causes": [
        "Battery charger 14V output current sensor circuit high (often weak 12V battery)",
        "Failing 12V battery causing excessive demand on OBCM 14V output",
        "OBCM internal current sensor fault",
        "High parasitic 12V draw from modules"
      ],
      "severity": "warning",
      "category": "Charging"
    },
    "P0D17": {
      "causes": [
        "Battery charging system HV interlock circuit low - check manual service disconnect",
        "HV service disconnect not fully seated after service",
        "Damaged HV interlock wiring in charging circuit",
        "OBCM interlock monitoring circuit fault"
      ],
      "severity": "critical",
      "category": "Charging"
    },
    "P0D3E": {
      "causes": [
        "Battery charger AC input voltage sensor performance out of range",
        "Fluctuating or weak EVSE supply voltage",
        "OBCM voltage measurement circuit fault",
        "Loose or corroded AC inlet connector"
      ],
      "severity": "warning",
      "category": "Charging"
    },
    "P1EDA": {
      "causes": [
        "Battery charger converter input voltage sensor 1 reading high (Gen 2 Volt)",
        "OBCM internal voltage sensing fault",
        "HV supply transient at OBCM input",
        "HPCM2 calibration issue"
      ],
      "severity": "warning",
      "category": "Charging"
    },
    "P0D08": {
      "causes": [
        "HV battery coolant heater circuit fault",
        "Failed heater element open circuit",
        "Open wiring to battery heater",
        "BECM heater driver fault"
      ],
      "severity": "warning",
      "category": "Climate"
    },
    "P0D0B": {
      "causes": [
        "HV battery coolant heater performance fault",
        "Failed coolant heater element (degraded output)",
        "Low coolant flow through heater circuit",
        "BECM heater control calibration mismatch"
      ],
      "severity": "warning",
      "category": "Climate"
    },

    // -------------------------------------------------------------------------
    // Volt-specific 1.4L / EVAP codes with forum-documented patterns
    // -------------------------------------------------------------------------
    "P0497": {
      "causes": [
        "Overfilled fuel tank collapsing EVAP canister with liquid fuel (TSB PIP4891H)",
        "Clogged EVAP charcoal canister from liquid fuel intrusion after overfill",
        "Failed purge valve stuck closed preventing proper purge flow",
        "Kinked or blocked purge hose"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P2400": {
      "causes": [
        "Corroded LDP (Leak Detection Pump) wiring harness connector in rear underbody",
        "Failed LDP motor or solenoid",
        "Rodent damage to rear EVAP harness (common under Volt body)",
        "Open circuit in LDP control wiring"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P2418": {
      "causes": [
        "Failed EVAP LDP switching valve control circuit",
        "Corroded LDP harness connector (common in northern climates from road salt)",
        "Open or shorted LDP valve wiring",
        "Failed LDP switching valve solenoid"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P2450": {
      "causes": [
        "LDP switching valve stuck or binding - wiring or mechanical",
        "Corroded LDP electrical connector causing intermittent performance",
        "LDP diaphragm torn or deformed",
        "Blocked LDP vent path"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P1516": {
      "causes": [
        "Carbon buildup on throttle body bore and plate (1.4L direct injection issue)",
        "Throttle body actuator motor wear or binding from carbon deposits",
        "Throttle position sensor correlation fault after carbon cleaning",
        "Failed throttle body assembly"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },
    "P1101": {
      "causes": [
        "MAF sensor out of self-test range - dirty or contaminated sensor element",
        "Air intake leak between MAF and throttle body",
        "Carbon buildup on throttle body altering airflow measurement",
        "MAF sensor wiring fault or poor connector contact"
      ],
      "severity": "warning",
      "category": "1.4L engine"
    },

    // -------------------------------------------------------------------------
    // Network / Lost Communication (Volt 12V battery sensitivity)
    // -------------------------------------------------------------------------
    "U0100": {
      "causes": [
        "Weak or dead 12V auxiliary battery causing module brownout (most common Volt cause)",
        "Corroded or loose 12V battery terminal dropping bus voltage",
        "CAN bus wiring fault (open or short in high-speed CAN circuit)",
        "Failed ECM/PCM module"
      ],
      "severity": "critical",
      "category": "Network"
    },
    "U0101": {
      "causes": [
        "Weak 12V battery causing TCM loss of communication during startup",
        "Failed TCM (Transmission Control Module)",
        "CAN bus fault between ECM and TCM",
        "Corroded TCM connector pins"
      ],
      "severity": "critical",
      "category": "Network"
    },
    "U0110": {
      "causes": [
        "Weak 12V battery causing Drive Motor Control Module comm loss",
        "Failed Drive Motor Control Module (DMCM1)",
        "CAN bus wiring fault on hybrid powertrain bus",
        "Corroded DMCM connector"
      ],
      "severity": "critical",
      "category": "Network"
    },
    "U0111": {
      "causes": [
        "Weak 12V battery causing BECM communication loss (very common Volt complaint)",
        "Failed Battery Energy Control Module (BECM)",
        "CAN bus fault on HV battery communication network",
        "Corroded BECM connector or wiring harness damage"
      ],
      "severity": "critical",
      "category": "Network"
    },
    "U0112": {
      "causes": [
        "Weak 12V battery causing BECM-B comm loss",
        "Failed secondary battery energy control module",
        "HV CAN bus fault",
        "Corroded BECM-B module connector"
      ],
      "severity": "critical",
      "category": "Network"
    },
    "U0140": {
      "causes": [
        "Weak or failing 12V battery causing BCM communication loss",
        "Failed Body Control Module (BCM)",
        "Blown fuse in BCM power supply circuit",
        "Corroded BCM ground connection"
      ],
      "severity": "warning",
      "category": "Network"
    },
    "U0293": {
      "causes": [
        "Weak 12V battery causing HPCM communication loss",
        "Failed Hybrid Powertrain Control Module 2 (HPCM2)",
        "CAN bus fault on hybrid powertrain network",
        "Corroded HPCM2 connector"
      ],
      "severity": "critical",
      "category": "Network"
    },
    "U0252": {
      "causes": [
        "Lost comm with charging system module - weak 12V or blown charger fuse",
        "Failed OBCM/BCCM module",
        "CAN bus fault on charging system network",
        "Corroded OBCM connector"
      ],
      "severity": "warning",
      "category": "Network"
    },
    "U0254": {
      "causes": [
        "Lost communication with on-board charger control module",
        "OBCM not powered - check fuse and 12V supply",
        "Failed OBCM module",
        "CAN wiring fault to OBCM"
      ],
      "severity": "warning",
      "category": "Network"
    },
    "U0121": {
      "causes": [
        "Weak 12V battery causing ABS/EBCM communication loss",
        "Failed EBCM (Electronic Brake Control Module)",
        "Blown EBCM fuse",
        "Corroded EBCM connector or damaged wiring"
      ],
      "severity": "critical",
      "category": "Network"
    },
    "U0073": {
      "causes": [
        "CAN bus off condition - bus shorted or terminated incorrectly",
        "Weak 12V battery causing module to hold CAN bus off",
        "Short circuit in CAN H or CAN L wiring",
        "Failed module holding bus in recessive/dominant state"
      ],
      "severity": "critical",
      "category": "Network"
    },
    "U0129": {
      "causes": [
        "Lost communication with EBCM (brake system control module)",
        "Weak 12V battery causing EBCM power dropout",
        "Failed EBCM",
        "Wiring fault or blown fuse in EBCM supply circuit"
      ],
      "severity": "critical",
      "category": "Network"
    },

    // -------------------------------------------------------------------------
    // Body / BCM
    // -------------------------------------------------------------------------
    "B101D": {
      "causes": [
        "BCM internal hardware fault from low 12V voltage event (TSB PIC5943)",
        "Weak 12V battery causing BCM brown-out during module wake sequence",
        "Corroded 12V battery terminals causing voltage drop to BCM",
        "BCM internal failure requiring replacement"
      ],
      "severity": "warning",
      "category": "Body"
    },
    "B1517": {
      "causes": [
        "Low 12V battery voltage detected by BCM",
        "Failing or discharged 12V auxiliary battery",
        "High parasitic drain keeping 12V battery discharged",
        "Failed DC/DC converter not recharging 12V battery from HV pack"
      ],
      "severity": "warning",
      "category": "12V power"
    },
    "B1001": {
      "causes": [
        "SDM configuration mismatch after BCM replacement - incomplete programming",
        "Module replaced without proper VIN programming",
        "Software incompatibility between replaced BCM and existing SDM",
        "Corrupted BCM configuration data"
      ],
      "severity": "warning",
      "category": "SRS"
    },

    // -------------------------------------------------------------------------
    // Brakes / ABS / EBCM - Volt-specific
    // -------------------------------------------------------------------------
    "C0021": {
      "causes": [
        "Failed electric vacuum pump (Gen 1 Volt - pump motor or relay failure)",
        "Vacuum hose leak to electric vacuum pump or brake booster",
        "Failed vacuum check valve allowing vacuum loss",
        "EBCM vacuum sensor circuit fault"
      ],
      "severity": "critical",
      "category": "Brakes"
    },
    "C0035": {
      "causes": [
        "Failed left front wheel speed sensor (connector corrosion is common)",
        "Damaged wheel speed sensor tone ring / reluctor",
        "Chafed wheel speed sensor wiring from suspension movement",
        "Wheel bearing hub failure affecting sensor air gap"
      ],
      "severity": "critical",
      "category": "Brakes"
    },
    "C0040": {
      "causes": [
        "Failed right front wheel speed sensor",
        "Corroded wheel speed sensor connector",
        "Damaged tone ring on right front hub",
        "WSS wiring chafed against suspension component"
      ],
      "severity": "critical",
      "category": "Brakes"
    },
    "C0045": {
      "causes": [
        "Failed left rear wheel speed sensor",
        "Damaged or corroded sensor connector at left rear",
        "Tone ring damage on rear hub",
        "Sensor wiring chafed from road debris"
      ],
      "severity": "critical",
      "category": "Brakes"
    },
    "C0050": {
      "causes": [
        "Failed right rear wheel speed sensor",
        "Corroded WSS connector at right rear",
        "Damaged tone ring on right rear hub",
        "Wiring damage from rear suspension travel"
      ],
      "severity": "critical",
      "category": "Brakes"
    },
    "C012D": {
      "causes": [
        "Failed brake boost pressure sensor in Gen 2 Volt brake-by-wire system",
        "Brake system hydraulic pressure leak",
        "EBCM internal sensor fault",
        "Wiring fault to pressure sensor"
      ],
      "severity": "critical",
      "category": "Brakes"
    },
    "C0561": {
      "causes": [
        "ABS/TCS system disabled after another fault was detected - check co-set codes",
        "Root cause stored as co-set code (fix underlying fault first)",
        "EBCM internal fault disabling stability control",
        "Lost communication fault disabling system"
      ],
      "severity": "warning",
      "category": "Brakes"
    },
    "C1100": {
      "causes": [
        "Electric vacuum pump performance fault (Gen 1 Volt brake booster)",
        "Vacuum pump motor worn or failing",
        "Vacuum hose leak reducing pump output pressure",
        "Failed vacuum pump relay"
      ],
      "severity": "critical",
      "category": "Brakes"
    },
    "C1101": {
      "causes": [
        "Brake booster vacuum sensor performance fault",
        "Vacuum leak at sensor port or booster hose",
        "Failed vacuum sensor",
        "Electric vacuum pump not reaching target vacuum level"
      ],
      "severity": "critical",
      "category": "Brakes"
    },
    "P1B12": {
      "causes": [
        "Regen brake blending fault - EBCM and HPCM torque mismatch",
        "Brake pedal position sensor correlation error",
        "EBCM software version mismatch with HPCM2",
        "Failed brake pedal simulator or pressure sensor"
      ],
      "severity": "critical",
      "category": "Brakes"
    },
    "C0244": {
      "causes": [
        "PWM delivered torque signal fault in Volt regen brake blending",
        "HPCM2 and EBCM regen torque communication mismatch",
        "Failed EBCM",
        "CAN bus fault between EBCM and HPCM"
      ],
      "severity": "critical",
      "category": "Brakes"
    },

    // -------------------------------------------------------------------------
    // TPMS
    // -------------------------------------------------------------------------
    "C0750": {
      "causes": [
        "Failed left front TPMS sensor - battery exhausted or sensor fault",
        "TPMS sensor damaged during tire service",
        "RF signal blockage preventing receiver from reading sensor",
        "TPMS sensor learn procedure needed after tire rotation"
      ],
      "severity": "info",
      "category": "TPMS"
    },
    "C0755": {
      "causes": [
        "Failed right front TPMS sensor",
        "TPMS sensor battery depleted (typical life 5-7 years)",
        "Sensor physically damaged from road hazard",
        "TPMS learn procedure required after service"
      ],
      "severity": "info",
      "category": "TPMS"
    },
    "C0760": {
      "causes": [
        "Failed left rear TPMS sensor",
        "TPMS sensor battery depleted",
        "Sensor damaged during wheel or tire service",
        "TPMS receiver module antenna issue"
      ],
      "severity": "info",
      "category": "TPMS"
    },
    "C0765": {
      "causes": [
        "Failed right rear TPMS sensor",
        "TPMS sensor battery depleted",
        "Physical sensor damage from road hazard",
        "TPMS learn procedure needed after rotation"
      ],
      "severity": "info",
      "category": "TPMS"
    },
    "C0775": {
      "causes": [
        "TPMS sensor learn procedure not completed after rotation or replacement",
        "All four sensors replaced but learn procedure not performed",
        "BCM TPMS configuration not programmed to vehicle",
        "Interference preventing TPMS signal reception during learn"
      ],
      "severity": "info",
      "category": "TPMS"
    },
    "C07A0": {
      "causes": [
        "Left front TPMS sensor battery depleted (end of sensor service life)",
        "Sensor operating at temperature extremes accelerating battery drain",
        "Replacement required - sensor battery is not user-serviceable"
      ],
      "severity": "info",
      "category": "TPMS"
    },
    "C07A5": {
      "causes": [
        "Right front TPMS sensor battery depleted",
        "Sensor service life exceeded",
        "Replacement required"
      ],
      "severity": "info",
      "category": "TPMS"
    },
    "C07B0": {
      "causes": [
        "Left rear TPMS sensor battery depleted",
        "Sensor service life exceeded",
        "Replacement required"
      ],
      "severity": "info",
      "category": "TPMS"
    },
    "C07B5": {
      "causes": [
        "Right rear TPMS sensor battery depleted",
        "Sensor service life exceeded",
        "Replacement required"
      ],
      "severity": "info",
      "category": "TPMS"
    }

  };
}());
