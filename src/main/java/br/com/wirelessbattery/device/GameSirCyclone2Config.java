package br.com.wirelessbattery.device;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Configuração dos identificadores HID do controle GameSir Cyclone 2.
 *
 * Valores confirmados nos repositórios de referência ({@code pedrosegato/gamesir-reverse-engineering}
 * e {@code vdemonchy/cyclone2-linux}):
 * - VID: 0x3537 (GameSir)
 * - PIDs ativos (modo XInput, respondem na coleção FFF0:0040):
 *   - 0x100B: receptor 2.4 GHz com controle pareado
 *   - 0x102A: receptor 2.4 GHz (variante wireless)
 *   - 0x1053: cabo USB (firmware 3.x)
 *   - 0x101D: cabo USB (firmware 1.x)
 * - PID receptor ocioso / modo HID: 0x0575 (dongle sem controle ligado ou em modo HID)
 * - Coleção HID proprietária: Usage Page 0xFFF0, Usage 0x0040
 *
 * Os valores permanecem configuráveis via {@code devices.properties} ou propriedades {@code -D}.
 */
public final class GameSirCyclone2Config {
    public static final int DEFAULT_VENDOR_ID = 0x3537;
    public static final int DEFAULT_ACTIVE_PID = 0x100B;
    public static final int DEFAULT_WIRELESS_ALT_PID = 0x102A;
    public static final int DEFAULT_WIRED_PID = 0x1053;
    public static final int DEFAULT_WIRED_LEGACY_PID = 0x101D;
    public static final int DEFAULT_IDLE_RECEIVER_PID = 0x0575;
    public static final int DEFAULT_USAGE_PAGE = 0xFFF0;
    public static final int DEFAULT_USAGE = 0x0040;
    public static final int DEFAULT_READ_TIMEOUT_MS = 1500;
    public static final int IDLE_PROBE_TIMEOUT_MS = 600;

    private final int vendorId;
    private final int activeProductId;
    private final int idleReceiverProductId;
    private final int usagePage;
    private final int usage;
    private final int readTimeoutMs;

    public GameSirCyclone2Config(
            int vendorId,
            int activeProductId,
            int idleReceiverProductId,
            int usagePage,
            int usage,
            int readTimeoutMs
    ) {
        this.vendorId = vendorId;
        this.activeProductId = activeProductId;
        this.idleReceiverProductId = idleReceiverProductId;
        this.usagePage = usagePage;
        this.usage = usage;
        this.readTimeoutMs = Math.max(300, Math.min(readTimeoutMs, 5000));
    }

    public static GameSirCyclone2Config defaultReference() {
        return new GameSirCyclone2Config(
                DEFAULT_VENDOR_ID,
                DEFAULT_ACTIVE_PID,
                DEFAULT_IDLE_RECEIVER_PID,
                DEFAULT_USAGE_PAGE,
                DEFAULT_USAGE,
                DEFAULT_READ_TIMEOUT_MS
        );
    }

    public static GameSirCyclone2Config loadFromEnvironment() {
        Properties props = new Properties();
        Path localConfig = Path.of("devices.properties");
        if (Files.isRegularFile(localConfig)) {
            try (InputStream in = Files.newInputStream(localConfig)) {
                props.load(in);
            } catch (IOException ignored) {
                // Mantém valores de referência caso não seja possível ler
            }
        }
        int vid = DeluxM800ProConfig.parseHexOrInt(resolve("wirelessbattery.cyclone2.vid", props), DEFAULT_VENDOR_ID);
        int activePid = DeluxM800ProConfig.parseHexOrInt(resolve("wirelessbattery.cyclone2.pid.active", props), DEFAULT_ACTIVE_PID);
        int idlePid = DeluxM800ProConfig.parseHexOrInt(resolve("wirelessbattery.cyclone2.pid.idle", props), DEFAULT_IDLE_RECEIVER_PID);
        int page = DeluxM800ProConfig.parseHexOrInt(resolve("wirelessbattery.cyclone2.usagePage", props), DEFAULT_USAGE_PAGE);
        int usage = DeluxM800ProConfig.parseHexOrInt(resolve("wirelessbattery.cyclone2.usage", props), DEFAULT_USAGE);
        int timeoutMs = DeluxM800ProConfig.parseHexOrInt(resolve("wirelessbattery.cyclone2.timeoutMs", props), DEFAULT_READ_TIMEOUT_MS);
        return new GameSirCyclone2Config(vid, activePid, idlePid, page, usage, timeoutMs);
    }

    private static String resolve(String key, Properties fileProps) {
        String sys = System.getProperty(key);
        if (sys != null && !sys.isBlank()) {
            return sys.trim();
        }
        String fileVal = fileProps.getProperty(key);
        return (fileVal != null && !fileVal.isBlank()) ? fileVal.trim() : null;
    }

    public int getVendorId() {
        return vendorId;
    }

    public int getActiveProductId() {
        return activeProductId;
    }

    public int getIdleReceiverProductId() {
        return idleReceiverProductId;
    }

    public int getUsagePage() {
        return usagePage;
    }

    public int getUsage() {
        return usage;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public boolean matchesActive(int vid, int pid) {
        if ((vid & 0xFFFF) != vendorId) {
            return false;
        }
        int p = pid & 0xFFFF;
        return p == activeProductId
                || p == DEFAULT_WIRELESS_ALT_PID
                || p == DEFAULT_WIRED_PID
                || p == DEFAULT_WIRED_LEGACY_PID;
    }

    public boolean isWiredPid(int pid) {
        int p = pid & 0xFFFF;
        return p == DEFAULT_WIRED_PID || p == DEFAULT_WIRED_LEGACY_PID;
    }

    public boolean matchesIdleReceiver(int vid, int pid) {
        return (vid & 0xFFFF) == vendorId && (pid & 0xFFFF) == idleReceiverProductId;
    }

    public boolean matchesAny(int vid, int pid) {
        return matchesActive(vid, pid) || matchesIdleReceiver(vid, pid);
    }

    public boolean matchesCollection(int vid, int pid, int collUsagePage, int collUsage) {
        return matchesActive(vid, pid)
                && (collUsagePage & 0xFFFF) == (usagePage & 0xFFFF)
                && (collUsage & 0xFFFF) == (usage & 0xFFFF);
    }

    public boolean matchesIdleCollection(int vid, int pid, int collUsagePage, int collUsage) {
        return matchesIdleReceiver(vid, pid)
                && (collUsagePage & 0xFFFF) == (usagePage & 0xFFFF)
                && (collUsage & 0xFFFF) == (usage & 0xFFFF);
    }

    public String describeIds() {
        return String.format(
                "VID 0x%04X / PID ativo 0x%04X/0x%04X/0x%04X/0x%04X (ocioso 0x%04X), Usage %04X:%04X",
                vendorId, activeProductId, DEFAULT_WIRELESS_ALT_PID, DEFAULT_WIRED_PID, DEFAULT_WIRED_LEGACY_PID,
                idleReceiverProductId, usagePage & 0xFFFF, usage & 0xFFFF
        );
    }
}
