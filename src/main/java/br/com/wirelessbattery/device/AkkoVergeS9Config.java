package br.com.wirelessbattery.device;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Configuração opcional do receptor USB 2.4 GHz do Akko Verge S9.
 * Por padrão, VID e PID permanecem indefinidos até serem confirmados via Diagnóstico HID.
 */
public final class AkkoVergeS9Config {
    private final Integer vendorId;
    private final Integer productId;

    public AkkoVergeS9Config(Integer vendorId, Integer productId) {
        this.vendorId = vendorId;
        this.productId = productId;
    }

    public static AkkoVergeS9Config unconfigured() {
        return new AkkoVergeS9Config(null, null);
    }

    public static AkkoVergeS9Config loadFromEnvironment() {
        Properties props = new Properties();
        Path localConfig = Path.of("devices.properties");
        if (Files.isRegularFile(localConfig)) {
            try (InputStream in = Files.newInputStream(localConfig)) {
                props.load(in);
            } catch (IOException ignored) {
                // Mantém não configurado se não for possível ler.
            }
        }
        Integer vid = DeluxM800ProConfig.parseOptionalHexOrInt(resolve("wirelessbattery.akkoverges9.vid", props));
        Integer pid = DeluxM800ProConfig.parseOptionalHexOrInt(resolve("wirelessbattery.akkoverges9.pid", props));
        return new AkkoVergeS9Config(vid, pid);
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

    public boolean matches(int vid, int pid) {
        return isConfigured() && vendorId == vid && productId == pid;
    }
}
