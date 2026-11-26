# Third-Party Notices and Technical References

**Wireless Battery Monitor (Java Edition)** is distributed under the **GNU General Public License v3.0 (GPL-3.0)** (see [LICENSE](LICENSE) and [docs/licenses/REDISTRIBUICAO.md](docs/licenses/REDISTRIBUICAO.md)).

This document strictly separates **technical protocol references** (where no third-party source code was copied) from **bundled runtime libraries and visual assets** included in binary distributions.

---

## 1. Technical & Protocol References (No Third-Party Source Code Copied)

All device drivers and Windows integrations in this repository were written cleanly in Java from scratch. The open-source repositories below were consulted solely as architectural or protocol references:

| Reference | Scope Consulted | License | Repository Availability |
| --- | --- | --- | --- |
| **krisgfx9/Wireless-Device-Battery-Monitor** *(original C++ BatteryHub by krisgfx9)* | Original concept, taskbar monitor workflow, and historical design inspiration | GPL-3.0 or later *(per historical README)* | Primary URL (`https://github.com/krisgfx9/Wireless-Device-Battery-Monitor`) currently returns **HTTP 404**; historical mirror/archive available at [ferloom/Wireless-Device-Battery-Monitor](https://github.com/ferloom/Wireless-Device-Battery-Monitor) |
| **joaoarebalo/Wireless-Device-Battery-Monitor-Fallen** | Architectural reference for `WS_CHILD` native Win32 taskbar parenting and passive Windows PnP Bluetooth SetupAPI scanning (`generic.cpp`) | GPL-3.0 | [GitHub](https://github.com/joaoarebalo/Wireless-Device-Battery-Monitor-Fallen) |
| **xb-bx/m800-pro-driver** | Protocol reference for Delux M800 Pro PAW3395 HID Feature Report `0x0C` battery and charging byte offsets (cleanly reimplemented in Java after prior audit) | GPL-2.0 | [GitHub](https://github.com/xb-bx/m800-pro-driver) |
| **vdemonchy/cyclone2-linux** | Protocol reference for GameSir Cyclone 2 XInput Output Report `0x0F 0x03` / Input Report `0x12` (`docs/protocol.md` confirms passive `GET_FEATURE` returns `EPIPE` and idle reads emit zero frames; bytes `[2..63]` remain `0x00` so LED selector `0x20` is never written) | GPL-3.0 | [GitHub](https://github.com/vdemonchy/cyclone2-linux) |
| **pedrosegato/gamesir-reverse-engineering** | Complementary GameSir HID protocol documentation | MIT | [GitHub](https://github.com/pedrosegato/gamesir-reverse-engineering) · [Local License](docs/licenses/gamesir-reverse-engineering-LICENSE.txt) |

---

## 2. Bundled Runtime Libraries & Assets

The compiled application and Windows x64 packages (`WirelessBattery-*.exe` and `WirelessBattery-*-portable-win-x64.zip`) include the following third-party components:

| Component | Version | License | Notice Location |
| --- | --- | --- | --- |
| **FlatLaf** (`com.formdev:flatlaf`) | `3.5.4` | Apache License 2.0 | `META-INF/LICENSE` inside JAR |
| **FlatLaf Extras** (`com.formdev:flatlaf-extras`) | `3.5.4` | Apache License 2.0 | `META-INF/LICENSE` inside JAR |
| **JSVG** (transitive via `flatlaf-extras`) | `1.4.0` | MIT License | `META-INF/LICENSE` inside JAR |
| **hid4java** (`org.hid4java:hid4java`) | `0.8.0` | MIT License | [hid4java-LICENSE.txt](docs/licenses/hid4java-LICENSE.txt) |
| **HIDAPI** (native library inside `hid4java`) | Bundled with `hid4java 0.8.0` | GPL-3.0 / BSD / Signal 11 *(distributed under BSD option)* | [hidapi-LICENSE.txt](docs/licenses/hidapi-LICENSE.txt) · [hidapi-LICENSE-bsd.txt](docs/licenses/hidapi-LICENSE-bsd.txt) |
| **JNA & JNA Platform** (`net.java.dev.jna`) | `5.19.1` (transitive via `hid4java`) / `5.14.0` (`jna-platform`) | Apache License 2.0 or LGPL-2.1+ *(distributed under Apache-2.0 option)* | `META-INF/LICENSE` inside JARs |
| **Lucide / Feather SVG Icons** | Icon assets | ISC / MIT License | [lucide-LICENSE.txt](docs/licenses/lucide-LICENSE.txt) |
| **Eclipse Temurin JDK / Runtime** *(bundled by `jpackage`)* | `25.0.5.1+2` (Windows x64) | GPL-2.0 with Classpath Exception | `runtime/legal` inside installed/portable directory · [Official Build & Sources](https://github.com/adoptium/temurin25-binaries/releases/tag/jdk-25.0.5.1%2B2) |

---

## 3. Build & Test Dependencies (Not Bundled in Runtime Distributions)

| Component | Version | Scope | License |
| --- | --- | --- | --- |
| **JUnit 5** (`org.junit.jupiter:junit-jupiter`) | `5.11.4` | Test only (`<scope>test</scope>`) | Eclipse Public License 2.0 (EPL-2.0) |

---

## 4. GPL-3.0 Corresponding Source

In accordance with **GNU GPL v3.0**, all binary distributions are accompanied by the Corresponding Source (including `pom.xml`, Java sources, WiX templates, and `scripts/package-windows.ps1`) in the official repository:  
`https://github.com/kybiy/Wireless-Device-Battery-Monitor`
