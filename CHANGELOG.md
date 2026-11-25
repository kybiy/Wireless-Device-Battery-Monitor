# Changelog

All notable changes to **Wireless Battery Monitor (Java Edition)** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.2.0] - 2026-11-25

### Added
- **Bluetooth Battery Monitoring for QCY T13 ANC (`QCY-T13 ANC`):** added passive, read-only native Windows Bluetooth battery detection (`WindowsBluetoothScanner` + `QcyT13Bluetooth`) using SetupAPI/Cfgmgr32 PnP device properties (`DEVPKEY_Bluetooth_Battery`, `PKEY_Devices_BatteryLife`, `DEVPKEY_Bluetooth_IsConnected`) consolidated by `ContainerId`, without opening RFCOMM/GATT connections or interfering with A2DP/HFP audio. Real Bluetooth battery reading on Windows 11 was confirmed by the user (`20%` in Windows and `20%` in the app widget). Other untested Bluetooth models are not declared physically compatible.
- **Automatic Headset Selection & Windows Default Audio Output Detection:** added `CategoryDeviceSelector` and `WindowsAudioOutputInspector` (Core Audio / MMDevice COM API via JNA) to share a unified headset slot across the main window cards, system tray, and native Win32 taskbar widget, automatically selecting between **Akko Verge S9 / S9 Ultra** and **QCY T13 ANC** based on connectivity and the active Windows default audio render endpoint.
- **Improved Simulation Scenarios & Updated Tooltips:** expanded `SimulationManager` / `SimulationControlPanel` with multi-headset scenarios and refined device tooltips in the main UI, tray, and native taskbar widget.

### Changed
- **Preserved Native Win32 Taskbar Widget, Memory Optimizations & Upgradable Installer:** kept `NativeTaskbarWidget` (`WS_CHILD` on `Shell_TrayWnd` with `Win+D` persistence), SVG caching / idle optimizations, all existing USB HID protocols (Delux M800 Pro, Akko Verge S9 / S9 Ultra, GameSir Cyclone 2), and the per-user upgradable Windows installer (`UpgradeCode` `{7C2F4B5E-3D9A-4E8C-91F2-8A6B4D2E1C90}` and directory-scoped `CloseHelper`).

## [1.1.1] - 2026-11-24

O Delux M800 Pro passa a interpretar separadamente percentual e carregamento. Valores acima de 100 passam a indicar leitura inválida. A inicialização automática respeita a desativação feita pelo usuário no Gerenciador de Tarefas.

## [1.1.0] - 2026-11-24

### Added
- **Native Win32 Taskbar Widget (`NativeTaskbarWidget`) with `Win+D` persistence:** implemented a native Win32 child window (`WS_CHILD | WS_CLIPSIBLINGS`, `WS_EX_LAYERED | WS_EX_NOACTIVATE`) parented directly to `Shell_TrayWnd` and rendered with per-pixel alpha via `UpdateLayeredWindow` (`ULW_ALPHA`). Unlike a floating Swing window, the indicator stays anchored to the Windows taskbar during **`Win+D` (Show Desktop)** and `Alt+Tab`, never steals focus (`MA_NOACTIVATE`), never overlaps fullscreen games, and automatically falls back to Swing `JWindow` / `SystemTray` if native attachment is unavailable.
- **Seamless In-Place Windows Installer Upgrades (`WirelessBattery-1.1.0.exe`):**
  - Automatically detects existing installations (`v1.0.0+`) via fixed MSI `UpgradeCode` (`{7C2F4B5E-3D9A-4E8C-91F2-8A6B4D2E1C90}`) and presents a streamlined one-click **Update Existing Installation** dialog without requiring manual uninstallation.
  - Safely and gracefully closes running installed instances before file validation (`CloseHelper` scoped strictly to `[INSTALLDIR]`, leaving portable copies in other directories untouched).
  - Schedules `RemoveExistingProducts` inside the standard Windows Installer transaction (`After="InstallInitialize"`, structurally verified in the MSI sequence table) to support MSI rollback on interrupted upgrades.
  - Preserves `%USERPROFILE%\.wireless-battery-java\settings.properties` (including forward-compatible unknown/extra keys), Desktop and Start Menu shortcuts, installation folder (including migration from legacy `v1.0.0` registry keys), and `HKCU\...\Run` Windows startup settings, while blocking accidental downgrades.
  - Added optional *"Launch Wireless Battery Monitor"* checkbox on the installer Finish screen.
- **Cooperative Shutdown Support:** added `--shutdown` CLI option, `shutdown.signal` listener in `SingleInstanceGuard`, and an idempotent JVM shutdown hook in `Main` to cleanly release system tray icons, native Win32 taskbar resources, and `hid4java` handles on exit or upgrade.

### Changed
- **Memory & Idle CPU Optimizations:**
  - Cached parsed Lucide/Feather vector icons by `(icon, size, color)` in `DeviceIconFactory` (`SVG_CACHE`) to avoid repeated XML/SVG parsing on card and taskbar repaints.
  - Paused card progress bar animation timers immediately when the main window is hidden/minimized (`!isShowing()` / `removeNotify()`).
  - Avoided unnecessary Swing container rebuilds and icon reallocations in `MainWindow` when displayed device order and status colors remain unchanged, and added a zero-allocation `taskbarPeer()` fast path in `WindowsTaskbarLocator`.
  - Bounded live log lines in `HidDiagnosticsDialog` to `AppLogger.MAX_ENTRIES` (`400`) to prevent unbounded document growth during long uptime sessions.
- **Publisher / Vendor Metadata:** updated package, JAR manifest, executable `VersionInfo`, and Windows Installed Apps Publisher metadata to `kybiy` while maintaining full upgrade compatibility with `v1.0.0` installations.

## [1.0.0] - 2026-11-23

### Added
- Initial stable Java 17 + Swing + FlatLaf release for Windows x64 (recode inspired by `krisgfx9/Wireless-Device-Battery-Monitor`).
- Read-only USB HID battery monitoring validated on Windows 11 for three 2.4 GHz wireless peripherals:
  - **Akko Verge S9 / S9 Ultra** (`VID 0x36EB / PID 0x1087`, `IF 3`, `0x008C:0x0001`, Report `0x02` cmd `0x12` with CRC-16 `0x8005`).
  - **Delux M800 Pro PAW3395** (`VID 0x248A / PID 0x5B2F` wireless & `0x5B2E` wired, `IF 1`, `0xFF00:0x0001`, Feature Report `0x0C`).
  - **GameSir Cyclone 2** (`VID 0x3537 / PID 0x100B` active, `0x0575` idle receiver & `0x1053` wired in XInput mode, `0xFFF0:0x0040`, Report `0x0F 0x03` / `0x12`).
- Compact Windows 11-inspired main window with Dark and Light themes, system tray icons (`SystemTray`), taskbar indicator mode, read-only HID Diagnostics dialog, isolated Simulation mode, single-instance lock (`SingleInstanceGuard`), and non-admin Windows 11 autostart (`HKCU\Software\Microsoft\Windows\CurrentVersion\Run`).
- Standalone Windows `.exe` per-user installer and portable `.zip` distribution with bundled Java runtime via `jpackage`.
