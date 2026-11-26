# Physical Validation Checklist — v1.3.0 (`feat/robustness-v1.3.0`)

Use this checklist on a **Windows 11 x64** PC with physical hardware connected before tagging `v1.3.0`. Automated tests (`mvn clean verify`) do not exercise real USB HID radios, Bluetooth PnP stacks, or live `Shell_TrayWnd` behavior.

**Pre-check:**
- [ ] Run `mvn clean verify` and confirm compilation (`-Xlint`), all JUnit tests, and SpotBugs pass.
- [ ] Package or run the build under test (`mvn exec:java` or packaged `.exe` / portable `.zip`).
- [ ] Keep **Diagnostics** window accessible to verify logs and export a sanitized diagnostic ZIP if any scenario fails.

---

## 14 Physical Validation Scenarios

| # | Scenario | Steps | Expected Result | Pass? |
| --- | --- | --- | --- | --- |
| **1** | **GameSir Cyclone 2 — Normal Mode** | Connect GameSir Cyclone 2 in **XInput mode** (2.4 GHz dongle and/or USB cable). Ensure `Read-Only HID Mode` is **OFF** and `GameSir monitoring` is **ON**. | First-use safety notice appears once (until acknowledged). After accepting, real battery `%` appears; plugging USB cable updates charging state. | [ ] |
| **2** | **GameSir Cyclone 2 — Read-Only HID Mode** | Enable **Read-Only HID Mode** (`readOnlyHidMode=true`) in Settings while GameSir Cyclone 2 is connected. | No `0x0F 0x03` Output Report writes are sent; UI clearly indicates GameSir active querying is paused/blocked by Read-Only mode, while passive/feature-report devices continue working. | [ ] |
| **3** | **Delux M800 Pro — Connected (Wireless)** | Turn on Delux M800 Pro via 2.4 GHz USB dongle (`0x248A:0x5B2F`). | Real battery percentage updates accurately (verify against prior known level) without affecting mouse tracking or DPI. | [ ] |
| **4** | **Delux M800 Pro — Charging** | Plug Delux M800 Pro via USB cable (`0x5B2E` or wired charging). | Charging indicator becomes active; verify behavior when reaching `100%` and unplugging. | [ ] |
| **5** | **Akko Verge S9 / S9 Ultra — Connected** | Power on Akko S9 Ultra with 2.4 GHz dongle (`0x36EB:0x1087`). | Real battery `%` (`1..100%`) displays in headset card, tray, and taskbar widget without disrupting audio/mic. | [ ] |
| **6** | **Akko Verge S9 / S9 Ultra — Off / Asleep** | Turn off the headset while keeping the 2.4 GHz dongle plugged in. | Transitions cleanly to `ASLEEP` / disconnected state, preserving the last known battery reading with age indicator rather than crashing or showing bogus values. | [ ] |
| **7** | **QCY T13 ANC — Connected (Bluetooth)** | Pair and connect **QCY-T13 ANC** via Windows Bluetooth. | Passive Windows PnP reading matches Windows Bluetooth Settings percentage (`DEVPKEY_Bluetooth_Battery` / `PKEY_Devices_BatteryLife`) with A2DP/HFP audio unaffected. | [ ] |
| **8** | **QCY T13 ANC — Disconnected (BT On)** | Power off QCY T13 ANC earbuds while Windows Bluetooth stays enabled. | Device switches to disconnected state cleanly without stale connected status or tray errors. | [ ] |
| **9** | **Windows Bluetooth Radio Turned Off** | Toggle Windows Bluetooth **OFF** in Quick Settings while app is running. | App logs gracefully without uncaught exceptions; USB HID devices continue polling normally. | [ ] |
| **10** | **Automatic Headset Selection** | Connect **both** Akko S9 Ultra and QCY T13 ANC simultaneously; switch Windows **Default Audio Output** between them. | Unified headset card, tray icon, and taskbar widget automatically switch to whichever headset is the active Windows default render endpoint. | [ ] |
| **11** | **Low & Critical Battery Alerts** | Set per-device low/critical thresholds in Settings (e.g., just above current % or test threshold crossing) and test **Suppress alerts while charging**. | Notification fires once on threshold crossing (no spam on repeated polls) and is suppressed when charging if configured. | [ ] |
| **12** | **Local Battery History, Graph, CSV & Autonomy** | Open **Battery History** (`BatteryHistoryDialog`) after accumulating real samples (`>= 3` samples, `>= 30 min`, `>= 3%` drop for autonomy). | Real samples appear separately from simulated samples; discharge chart renders; CSV export succeeds; conservative autonomy estimate appears only when thresholds are met. | [ ] |
| **13** | **Internationalization (`pt-BR` / `en-US`)** | Switch language in Settings between `System`, `Português (Brasil)`, and `English (US)`. | Labels, dialogs, tray tooltips, and status messages reflect the chosen language cleanly without truncated text or missing keys. | [ ] |
| **14** | **Native Widget (`Win+D`, Explorer, DPI) & Eco Mode** | Press `Win+D`, `Alt+Tab`, restart `explorer.exe` in Task Manager, test DPI scaling if applicable, then minimize/hide main window with **Eco Mode** enabled. | Taskbar widget stays visible on `Win+D`, does not overlap fullscreen apps, re-attaches automatically after `explorer.exe` restart, and Eco Mode reduces background polling frequency (`max(min(60, base*2), 30)`s) when hidden. | [ ] |
