package br.com.wirelessbattery.device;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Configuração do receptor USB 2.4 GHz do headset Akko S9 Ultra / Verge S9.
 *
 * Receptor confirmado em hardware real no Windows:
 * - VID: 0x36EB (GTAI / Shenzhen GTAI Technology Co., Ltd.)
 * - PID: 0x1087 (Akko S9 Ultra_Dongle)
 * - Interface: 3 (Col01 UsagePage 0x000C:0x0001 Consumer Control; Col02 UsagePage 0x008C:0x0001)
 *
 * Exclui explicitamente teclados Akko (ex.: VID 0x3151 / PID 0x502D) para evitar falsos positivos.
 */
public final class AkkoVergeS9Config {
    public static final int DEFAULT_VENDOR_ID = 0x36EB;
    public static final int DEFAULT_PRODUCT_ID = 0x1087;
    public static final int WIRED_OR_ALT_PRODUCT_ID = 0x0087;
    public static final int DEFAULT_INTERFACE_NUMBER = 3;
    public static final int CONSUMER_USAGE_PAGE = 0x000C;
    public static final int PROPRIETARY_CANDIDATE_USAGE_PAGE = 0x008C;
    public static final int DEFAULT_USAGE_PAGE = 0x008C;
    public static final int DEFAULT_USAGE = 0x0001;
    public static final int DEFAULT_READ_TIMEOUT_MS = 1000;

    public static final int KNOWN_KEYBOARD_VENDOR_ID = 0x3151;
    public static final int KNOWN_KEYBOARD_PRODUCT_ID = 0x502D;

    private final Integer vendorId;
    private final Integer productId;
    private final int interfaceNumber;

    public AkkoVergeS9Config(Integer vendorId, Integer productId) {
        this(vendorId, productId, DEFAULT_INTERFACE_NUMBER);
    }

    public AkkoVergeS9Config(Integer vendorId, Integer productId, int interfaceNumber) {
        this.vendorId = vendorId;
        this.productId = productId;
        this.interfaceNumber = interfaceNumber;
    }

    public static AkkoVergeS9Config defaultReference() {
        return new AkkoVergeS9Config(DEFAULT_VENDOR_ID, DEFAULT_PRODUCT_ID, DEFAULT_INTERFACE_NUMBER);
    }

    public static AkkoVergeS9Config unconfigured() {
        return new AkkoVergeS9Config(null, null, -1);
    }

    public static AkkoVergeS9Config loadFromEnvironment() {
        Properties props = new Properties();
        Path localConfig = Path.of("devices.properties");
        if (Files.isRegularFile(localConfig)) {
            try (InputStream in = Files.newInputStream(localConfig)) {
                props.load(in);
            } catch (IOException ignored) {
                // Usa padrões confirmados se o arquivo não puder ser lido.
            }
        }
        int vid = DeluxM800ProConfig.parseHexOrInt(resolve("wirelessbattery.akkoverges9.vid", props), DEFAULT_VENDOR_ID);
        int pid = DeluxM800ProConfig.parseHexOrInt(resolve("wirelessbattery.akkoverges9.pid", props), DEFAULT_PRODUCT_ID);
        int iface = DeluxM800ProConfig.parseHexOrInt(resolve("wirelessbattery.akkoverges9.interface", props), DEFAULT_INTERFACE_NUMBER);
        return new AkkoVergeS9Config(vid, pid, iface);
    }

    private static String resolve(String key, Properties fileProps) {
        String sys = System.getProperty(key);
        if (sys != null && !sys.isBlank()) {
            return sys.trim();
        }
        String fileVal = fileProps.getProperty(key);
        return (fileVal != null && !fileVal.isBlank()) ? fileVal.trim() : null;
    }

    public boolean isConfigured() {
        return vendorId != null && productId != null;
    }

    public Integer getVendorId() {
        return vendorId;
    }

    public Integer getProductId() {
        return productId;
    }

    public int getInterfaceNumber() {
        return interfaceNumber;
    }

    public boolean matches(int vid, int pid) {
        if (!isConfigured()) {
            return false;
        }
        int v = vid & 0xFFFF;
        int p = pid & 0xFFFF;
        if (isKnownAkkoKeyboard(v, p)) {
            return false;
        }
        int cfgV = vendorId & 0xFFFF;
        int cfgP = productId & 0xFFFF;
        if (cfgV != v) {
            return false;
        }
        if (cfgP == p) {
            return true;
        }
        return cfgV == DEFAULT_VENDOR_ID && cfgP == DEFAULT_PRODUCT_ID && p == WIRED_OR_ALT_PRODUCT_ID;
    }

    /**
     * Verifica se a coleção HID corresponde exatamente ao canal de controle e bateria
     * ({@code Col02}: UsagePage {@code 0x008C}, Usage {@code 0x0001}, Interface 3).
     */
    public boolean matchesTargetCollection(int vid, int pid, int iface, int usagePage, int usage, String product) {
        if (!matchesHeadsetInterface(vid, pid, usagePage, usage, product)) {
            return false;
        }
        if (interfaceNumber >= 0 && iface >= 0 && iface != interfaceNumber) {
            return false;
        }
        int page = usagePage & 0xFFFF;
        int u = usage & 0xFFFF;
        return page == DEFAULT_USAGE_PAGE && u == DEFAULT_USAGE;
    }

    /**
     * Validação completa para impedir que interfaces de teclado/mouse (ex.: Akko Keyboard 0x3151:0x502D)
     * sejam confundidas com o receptor do headset.
     */
    public boolean matchesHeadsetInterface(int vid, int pid, int usagePage, int usage, String product) {
        if (!matches(vid, pid)) {
            return false;
        }
        int page = usagePage & 0xFFFF;
        int u = usage & 0xFFFF;
        if (page == 0x0001 && (u == 0x0006 || u == 0x0002)) {
            return false;
        }
        if (product != null) {
            String lower = product.toLowerCase(Locale.ROOT);
            if (lower.contains("keyboard") || lower.contains("teclado") || lower.contains("mouse")) {
                return false;
            }
        }
        return true;
    }

    public static boolean isKnownAkkoKeyboard(int vid, int pid) {
        int v = vid & 0xFFFF;
        int p = pid & 0xFFFF;
        return v == KNOWN_KEYBOARD_VENDOR_ID && p == KNOWN_KEYBOARD_PRODUCT_ID;
    }
}
