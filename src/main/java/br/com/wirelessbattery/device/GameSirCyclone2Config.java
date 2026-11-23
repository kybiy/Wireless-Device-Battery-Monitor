package br.com.wirelessbattery.device;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Configuração dos identificadores HID do controle GameSir Cyclone 2.
 *
 * Valores baseados diretamente no driver C++ {@code PollGameSirCyclone}
 * ({@code src/drivers/gamesir.cpp} do repositório de referência, protocolo
 * {@code cyclone2-linux}):
 * - VID: 0x3537
 * - PID ativo (2.4 GHz linkado ou cabo USB em modo XInput): 0x100B
 * - PID receptor ocioso (dongle 2.4 GHz sem controle ligado): 0x0575
 * - Coleção HID proprietária: Usage Page 0xFFF0, Usage 0x0040
 *
 * Os valores permanecem configuráveis via {@code devices.properties} ou propriedades {@code -D}.
 */
public final class GameSirCyclone2Config {
    public static final int DEFAULT_VENDOR_ID = 0x3537;
    public static final int DEFAULT_ACTIVE_PID = 0x100B;
    public static final int DEFAULT_IDLE_RECEIVER_PID = 0x0575;
    public static final int DEFAULT_USAGE_PAGE = 0xFFF0;
    public static final int DEFAULT_USAGE = 0x0040;
    public static final int DEFAULT_READ_TIMEOUT_MS = 1500;

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
        return (vid & 0xFFFF) == vendorId && (pid & 0xFFFF) == activeProductId;
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

    public String describeIds() {
        return String.format(
                "VID 0x%04X / PID ativo 0x%04X (ocioso 0x%04X), Usage %04X:%04X",
                vendorId, activeProductId, idleReceiverProductId, usagePage & 0xFFFF, usage & 0xFFFF
        );
    }
}
