# Wireless Battery Monitor v1.2.0 — Bluetooth Headset Support

> **This is a Java recode of [krisgfx9/Wireless-Device-Battery-Monitor](https://github.com/krisgfx9/Wireless-Device-Battery-Monitor) (the original C++ BatteryHub by krisgfx9), adapted to support specific wireless devices.**

**Wireless Battery Monitor v1.2.0** introduces native Windows Bluetooth battery monitoring for the **QCY T13 ANC** (`QCY-T13 ANC`), automatic headset selection between Akko and QCY using Windows default audio output detection, and a unified headset indicator across the main window, system tray, and native Win32 taskbar widget.

---

## Highlights in v1.2.0

- **Bluetooth battery monitoring for QCY T13 ANC (`QCY-T13 ANC`):** passive, read-only native Windows Bluetooth battery detection through SetupAPI / Cfgmgr32 PnP properties (`DEVPKEY_Bluetooth_Battery`, `PKEY_Devices_BatteryLife`, `DEVPKEY_Bluetooth_IsConnected`) consolidated by `ContainerId`, without opening RFCOMM or GATT connections. Real battery reading on Windows 11 was confirmed by the user (`20%` reported by Windows and `20%` displayed in the app widget).
  > *Note:* Bluetooth battery readings depend on the battery level information exposed by Windows. Other untested Bluetooth models are not declared physically compatible.
- **Automatic headset selection:** `CategoryDeviceSelector` automatically selects the active audio device between **Akko Verge S9 / S9 Ultra** and **QCY T13 ANC**.
- **Windows default audio output detection:** inspects the active Windows default render endpoint via Core Audio (`MMDevice` COM API) to break ties when both headsets are connected.
- **Unified headset card and taskbar indicator:** the main window, system tray, and taskbar widget share the exact same resolved selection, keeping a clean three-indicator layout (mouse, active headset, controller).
- **Improved simulation scenarios & updated device tooltips:** added multi-headset simulation presets and clearer device tooltips across UI surfaces.
- **Preserved native Win32 taskbar widget & memory optimizations:** maintains the `WS_CHILD` Win32 taskbar widget (`Win+D` and `Alt+Tab` compatible), SVG caching, idle animation pausing, and all existing USB HID drivers (`Delux M800 Pro PAW3395`, `Akko Verge S9 / S9 Ultra`, and `GameSir Cyclone 2`).
- **Installer upgrade support:** preserves the fixed MSI `UpgradeCode` (`{7C2F4B5E-3D9A-4E8C-91F2-8A6B4D2E1C90}`), per-user installation scope, directory-scoped safe process closure (`CloseHelper`), user settings (`%USERPROFILE%\.wireless-battery-java`), shortcuts, and Windows autostart preferences for direct upgrades from `v1.0.0`, `v1.1.0`, and `v1.1.1` *(structurally preserved and verified in the MSI package; manual upgrade from `v1.1.1` is left to the user)*.
- **Automated test suite:** **77 automated tests passing** (`mvn clean package`).

---

## Supported Devices

| Device | Type | Connection | Notes |
| :--- | :--- | :--- | :--- |
| **QCY T13 ANC (`QCY-T13 ANC`)** | Bluetooth Earbuds | Bluetooth (Windows Native APIs) | Real battery reading confirmed by user on Windows 11 (`20%` Windows / `20%` app) |
| **Akko Verge S9 / S9 Ultra** | Wireless Headset | 2.4 GHz USB Dongle | USB HID |
| **Delux M800 Pro PAW3395** | Wireless Mouse | 2.4 GHz USB Dongle *(and USB cable)* | USB HID |
| **GameSir Cyclone 2** | Wireless Controller | 2.4 GHz USB Dongle *(XInput mode & USB cable)* | USB HID |

---

## Downloads & Upgrade Instructions

- **`WirelessBattery-1.2.0.exe` (Recommended — Windows x64 Per-User Installer & Upgrader):**
  Includes a bundled Java runtime and requires no Administrator privileges. Run directly to install or upgrade an existing `v1.0.0` / `v1.1.x` installation without uninstalling manually.
- **`WirelessBattery-1.2.0-portable-win-x64.zip` (Portable x64 Archive):**
  Extract the folder and run `WirelessBattery\WirelessBattery.exe` (includes bundled Java runtime, no installation required).
- **`SHA256SUMS.txt`:**
  Verify downloaded files in PowerShell with `Get-FileHash .\WirelessBattery-1.2.0.exe -Algorithm SHA256`. Windows SmartScreen may warn on unsigned open-source binaries; verify the official GitHub Release URL and SHA-256 checksum before running.

---

## Credits & License

Inspired by [krisgfx9/Wireless-Device-Battery-Monitor](https://github.com/krisgfx9/Wireless-Device-Battery-Monitor) (original C++ *BatteryHub* by **krisgfx9**), with additional references from `joaoarebalo/Wireless-Device-Battery-Monitor-Fallen`, `xb-bx/m800-pro-driver`, `vdemonchy/cyclone2-linux`, and `pedrosegato/gamesir-reverse-engineering`. Distributed under the **GNU General Public License v3.0 (GPL-3.0)**.
