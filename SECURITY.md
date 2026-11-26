# Security Policy

## 1. HID Safety & Privacy Commitments

**Wireless Battery Monitor (Java Edition)** interacts with local USB HID receptors and Windows native device APIs. Because improper HID writes can alter device state or firmware settings, the application follows strict safety boundaries:

- **No telemetry, no accounts, no background tracking:** No user data, device serial numbers, MAC addresses, or usage statistics are ever transmitted over the network.
- **Local storage only:** Preferences (`settings.properties`) and optional battery history (`history/*.tsv`) are stored locally under `%USERPROFILE%\.wireless-battery-java\`.
- **Optional Update Checker:** When manually triggered or explicitly enabled (`autoCheckUpdates`), `UpdateCheckerService` queries only the public GitHub Releases API (`https://api.github.com/repos/kybiy/Wireless-Device-Battery-Monitor/releases`) with no authentication tokens or machine identifiers, caching results for 24 hours.
- **Sanitized Diagnostic Exports:** `DiagnosticBundleExporter` + `DiagnosticSanitizer` run strictly locally, redacting usernames, hostnames, emails, Bluetooth MAC addresses, HID device path serial/container segments, and tokens before generating a ZIP for manual user review.

### Per-Device Protocol Behavior
| Device | Mechanism | What Is Sent / Read |
| --- | --- | --- |
| **QCY T13 ANC** | `OS_PASSIVE_PROPERTY` (SetupAPI / Cfgmgr32) | Strictly passive read of Windows PnP properties (`DEVPKEY_Bluetooth_Battery`, `PKEY_Devices_BatteryLife`, `DEVPKEY_Bluetooth_IsConnected`). No RFCOMM/GATT connections opened. |
| **Delux M800 Pro** | `HID_FEATURE_REPORT_QUERY` | Sends/reads HID Feature Report `0x0C` on `IF 1` (`0xFF00:0x0001`). No mouse DPI, polling rate, or RGB configuration commands. |
| **Akko Verge S9 / S9 Ultra** | `HID_OUTPUT_REPORT_VALIDATED` | Sends read-only battery/state query `0x02 0x12 0x01 ... <CRC16>` on `IF 3` (`0x008C:0x0001`). No audio, EQ, or firmware commands. |
| **GameSir Cyclone 2** | `HID_OUTPUT_REPORT_EXPERIMENTAL` | Sends `0x0F 0x03` (`64` bytes) on XInput `FFF0:0040` because passive `GET_FEATURE` returns `EPIPE` and idle reads emit zero frames. Bytes `[2..63]` are strictly kept `0x00` so LED register selector `0x20` is never written. Protected by **first-use safety notice** and **Read-Only HID Mode** (`readOnlyHidMode`), which blocks all experimental Output Report writes when enabled. |

---

## 2. Reporting a Vulnerability Privately

Please **do not** open public GitHub issues for security vulnerabilities, unsafe HID command discoveries, or installer privilege/path issues.

Instead, report vulnerabilities privately via **GitHub Private Vulnerability Reporting**:

1. Go to the repository's **Security** tab:  
   `https://github.com/kybiy/Wireless-Device-Battery-Monitor/security`
2. Click **Advisories** → **Report a vulnerability**.
3. Include:
   - Affected application version and Windows version (`x64`)
   - Component or driver involved
   - Reproduction steps or proof-of-concept
   - Potential impact (e.g., unsafe HID write, path traversal, sanitizer leak)

### Note for Repository Maintainer (Enabling Private Vulnerability Reporting)
If the **Report a vulnerability** button is not yet visible on GitHub, enable it once in repository settings:
1. Open **Settings** on `kybiy/Wireless-Device-Battery-Monitor`.
2. Navigate to **Code security and analysis** (under *Security*).
3. Click **Enable** next to **Private vulnerability reporting**.
