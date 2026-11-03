package com.xsr.companion.diagnostics;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class DtcManager {

    public enum Severity {
        OK,
        INFO,
        WARNING,
        CRITICAL
    }

    public static class DtcInfo {
        public final int code;
        public final String title;
        public final String description;
        public final String symptom;
        public final String action;
        public final Severity severity;

        public DtcInfo(int code, String title, String description, String symptom, String action, Severity severity) {
            this.code = code;
            this.title = title;
            this.description = description;
            this.symptom = symptom;
            this.action = action;
            this.severity = severity;
        }
    }

    private static final Map<Integer, DtcInfo> DTC_TABLE;

    static {
        Map<Integer, DtcInfo> map = new HashMap<>();

        map.put(12, new DtcInfo(12,
                "Crankshaft Position Sensor",
                "No normal crank pulses received from the pickup coil.",
                "Engine will not start; starter motor cranks without spark.",
                "Check pickup coil connector, wiring harness, and air gap.",
                Severity.CRITICAL));

        map.put(13, new DtcInfo(13,
                "Intake Air Pressure Sensor (Open/Short)",
                "Intake manifold pressure sensor open or short circuit.",
                "Engine starts but runs in fail-safe limp mode; sluggish throttle response.",
                "Inspect MAP sensor harness and connector on throttle body.",
                Severity.WARNING));

        map.put(14, new DtcInfo(14,
                "Intake Air Pressure Sensor Pipe Detached",
                "Vacuum hose detached or clogged between manifold and sensor.",
                "Erratic idle speed, stalls easily under acceleration.",
                "Inspect rubber vacuum hose to intake pressure sensor.",
                Severity.WARNING));

        map.put(15, new DtcInfo(15,
                "Throttle Position Sensor (TPS) Circuit",
                "TPS output voltage abnormal (open or short circuit).",
                "Rough acceleration, poor throttle response, hesitation.",
                "Inspect TPS harness and sensor mounting alignment.",
                Severity.WARNING));

        map.put(16, new DtcInfo(16,
                "Throttle Position Sensor (TPS) Stuck",
                "TPS output signal does not change as engine revs or throttle moves.",
                "Unstable idle, inability to reach full RPM.",
                "Check throttle cable tension and replace TPS if sensor arm is binding.",
                Severity.WARNING));

        map.put(19, new DtcInfo(19,
                "Sidestand Switch Circuit Broken",
                "Sidestand switch input open or disconnected.",
                "Engine cuts off immediately when shifted into gear.",
                "Check sidestand switch plunger, pivot spring, and electrical plug.",
                Severity.CRITICAL));

        map.put(21, new DtcInfo(21,
                "Coolant Temperature Sensor",
                "Engine coolant temp sensor open or short circuit.",
                "Hard cold starting; radiator fan may stay running continuously in fail-safe mode.",
                "Check coolant temperature sensor connector near cylinder head.",
                Severity.WARNING));

        map.put(22, new DtcInfo(22,
                "Intake Air Temperature Sensor",
                "Intake air temperature sensor open or short circuit.",
                "Minor fuel trim inaccuracy; slightly degraded fuel economy.",
                "Inspect air temp sensor plug mounted on airbox.",
                Severity.INFO));

        map.put(24, new DtcInfo(24,
                "O2 Sensor Abnormal Signal",
                "Exhaust oxygen sensor signal irregular or heater circuit faulty.",
                "Increased emissions, black spark plug fouling, slight drop in km/L.",
                "Check O2 sensor wiring near exhaust downpipe.",
                Severity.WARNING));

        map.put(28, new DtcInfo(28,
                "Coolant Over-Temperature",
                "Engine coolant temperature exceeded safe threshold (>115°C).",
                "Severe engine overheating warning; potential cylinder warp risk.",
                "Immediately pull over, key off, check coolant level and radiator fan.",
                Severity.CRITICAL));

        map.put(30, new DtcInfo(30,
                "Lean Angle Sensor Activated (Tip-Over)",
                "Motorcycle tilt angle exceeded 65 degrees (bike fell over).",
                "Ignition and fuel injection automatically cut off for safety.",
                "Upright motorcycle, cycle ignition key OFF then back ON to reset.",
                Severity.CRITICAL));

        map.put(33, new DtcInfo(33,
                "Ignition Coil Primary Circuit",
                "Primary lead to ignition coil open or short circuit.",
                "Engine misfires, backfires, or fails to start completely.",
                "Check ignition coil terminal, spark plug boot, and ground bolt.",
                Severity.CRITICAL));

        map.put(37, new DtcInfo(37,
                "Idle Speed Control (ISC) Valve",
                "ISC stepper motor abnormal or idle air bypass jammed with carbon.",
                "Engine stalls when coming to a stop, or idles higher than 2,000 RPM.",
                "Clean throttle body idle air passage or inspect ISC stepper connector.",
                Severity.WARNING));

        map.put(39, new DtcInfo(39,
                "Fuel Injector Circuit",
                "Fuel injector solenoid open or short circuit.",
                "Engine will not run; no fuel delivery into intake port.",
                "Inspect injector plug and measure resistance (approx 12 ohms).",
                Severity.CRITICAL));

        map.put(42, new DtcInfo(42,
                "Wheel Speed Sensor / Neutral Switch",
                "No speed pulses detected while motorcycle is in motion in gear.",
                "Speedometer reads 0 km/h; gear indicator unavailable.",
                "Check wheel speed sensor cable near front wheel rotor and neutral switch.",
                Severity.WARNING));

        map.put(44, new DtcInfo(44,
                "ECU EEPROM Memory Error",
                "ECU internal non-volatile memory read/write error.",
                "Erratic operation or loss of stored trim adaptations.",
                "Attempt battery disconnect reset (10 min); replace ECU if code persists.",
                Severity.CRITICAL));

        map.put(46, new DtcInfo(46,
                "Charging System / Voltage Abnormal",
                "ECU power supply voltage too low (<10V) or too high (>16V).",
                "Battery drains while riding, or lights flicker abnormally.",
                "Test stator alternator output AC voltage and regulator/rectifier DC output.",
                Severity.CRITICAL));

        map.put(50, new DtcInfo(50,
                "ECU Internal Fault",
                "Faulty ECU microprocessor internal execution.",
                "Engine fails to start or stalls intermittently.",
                "Check ECU main ground connection; dealer ECU replacement required.",
                Severity.CRITICAL));

        map.put(84, new DtcInfo(84,
                "VVA Solenoid Valve Malfunction",
                "Variable Valve Actuation solenoid open or short circuit.",
                "High-RPM power deficit; VVA high-cam profile will not engage above 7,400 RPM.",
                "Inspect VVA solenoid connector on cylinder head cover.",
                Severity.WARNING));

        map.put(96, new DtcInfo(96,
                "Emergency Stop / Killswitch Active",
                "Engine stop switch is in OFF position or killswitch circuit grounded.",
                "Starter will crank but engine will not fire.",
                "Verify handlebar red killswitch is toggled to RUN position.",
                Severity.INFO));

        DTC_TABLE = Collections.unmodifiableMap(map);
    }

    public static DtcInfo lookup(int dtcCode) {
        if (dtcCode == 0) {
            return new DtcInfo(0, "All Systems Healthy", "No active Diagnostic Trouble Codes detected.",
                    "Engine operates within optimal parameters.", "No maintenance action required.", Severity.OK);
        }
        DtcInfo info = DTC_TABLE.get(dtcCode);
        if (info != null) {
            return info;
        }
        return new DtcInfo(dtcCode,
                "Diagnostic Code DTC " + dtcCode,
                "ECU reported trouble code " + dtcCode + ".",
                "Check engine malfunction light may illuminate.",
                "Consult Yamaha XSR 155 workshop service manual for code " + dtcCode + ".",
                Severity.WARNING);
    }
}
