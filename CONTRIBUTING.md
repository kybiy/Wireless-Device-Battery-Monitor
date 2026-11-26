# Contributing to Wireless Battery Monitor (Java Edition)

Thank you for your interest in contributing! This project focuses on **safe, predictable, low-overhead battery monitoring** for specific wireless peripherals on Windows x64.

---

## 1. Development Environment Setup

### Prerequisites
- **Windows 10 / 11 x64** (required for Win32 taskbar, SetupAPI, and Core Audio integrations).
- **JDK 17 or newer** (`maven.compiler.release` is `17`; CI verifies builds on JDK 17 and JDK 25).
- **Apache Maven 3.9+** (or NetBeans / IntelliJ / VS Code with Maven integration).
- **Optional (only for `.exe` / `.zip` packaging):** JDK 25 (`jpackage`) + **WiX Toolset 3.14+**.

### Clone & Verify Locally
```powershell
git clone https://github.com/kybiy/Wireless-Device-Battery-Monitor.git
cd Wireless-Device-Battery-Monitor
mvn clean verify
```

`mvn clean verify` runs:
- Strict javac warnings (`-Xlint:all,-serial,-processing`)
- Automated unit, integration, and simulation tests (JUnit 5)
- **SpotBugs** static analysis (`spotbugs-maven-plugin` at `Max` effort / `Medium` threshold, filtered via `spotbugs-exclude.xml`)

To launch the app directly from Maven:
```powershell
mvn exec:java
```

---

## 2. Automated Tests vs. Physical Hardware Validation

- **Automated CI & unit/integration tests (`mvn clean verify`)** test protocol parsers, sanitizers, i18n bundles, local history/autonomy math, update checker logic, driver safety metadata, and simulated device polling **without requiring physical dongles or Bluetooth radios**.
- **Physical hardware checks** require real peripherals on Windows 11 x64. Always test hardware-touching driver changes against real devices using a checklist (see `docs/PHYSICAL_VALIDATION_CHECKLIST_v1.3.0.md`) and never claim physical validation you did not personally observe.

### Using Simulation Mode
You can test UI layouts, taskbar rendering, battery alerts, and headset switching safely without physical hardware by enabling **Simulation Mode** in Settings (or via `SimulationManager` in tests).

---

## 3. Identifying VID/PID & Collecting Safe Diagnostics

1. Open the app's **HID Diagnostics** dialog (`HidDiagnosticsDialog`) — it performs **passive, read-only** USB HID enumeration (`UsagePage`, `Usage`, `Interface`, `VID`, `PID`, caps).
2. To share logs or interface reports in an issue or PR, use **Export Sanitized Diagnostic Bundle (ZIP)** (`DiagnosticBundleExporter` + `DiagnosticSanitizer`).
   - It automatically redacts usernames, hostnames, emails, Bluetooth MAC addresses, HID device path serial/container segments, and tokens.
   - **Always inspect the preview before posting!**

---

## 4. Adding a New Modular `BatteryDevice` Driver

New device support must implement `br.com.wirelessbattery.device.BatteryDevice` and register via `DeviceDriverRegistry`:

1. **Declare `DriverCapabilities` and `ProtocolSafetyInfo`** (`getCapabilities()`):
   - Specify `TransportType` (`USB_HID`, `BLUETOOTH_PNP`, etc.), `supportsChargingDetection`, `supportsSleepDetection`, and `minimumSafePollSeconds` (enforced `>= 5`s).
   - Provide `ProtocolSafetyInfo` with accurate `QueryMechanism`, `strictlyPassive`, `requiresHidOutputWrite`, `allowedInReadOnlyMode`, `requiresFirstUseNotice`, `protocolSummary`, and `referenceUrl`.
2. **Isolate VID/PID matching** (`matchesVidPid(int vid, int pid)` and `getExpectedInterface()`):
   - Never open or write to unrelated HID collections/interfaces.
3. **Never return `null` from `readBattery()`**:
   - Return `DeviceReading.realDisconnected(...)`, `realAsleep(...)`, or `realPendingProtocol(...)` appropriately.
4. **Add JUnit tests** covering packet parsing, idle/malformed frames, boundary values (`< 0` or `> 100`), and `DriverCapabilities` / `Read-Only Mode` compliance.

---

## 5. Strict HID Safety & Privacy Rules

- **NEVER** send experimental HID writes, RGB/LED control bytes, DPI/EQ presets, pairing commands, or firmware update commands.
  - For example, on GameSir Cyclone 2 (`0x0F 0x03`), bytes `[2..63]` must **always** remain `0x00` so LED register selector `0x20` is never written.
- **Respect Read-Only HID Mode (`readOnlyHidMode`)**: drivers requiring `HID_OUTPUT_REPORT_EXPERIMENTAL` (`allowedInReadOnlyMode = false`) must not issue Output Report writes when Read-Only HID Mode is active.
- **NEVER** attach raw personal USB captures (`.pcapng`), unredacted Windows Registry exports, emails, Bluetooth MAC addresses, or device serial numbers to issues or PRs.

---

## 6. Pull Request Workflow & Licensing

1. Fork the repository and create a topic branch (`feat/...` or `fix/...`).
2. Keep changes focused and ensure `mvn clean verify` passes without warnings or SpotBugs issues.
3. Fill out `.github/PULL_REQUEST_TEMPLATE.md` accurately, noting whether physical hardware was tested.
4. All contributions are licensed under the **GNU General Public License v3.0 (GPL-3.0)** ([LICENSE](LICENSE)). Ensure any protocol references respect upstream licenses and cite references in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) without copying incompatible code.
