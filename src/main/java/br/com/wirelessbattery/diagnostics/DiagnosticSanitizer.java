package br.com.wirelessbattery.diagnostics;

import br.com.wirelessbattery.config.AppSettings;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sanitizador puro para remoção de dados pessoais e credenciais antes da exportação
 * local de pacotes de diagnóstico.
 */
public final class DiagnosticSanitizer {

    private static final Pattern TOKEN_GHP_PATTERN = Pattern.compile("ghp_[A-Za-z0-9_]+");
    private static final Pattern TOKEN_GH_PAT_PATTERN = Pattern.compile("github_pat_[A-Za-z0-9_]+");
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._\\-]+");
    private static final Pattern KEY_SECRET_PATTERN = Pattern.compile("(?i)(?:token|password)\\s*[=:]\\s*\\S+");

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private static final Pattern WINDOWS_USER_HOME_PATTERN =
            Pattern.compile("(?i)([A-Za-z]:[\\\\/]Users[\\\\/])([^\\\\/\\s\"';,|<>]+)");

    private static final Pattern BT_MAC_SEPARATED_PATTERN =
            Pattern.compile("(?<![0-9A-Fa-f:-])(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}(?![0-9A-Fa-f:-])");
    private static final Pattern PNP_DEV_MAC_PATTERN =
            Pattern.compile("(?i)(DEV_)([0-9A-F]{12})(?![0-9A-F])");
    private static final Pattern PNP_BT_DEVICE_MAC_PATTERN =
            Pattern.compile("(?i)(BluetoothDevice_)([0-9A-F]{12})(?![0-9A-F])");
    private static final Pattern PNP_AMP_MAC_PATTERN =
            Pattern.compile("(?i)(&)([0-9A-F]{12})([_\\\\&#\\s]|$)");

    private static final Pattern SERIAL_FIELD_PATTERN =
            Pattern.compile("(?i)(Serial(?:Number)?\\s*[=:]\\s*)(?!-(?:\\s|\\||$)|<SERIAL_REDACTED>)([^\\r\\n|]+?)(?=\\s*(?:\\||$|\\r|\\n))");

    private static final Pattern HID_DEVICE_PATH_PATTERN =
            Pattern.compile("(?i)(\\\\\\\\\\?\\\\hid#[^#\\s]+#)([^#\\s]+)(#\\{[0-9A-F-]+\\})");

    private static final Pattern REDACTION_TAG_PATTERN =
            Pattern.compile("<[A-Z0-9_]+>");

    private final String defaultUsername;
    private final String defaultHostname;

    public DiagnosticSanitizer() {
        this(resolveSystemUsername(), resolveSystemHostname());
    }

    public DiagnosticSanitizer(String defaultUsername, String defaultHostname) {
        this.defaultUsername = defaultUsername;
        this.defaultHostname = defaultHostname;
    }

    public String sanitizeText(String input) {
        return sanitizeText(input, defaultUsername, defaultHostname);
    }

    public String sanitizeText(String input, String explicitUsername, String explicitHostname) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        String result = input;

        // 1. Tokens e credenciais
        result = TOKEN_GHP_PATTERN.matcher(result).replaceAll("<TOKEN_REDACTED>");
        result = TOKEN_GH_PAT_PATTERN.matcher(result).replaceAll("<TOKEN_REDACTED>");
        result = BEARER_PATTERN.matcher(result).replaceAll("<TOKEN_REDACTED>");
        result = KEY_SECRET_PATTERN.matcher(result).replaceAll("<TOKEN_REDACTED>");

        // 2. Endereços de e-mail
        result = EMAIL_PATTERN.matcher(result).replaceAll("<EMAIL_REDACTED>");

        // 3. Caminhos de perfil de usuário Windows (C:\Users\<nome>\... ou C:/Users/<nome>/...)
        result = WINDOWS_USER_HOME_PATTERN.matcher(result).replaceAll("$1<USER>");

        // 4. Endereços MAC Bluetooth e fragmentos PnP de Bluetooth
        result = BT_MAC_SEPARATED_PATTERN.matcher(result).replaceAll("<BT_MAC_REDACTED>");
        result = PNP_DEV_MAC_PATTERN.matcher(result).replaceAll("$1<BT_MAC_REDACTED>");
        result = PNP_BT_DEVICE_MAC_PATTERN.matcher(result).replaceAll("$1<BT_MAC_REDACTED>");
        result = PNP_AMP_MAC_PATTERN.matcher(result).replaceAll("$1<BT_MAC_REDACTED>$3");

        // 5. Campos explícitos Serial=... / serialNumber=... e segmentos de serial/contêiner em Device Path HID
        result = SERIAL_FIELD_PATTERN.matcher(result).replaceAll("$1<SERIAL_REDACTED>");
        result = sanitizeHidDevicePaths(result);

        // 6. Nome de usuário e hostname explícitos (comprimento >= 3) fora de marcadores <...>
        if (explicitUsername != null) {
            String trimmedUser = explicitUsername.trim();
            if (trimmedUser.length() >= 3) {
                result = replaceIdentifierOutsideTags(result, trimmedUser, "<USER>");
            }
        }
        if (explicitHostname != null) {
            String trimmedHost = explicitHostname.trim();
            if (trimmedHost.length() >= 3) {
                result = replaceIdentifierOutsideTags(result, trimmedHost, "<HOST>");
            }
        }

        return result;
    }

    private static String sanitizeHidDevicePaths(String input) {
        Matcher matcher = HID_DEVICE_PATH_PATTERN.matcher(input);
        StringBuilder sb = new StringBuilder(input.length());
        while (matcher.find()) {
            String prefix = matcher.group(1);
            String instanceSegment = matcher.group(2);
            String guidSuffix = matcher.group(3);
            String maskedInstance = maskHidInstanceSegment(instanceSegment);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(prefix + maskedInstance + guidSuffix));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String maskHidInstanceSegment(String instanceSegment) {
        if (instanceSegment == null || instanceSegment.isEmpty()) {
            return instanceSegment;
        }
        if (!instanceSegment.contains("&")) {
            return "<SERIAL_REDACTED>";
        }
        String[] parts = instanceSegment.split("&", -1);
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (part.contains("<") && part.contains(">")) {
                continue;
            }
            if (isSerialOrContainerSegment(part)) {
                parts[i] = "<SERIAL_REDACTED>";
            }
        }
        return String.join("&", parts);
    }

    private static boolean isSerialOrContainerSegment(String segment) {
        if (segment == null || segment.isEmpty()) {
            return false;
        }
        if (segment.length() >= 5) {
            return true;
        }
        return segment.length() >= 4 && !segment.chars().allMatch(Character::isDigit);
    }

    private static String replaceIdentifierOutsideTags(String input, String identifier, String replacement) {
        Pattern idPattern = Pattern.compile(
                "(?<![A-Za-z0-9])" + Pattern.quote(identifier) + "(?![A-Za-z0-9])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
        );
        Matcher tagMatcher = REDACTION_TAG_PATTERN.matcher(input);
        StringBuilder out = new StringBuilder(input.length());
        int lastEnd = 0;
        while (tagMatcher.find()) {
            if (tagMatcher.start() > lastEnd) {
                String plain = input.substring(lastEnd, tagMatcher.start());
                out.append(idPattern.matcher(plain).replaceAll(Matcher.quoteReplacement(replacement)));
            }
            out.append(tagMatcher.group());
            lastEnd = tagMatcher.end();
        }
        if (lastEnd < input.length()) {
            String tail = input.substring(lastEnd);
            out.append(idPattern.matcher(tail).replaceAll(Matcher.quoteReplacement(replacement)));
        }
        return out.toString();
    }

    /**
     * Serializa apenas propriedades técnicas não sensíveis das configurações do aplicativo
     * (omitindo {@code windowX}, {@code windowY}, caminhos pessoais e propriedades arbitrárias).
     */
    public static String sanitizeSettings(AppSettings settings) {
        if (settings == null) {
            return "# Configurações indisponíveis\n";
        }
        Map<String, String> safe = new LinkedHashMap<>();
        safe.put("language", settings.getLanguage().getCode());
        safe.put("pollIntervalSeconds", Integer.toString(settings.getPollIntervalSeconds()));
        safe.put("ecoModeEnabled", Boolean.toString(settings.isEcoModeEnabled()));
        safe.put("readOnlyHidMode", Boolean.toString(settings.isReadOnlyHidMode()));
        safe.put("gameSirMonitoringEnabled", Boolean.toString(settings.isGameSirMonitoringEnabled()));
        safe.put("gameSirSafetyNoticeAcknowledged", Boolean.toString(settings.isGameSirSafetyNoticeAcknowledged()));
        safe.put("displayMode", settings.getDisplayMode().name());
        safe.put("darkTheme", Boolean.toString(settings.isDarkTheme()));
        safe.put("lowBatteryThreshold", Integer.toString(settings.getLowBatteryThreshold()));
        safe.put("mediumBatteryThreshold", Integer.toString(settings.getMediumBatteryThreshold()));
        safe.put("notificationsEnabled", Boolean.toString(settings.isNotificationsEnabled()));
        safe.put("historyEnabled", Boolean.toString(settings.isHistoryEnabled()));
        safe.put("historyRetentionDays", Integer.toString(settings.getHistoryRetentionDays()));
        safe.put("autoCheckUpdates", Boolean.toString(settings.isAutoCheckUpdates()));
        safe.put("startWithWindows", Boolean.toString(settings.isStartWithWindows()));
        safe.put("startMinimized", Boolean.toString(settings.isStartMinimized()));
        safe.put("minimizeToTray", Boolean.toString(settings.isMinimizeToTray()));
        safe.put("simulationEnabled", Boolean.toString(settings.isSimulationEnabled()));
        safe.put("showDisconnectedDevices", Boolean.toString(settings.isShowDisconnectedDevices()));
        safe.put("taskbarBackgroundPlate", Boolean.toString(settings.isTaskbarBackgroundPlate()));
        safe.put("taskbarIconSize", Integer.toString(settings.getTaskbarIconSize()));
        safe.put("taskbarFontSize", Integer.toString(settings.getTaskbarFontSize()));
        safe.put("taskbarSpacing", Integer.toString(settings.getTaskbarSpacing()));
        safe.put("taskbarOffsetX", Integer.toString(settings.getTaskbarOffsetX()));
        safe.put("hiddenDeviceIds", String.join(",", settings.getHiddenDeviceIds()));

        StringBuilder sb = new StringBuilder();
        sb.append("# Wireless Battery Monitor - Sanitized Technical Settings\n");
        for (Map.Entry<String, String> entry : safe.entrySet()) {
            sb.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        return sb.toString();
    }

    private static String resolveSystemUsername() {
        String prop = System.getProperty("user.name");
        return (prop != null && !prop.isBlank()) ? prop.trim() : "";
    }

    private static String resolveSystemHostname() {
        String comp = System.getenv("COMPUTERNAME");
        if (comp != null && !comp.isBlank()) {
            return comp.trim();
        }
        String host = System.getenv("HOSTNAME");
        return host != null ? host.trim() : "";
    }
}
