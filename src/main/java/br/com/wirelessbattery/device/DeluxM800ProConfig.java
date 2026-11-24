package br.com.wirelessbattery.device;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Configuração dos identificadores HID do Delux M800 Pro PAW3395.
 *
 * Os identificadores USB padrão (VID 0x248A, PIDs 0x5B2F/0x5B2E e interface 1) foram
 * consultados originalmente no projeto público {@code https://github.com/xb-bx/m800-pro-driver}
 * e confirmados no receptor real no Windows (IF 1, UsagePage 0xFF00, Usage 0x0001 / Col04),
 * permanecendo configuráveis via propriedades locais. O comportamento de carregamento e
 * carga em 100% ainda exige validação em hardware real.
 */
public final class DeluxM800ProConfig {
    // Identificadores técnicos consultados em xb-bx/m800-pro-driver e validados no Windows
    // (IF 1, UsagePage 0xFF00, Usage 0x0001, Col04)
    public static final int DEFAULT_VENDOR_ID = 0x248A;
    public static final int DEFAULT_WIRELESS_PID = 0x5B2F;
    public static final int DEFAULT_WIRED_PID = 0x5B2E;
    public static final int DEFAULT_INTERFACE_NUMBER = 1;
    public static final int DEFAULT_USAGE_PAGE = 0xFF00;
    public static final int DEFAULT_USAGE = 0x0001;
    public static final int DEFAULT_MAX_ATTEMPTS = 16;

    private final int vendorId;
    private final int wirelessProductId;
    private final int wiredProductId;
    private final int interfaceNumber;
    private final Integer usagePage;
    private final Integer usage;
    private final int maxAttempts;

    public DeluxM800ProConfig(
            int vendorId,
            int wirelessProductId,
            int wiredProductId,
            int interfaceNumber,
            Integer usagePage,
            int maxAttempts
    ) {
        this(vendorId, wirelessProductId, wiredProductId, interfaceNumber, usagePage, null, maxAttempts);
    }

    public DeluxM800ProConfig(
            int vendorId,
            int wirelessProductId,
            int wiredProductId,
            int interfaceNumber,
            Integer usagePage,
            Integer usage,
            int maxAttempts
    ) {
        this.vendorId = vendorId;
        this.wirelessProductId = wirelessProductId;
        this.wiredProductId = wiredProductId;
        this.interfaceNumber = interfaceNumber;
        this.usagePage = usagePage;
        this.usage = usage;
        this.maxAttempts = Math.max(1, Math.min(maxAttempts, 64));
    }

    public static DeluxM800ProConfig defaultReference() {
        return new DeluxM800ProConfig(
                DEFAULT_VENDOR_ID,
                DEFAULT_WIRELESS_PID,
                DEFAULT_WIRED_PID,
                DEFAULT_INTERFACE_NUMBER,
                DEFAULT_USAGE_PAGE,
                DEFAULT_USAGE,
                DEFAULT_MAX_ATTEMPTS
        );
    }

    /**
     * Carrega overrides opcionais a partir de propriedades do sistema ou de um arquivo
     * {@code devices.properties} na pasta de execução, caso exista.
     */
    public static DeluxM800ProConfig loadFromEnvironment() {
        Properties props = new Properties();
        Path localConfig = Path.of("devices.properties");
        if (Files.isRegularFile(localConfig)) {
            try (InputStream in = Files.newInputStream(localConfig)) {
                props.load(in);
            } catch (IOException ignored) {
                // Usa padrões de referência se o arquivo não puder ser lido.
            }
        }
        int vid = parseHexOrInt(resolve("wirelessbattery.m800pro.vid", props), DEFAULT_VENDOR_ID);
        int wirelessPid = parseHexOrInt(resolve("wirelessbattery.m800pro.pid.wireless", props), DEFAULT_WIRELESS_PID);
        int wiredPid = parseHexOrInt(resolve("wirelessbattery.m800pro.pid.wired", props), DEFAULT_WIRED_PID);
        int iface = parseHexOrInt(resolve("wirelessbattery.m800pro.interface", props), DEFAULT_INTERFACE_NUMBER);
        Integer usagePage = parseOptionalHexOrInt(resolve("wirelessbattery.m800pro.usagePage", props));
        if (usagePage == null) {
            usagePage = DEFAULT_USAGE_PAGE;
        }
        Integer usage = parseOptionalHexOrInt(resolve("wirelessbattery.m800pro.usage", props));
        if (usage == null) {
            usage = DEFAULT_USAGE;
        }
        int attempts = parseHexOrInt(resolve("wirelessbattery.m800pro.maxAttempts", props), DEFAULT_MAX_ATTEMPTS);
        return new DeluxM800ProConfig(vid, wirelessPid, wiredPid, iface, usagePage, usage, attempts);
    }

    private static String resolve(String key, Properties fileProps) {
        String sys = System.getProperty(key);
        if (sys != null && !sys.isBlank()) {
            return sys.trim();
        }
        String fileVal = fileProps.getProperty(key);
        return (fileVal != null && !fileVal.isBlank()) ? fileVal.trim() : null;
    }

    static int parseHexOrInt(String raw, int fallback) {
        Integer parsed = parseOptionalHexOrInt(raw);
        return parsed != null ? parsed : fallback;
    }

    static Integer parseOptionalHexOrInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            String normalized = raw.trim().toLowerCase();
            if (normalized.startsWith("0x")) {
                return Integer.parseInt(normalized.substring(2), 16);
            }
            return Integer.parseInt(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public int getVendorId() {
        return vendorId;
    }

    public int getWirelessProductId() {
        return wirelessProductId;
    }

    public int getWiredProductId() {
        return wiredProductId;
    }

    public int getInterfaceNumber() {
        return interfaceNumber;
    }

    public Integer getUsagePage() {
        return usagePage;
    }

    public Integer getUsage() {
        return usage;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public boolean matches(int vid, int pid) {
        return (vid & 0xFFFF) == vendorId && ((pid & 0xFFFF) == wirelessProductId || (pid & 0xFFFF) == wiredProductId);
    }

    /**
     * Verifica se a coleção corresponde exatamente à interface e UsagePage/Usage alvo
     * (IF 1, UsagePage 0xFF00, Usage 0x0001 / Col04 no receptor real).
     */
    public boolean matchesTargetCollection(int vid, int pid, int iface, int collUsagePage, int collUsage) {
        if (!matches(vid, pid)) {
            return false;
        }
        boolean ifaceMatch = (iface == interfaceNumber || iface < 0);
        int page = collUsagePage & 0xFFFF;
        int u = collUsage & 0xFFFF;
        boolean pageMatch = usagePage != null ? page == (usagePage & 0xFFFF) : page >= 0xFF00;
        boolean usageMatch = usage == null || u == (usage & 0xFFFF);
        return ifaceMatch && pageMatch && usageMatch;
    }

    /**
     * Verifica se é uma coleção Vendor (>= 0xFF00) na mesma interface esperada (IF 1),
     * excluindo coleções padrão (mouse/teclado/consumer) e interfaces diferentes (ex.: IF 2 0xFFEF).
     */
    public boolean matchesVendorFallbackOnExpectedInterface(int vid, int pid, int iface, int collUsagePage) {
        if (!matches(vid, pid)) {
            return false;
        }
        boolean ifaceMatch = (iface == interfaceNumber || iface < 0);
        int page = collUsagePage & 0xFFFF;
        return ifaceMatch && page >= 0xFF00;
    }

    public String describeIds() {
        return String.format("VID 0x%04X / PID 0x%04X (2.4G) ou 0x%04X (Cabo), IF %d, Usage %s:%s",
                vendorId,
                wirelessProductId,
                wiredProductId,
                interfaceNumber,
                usagePage != null ? String.format("0x%04X", usagePage & 0xFFFF) : "0xFF00+",
                usage != null ? String.format("0x%04X", usage & 0xFFFF) : "*");
    }
}
