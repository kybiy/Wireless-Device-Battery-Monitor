package br.com.wirelessbattery.config;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Properties;

/**
 * Resolves application version, Maven build timestamp, and runtime code source location.
 */
public final class AppVersion {

    public static final String APP_VENDOR = "kybiy";
    public static final String WINDOWS_UPGRADE_UUID = "7c2f4b5e-3d9a-4e8c-91f2-8a6b4d2e1c90";
    private static final String DEFAULT_VERSION = "1.1.0";
    private static final String DEFAULT_BUILD_TIMESTAMP = "dev";
    private static final String RESOURCE_PATH = "/build-info.properties";

    private static final String VERSION;
    private static final String BUILD_TIMESTAMP;

    static {
        Properties props = new Properties();
        try (InputStream in = AppVersion.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException ignored) {
            // Keep safe defaults when build metadata resource is unavailable.
        }
        VERSION = normalizeMetadata(props.getProperty("app.version"), DEFAULT_VERSION);
        BUILD_TIMESTAMP = normalizeMetadata(props.getProperty("app.build.timestamp"), DEFAULT_BUILD_TIMESTAMP);
    }

    private AppVersion() {
    }

    private static String normalizeMetadata(String raw, String fallback) {
        if (raw == null || raw.isBlank() || (raw.startsWith("${") && raw.endsWith("}"))) {
            return fallback;
        }
        return raw.trim();
    }

    public static String getVersion() {
        return VERSION;
    }

    public static String getBuildTimestamp() {
        return BUILD_TIMESTAMP;
    }

    public static String getCodeSourcePath() {
        try {
            CodeSource codeSource = AppVersion.class.getProtectionDomain().getCodeSource();
            URL location = codeSource != null ? codeSource.getLocation() : null;
            if (location == null) {
                return "(desconhecido)";
            }
            return Path.of(location.toURI()).toAbsolutePath().toString();
        } catch (URISyntaxException | RuntimeException ex) {
            return "(indisponível)";
        }
    }

    public static String formatShort() {
        return "v" + VERSION + " (build " + BUILD_TIMESTAMP + ")";
    }

    public static String formatStartupInfo() {
        return "Versão: " + VERSION
                + " | Build: " + BUILD_TIMESTAMP
                + " | Origem das classes/JAR: " + getCodeSourcePath();
    }
}
