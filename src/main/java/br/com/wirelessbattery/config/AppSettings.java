package br.com.wirelessbattery.config;

import br.com.wirelessbattery.i18n.AppLanguage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Preferências persistentes do aplicativo salvas localmente em arquivo {@code settings.properties}.
 */
public final class AppSettings {
    public static final List<String> KNOWN_DEVICE_IDS = List.of(
            "delux-m800-pro",
            "akko-verge-s9",
            "gamesir-cyclone-2",
            "qcy-t13"
    );

    private static final Path DEFAULT_PATH = Path.of(
            System.getProperty("user.home"), ".wireless-battery-java", "settings.properties"
    );

    private static final Set<String> KNOWN_KEYS = Set.of(
            "startWithWindows",
            "startMinimized",
            "minimizeToTray",
            "pollIntervalSeconds",
            "darkTheme",
            "notificationsEnabled",
            "lowBatteryThreshold",
            "mediumBatteryThreshold",
            "displayMode",
            "simulationEnabled",
            "showDisconnectedDevices",
            "taskbarBackgroundPlate",
            "taskbarIconSize",
            "taskbarFontSize",
            "taskbarSpacing",
            "taskbarOffsetX",
            "windowX",
            "windowY",
            "hiddenDeviceIds",
            "language",
            "readOnlyHidMode",
            "gameSirMonitoringEnabled",
            "gameSirSafetyNoticeAcknowledged",
            "ecoModeEnabled",
            "historyEnabled",
            "historyRetentionDays",
            "autoCheckUpdates",
            "lastUpdateCheckEpochMillis",
            "cachedLatestVersion",
            "cachedReleaseUrl",
            "cachedReleaseNotes"
    );

    private final Path storagePath;
    private final Map<String, String> extraProperties = new LinkedHashMap<>();
    private boolean startWithWindows = false;
    private boolean startMinimized = false;
    private boolean minimizeToTray = true;
    private int pollIntervalSeconds = 15;
    private boolean darkTheme = true;
    private boolean notificationsEnabled = true;
    private int lowBatteryThreshold = 20;
    private int mediumBatteryThreshold = 50;
    private DisplayMode displayMode = DisplayMode.TASKBAR_WIDGET;
    private boolean simulationEnabled = false;
    private boolean showDisconnectedDevices = false;
    private boolean taskbarBackgroundPlate = false;
    private int taskbarIconSize = 16;
    private int taskbarFontSize = 12;
    private int taskbarSpacing = 14;
    private int taskbarOffsetX = 8;
    private Integer windowX = null;
    private Integer windowY = null;
    private final Set<String> hiddenDeviceIds = new LinkedHashSet<>();

    // v1.3.0 robustness & community preferences
    private AppLanguage language = AppLanguage.SYSTEM;
    private boolean readOnlyHidMode = false;
    private boolean gameSirMonitoringEnabled = true;
    private boolean gameSirSafetyNoticeAcknowledged = false;
    private boolean ecoModeEnabled = true;
    private boolean historyEnabled = true;
    private int historyRetentionDays = 30;
    private boolean autoCheckUpdates = false;
    private long lastUpdateCheckEpochMillis = 0L;
    private String cachedLatestVersion = "";
    private String cachedReleaseUrl = "";
    private String cachedReleaseNotes = "";

    private final Map<String, Boolean> deviceAlertEnabled = new LinkedHashMap<>();
    private final Map<String, Integer> deviceLowThreshold = new LinkedHashMap<>();
    private final Map<String, Integer> deviceCriticalThreshold = new LinkedHashMap<>();
    private final Map<String, Boolean> deviceSuppressWhenCharging = new LinkedHashMap<>();

    public AppSettings() {
        this(DEFAULT_PATH);
    }

    public AppSettings(Path storagePath) {
        this.storagePath = storagePath;
    }

    public Path getStoragePath() {
        return storagePath;
    }

    public void load() {
        if (!Files.isRegularFile(storagePath)) {
            return;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(storagePath)) {
            p.load(in);
        } catch (IOException e) {
            return;
        }

        startWithWindows = parseBool(p, "startWithWindows", startWithWindows);
        startMinimized = parseBool(p, "startMinimized", startMinimized);
        minimizeToTray = parseBool(p, "minimizeToTray", minimizeToTray);
        setPollIntervalSeconds(parseInt(p, "pollIntervalSeconds", pollIntervalSeconds));
        darkTheme = parseBool(p, "darkTheme", darkTheme);
        notificationsEnabled = parseBool(p, "notificationsEnabled", notificationsEnabled);
        int low = parseInt(p, "lowBatteryThreshold", lowBatteryThreshold);
        int med = parseInt(p, "mediumBatteryThreshold", mediumBatteryThreshold);
        setBatteryThresholds(low, med);
        displayMode = parseDisplayMode(p.getProperty("displayMode"), displayMode);
        simulationEnabled = parseBool(p, "simulationEnabled", simulationEnabled);
        showDisconnectedDevices = parseBool(p, "showDisconnectedDevices", showDisconnectedDevices);
        taskbarBackgroundPlate = parseBool(p, "taskbarBackgroundPlate", taskbarBackgroundPlate);
        setTaskbarIconSize(parseInt(p, "taskbarIconSize", taskbarIconSize));
        setTaskbarFontSize(parseInt(p, "taskbarFontSize", taskbarFontSize));
        setTaskbarSpacing(parseInt(p, "taskbarSpacing", taskbarSpacing));
        setTaskbarOffsetX(parseInt(p, "taskbarOffsetX", taskbarOffsetX));
        windowX = parseOptionalInt(p, "windowX");
        windowY = parseOptionalInt(p, "windowY");

        language = AppLanguage.fromCode(p.getProperty("language"));
        readOnlyHidMode = parseBool(p, "readOnlyHidMode", readOnlyHidMode);
        gameSirMonitoringEnabled = parseBool(p, "gameSirMonitoringEnabled", gameSirMonitoringEnabled);
        gameSirSafetyNoticeAcknowledged = parseBool(p, "gameSirSafetyNoticeAcknowledged", gameSirSafetyNoticeAcknowledged);
        ecoModeEnabled = parseBool(p, "ecoModeEnabled", ecoModeEnabled);
        historyEnabled = parseBool(p, "historyEnabled", historyEnabled);
        setHistoryRetentionDays(parseInt(p, "historyRetentionDays", historyRetentionDays));
        autoCheckUpdates = parseBool(p, "autoCheckUpdates", autoCheckUpdates);
        lastUpdateCheckEpochMillis = parseLong(p, "lastUpdateCheckEpochMillis", 0L);
        cachedLatestVersion = safeString(p.getProperty("cachedLatestVersion"));
        cachedReleaseUrl = safeString(p.getProperty("cachedReleaseUrl"));
        cachedReleaseNotes = safeString(p.getProperty("cachedReleaseNotes"));

        hiddenDeviceIds.clear();
        String hidden = p.getProperty("hiddenDeviceIds", "");
        if (!hidden.isBlank()) {
            Arrays.stream(hidden.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(hiddenDeviceIds::add);
        }

        deviceAlertEnabled.clear();
        deviceLowThreshold.clear();
        deviceCriticalThreshold.clear();
        deviceSuppressWhenCharging.clear();
        extraProperties.clear();

        for (String name : p.stringPropertyNames()) {
            if (name.startsWith("alert.")) {
                parseAlertProperty(name, p.getProperty(name));
            } else if (!KNOWN_KEYS.contains(name)) {
                extraProperties.put(name, p.getProperty(name));
            }
        }
    }

    private void parseAlertProperty(String key, String value) {
        if (key.endsWith(".enabled")) {
            String id = key.substring("alert.".length(), key.length() - ".enabled".length());
            deviceAlertEnabled.put(id, Boolean.parseBoolean(value.trim()));
        } else if (key.endsWith(".low")) {
            String id = key.substring("alert.".length(), key.length() - ".low".length());
            setDeviceLowThreshold(id, parseIntString(value, lowBatteryThreshold));
        } else if (key.endsWith(".critical")) {
            String id = key.substring("alert.".length(), key.length() - ".critical".length());
            setDeviceCriticalThreshold(id, parseIntString(value, 10));
        } else if (key.endsWith(".suppressCharging")) {
            String id = key.substring("alert.".length(), key.length() - ".suppressCharging".length());
            deviceSuppressWhenCharging.put(id, Boolean.parseBoolean(value.trim()));
        }
    }

    public void save() throws IOException {
        Path parent = storagePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Properties p = new Properties();
        for (Map.Entry<String, String> entry : extraProperties.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                p.setProperty(entry.getKey(), entry.getValue());
            }
        }
        p.setProperty("startWithWindows", Boolean.toString(startWithWindows));
        p.setProperty("startMinimized", Boolean.toString(startMinimized));
        p.setProperty("minimizeToTray", Boolean.toString(minimizeToTray));
        p.setProperty("pollIntervalSeconds", Integer.toString(pollIntervalSeconds));
        p.setProperty("darkTheme", Boolean.toString(darkTheme));
        p.setProperty("notificationsEnabled", Boolean.toString(notificationsEnabled));
        p.setProperty("lowBatteryThreshold", Integer.toString(lowBatteryThreshold));
        p.setProperty("mediumBatteryThreshold", Integer.toString(mediumBatteryThreshold));
        p.setProperty("displayMode", displayMode.name());
        p.setProperty("simulationEnabled", Boolean.toString(simulationEnabled));
        p.setProperty("showDisconnectedDevices", Boolean.toString(showDisconnectedDevices));
        p.setProperty("taskbarBackgroundPlate", Boolean.toString(taskbarBackgroundPlate));
        p.setProperty("taskbarIconSize", Integer.toString(taskbarIconSize));
        p.setProperty("taskbarFontSize", Integer.toString(taskbarFontSize));
        p.setProperty("taskbarSpacing", Integer.toString(taskbarSpacing));
        p.setProperty("taskbarOffsetX", Integer.toString(taskbarOffsetX));
        if (windowX != null && windowY != null) {
            p.setProperty("windowX", Integer.toString(windowX));
            p.setProperty("windowY", Integer.toString(windowY));
        }
        p.setProperty("hiddenDeviceIds", hiddenDeviceIds.stream().collect(Collectors.joining(",")));

        p.setProperty("language", language.getCode());
        p.setProperty("readOnlyHidMode", Boolean.toString(readOnlyHidMode));
        p.setProperty("gameSirMonitoringEnabled", Boolean.toString(gameSirMonitoringEnabled));
        p.setProperty("gameSirSafetyNoticeAcknowledged", Boolean.toString(gameSirSafetyNoticeAcknowledged));
        p.setProperty("ecoModeEnabled", Boolean.toString(ecoModeEnabled));
        p.setProperty("historyEnabled", Boolean.toString(historyEnabled));
        p.setProperty("historyRetentionDays", Integer.toString(historyRetentionDays));
        p.setProperty("autoCheckUpdates", Boolean.toString(autoCheckUpdates));
        p.setProperty("lastUpdateCheckEpochMillis", Long.toString(lastUpdateCheckEpochMillis));
        if (!cachedLatestVersion.isEmpty()) {
            p.setProperty("cachedLatestVersion", cachedLatestVersion);
        }
        if (!cachedReleaseUrl.isEmpty()) {
            p.setProperty("cachedReleaseUrl", cachedReleaseUrl);
        }
        if (!cachedReleaseNotes.isEmpty()) {
            p.setProperty("cachedReleaseNotes", cachedReleaseNotes);
        }

        for (Map.Entry<String, Boolean> e : deviceAlertEnabled.entrySet()) {
            p.setProperty("alert." + e.getKey() + ".enabled", Boolean.toString(e.getValue()));
        }
        for (Map.Entry<String, Integer> e : deviceLowThreshold.entrySet()) {
            p.setProperty("alert." + e.getKey() + ".low", Integer.toString(e.getValue()));
        }
        for (Map.Entry<String, Integer> e : deviceCriticalThreshold.entrySet()) {
            p.setProperty("alert." + e.getKey() + ".critical", Integer.toString(e.getValue()));
        }
        for (Map.Entry<String, Boolean> e : deviceSuppressWhenCharging.entrySet()) {
            p.setProperty("alert." + e.getKey() + ".suppressCharging", Boolean.toString(e.getValue()));
        }

        try (OutputStream out = Files.newOutputStream(storagePath)) {
            p.store(out, "Wireless Battery Monitor - Configurações do Usuário");
        }
    }

    public boolean isStartWithWindows() { return startWithWindows; }
    public void setStartWithWindows(boolean startWithWindows) { this.startWithWindows = startWithWindows; }

    public boolean isStartMinimized() { return startMinimized; }
    public void setStartMinimized(boolean startMinimized) { this.startMinimized = startMinimized; }

    public boolean isMinimizeToTray() { return minimizeToTray; }
    public void setMinimizeToTray(boolean minimizeToTray) { this.minimizeToTray = minimizeToTray; }

    public int getPollIntervalSeconds() { return pollIntervalSeconds; }
    public void setPollIntervalSeconds(int seconds) {
        this.pollIntervalSeconds = Math.max(5, Math.min(600, seconds));
    }

    public boolean isDarkTheme() { return darkTheme; }
    public void setDarkTheme(boolean darkTheme) { this.darkTheme = darkTheme; }

    public boolean isNotificationsEnabled() { return notificationsEnabled; }
    public void setNotificationsEnabled(boolean notificationsEnabled) { this.notificationsEnabled = notificationsEnabled; }

    public int getLowBatteryThreshold() { return lowBatteryThreshold; }
    public int getMediumBatteryThreshold() { return mediumBatteryThreshold; }

    public void setBatteryThresholds(int low, int medium) {
        int clampedLow = Math.max(5, Math.min(45, low));
        int clampedMed = Math.max(clampedLow + 5, Math.min(95, medium));
        this.lowBatteryThreshold = clampedLow;
        this.mediumBatteryThreshold = clampedMed;
    }

    public DisplayMode getDisplayMode() { return displayMode; }
    public void setDisplayMode(DisplayMode displayMode) {
        this.displayMode = displayMode != null ? displayMode : DisplayMode.TASKBAR_WIDGET;
    }

    public boolean isSimulationEnabled() { return simulationEnabled; }
    public void setSimulationEnabled(boolean simulationEnabled) { this.simulationEnabled = simulationEnabled; }

    public boolean isShowDisconnectedDevices() { return showDisconnectedDevices; }
    public void setShowDisconnectedDevices(boolean showDisconnectedDevices) {
        this.showDisconnectedDevices = showDisconnectedDevices;
    }

    public boolean isTaskbarBackgroundPlate() { return taskbarBackgroundPlate; }
    public void setTaskbarBackgroundPlate(boolean taskbarBackgroundPlate) {
        this.taskbarBackgroundPlate = taskbarBackgroundPlate;
    }

    public int getTaskbarIconSize() { return taskbarIconSize; }
    public void setTaskbarIconSize(int taskbarIconSize) {
        this.taskbarIconSize = Math.max(12, Math.min(24, taskbarIconSize));
    }

    public int getTaskbarFontSize() { return taskbarFontSize; }
    public void setTaskbarFontSize(int taskbarFontSize) {
        this.taskbarFontSize = Math.max(10, Math.min(18, taskbarFontSize));
    }

    public int getTaskbarSpacing() { return taskbarSpacing; }
    public void setTaskbarSpacing(int taskbarSpacing) {
        this.taskbarSpacing = Math.max(4, Math.min(32, taskbarSpacing));
    }

    public int getTaskbarOffsetX() { return taskbarOffsetX; }
    public void setTaskbarOffsetX(int taskbarOffsetX) {
        this.taskbarOffsetX = Math.max(0, Math.min(300, taskbarOffsetX));
    }

    public Integer getWindowX() { return windowX; }
    public Integer getWindowY() { return windowY; }
    public void setWindowPosition(Integer x, Integer y) {
        this.windowX = x;
        this.windowY = y;
    }

    public boolean isDeviceHidden(String deviceId) {
        return hiddenDeviceIds.contains(deviceId);
    }

    public void setDeviceHidden(String deviceId, boolean hidden) {
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        if (hidden) {
            hiddenDeviceIds.add(deviceId);
        } else {
            hiddenDeviceIds.remove(deviceId);
        }
    }

    public Set<String> getHiddenDeviceIds() {
        return Collections.unmodifiableSet(hiddenDeviceIds);
    }

    public AppLanguage getLanguage() { return language; }
    public void setLanguage(AppLanguage language) {
        this.language = language != null ? language : AppLanguage.SYSTEM;
    }

    public boolean isReadOnlyHidMode() { return readOnlyHidMode; }
    public void setReadOnlyHidMode(boolean readOnlyHidMode) { this.readOnlyHidMode = readOnlyHidMode; }

    public boolean isGameSirMonitoringEnabled() { return gameSirMonitoringEnabled; }
    public void setGameSirMonitoringEnabled(boolean enabled) { this.gameSirMonitoringEnabled = enabled; }

    public boolean isGameSirSafetyNoticeAcknowledged() { return gameSirSafetyNoticeAcknowledged; }
    public void setGameSirSafetyNoticeAcknowledged(boolean ack) { this.gameSirSafetyNoticeAcknowledged = ack; }

    public boolean isEcoModeEnabled() { return ecoModeEnabled; }
    public void setEcoModeEnabled(boolean ecoModeEnabled) { this.ecoModeEnabled = ecoModeEnabled; }

    public boolean isHistoryEnabled() { return historyEnabled; }
    public void setHistoryEnabled(boolean historyEnabled) { this.historyEnabled = historyEnabled; }

    public int getHistoryRetentionDays() { return historyRetentionDays; }
    public void setHistoryRetentionDays(int days) {
        if (days <= 7) {
            this.historyRetentionDays = 7;
        } else if (days <= 30) {
            this.historyRetentionDays = 30;
        } else {
            this.historyRetentionDays = 90;
        }
    }

    public boolean isAutoCheckUpdates() { return autoCheckUpdates; }
    public void setAutoCheckUpdates(boolean autoCheckUpdates) { this.autoCheckUpdates = autoCheckUpdates; }

    public long getLastUpdateCheckEpochMillis() { return lastUpdateCheckEpochMillis; }
    public void setLastUpdateCheckEpochMillis(long epochMillis) {
        this.lastUpdateCheckEpochMillis = Math.max(0L, epochMillis);
    }

    public String getCachedLatestVersion() { return cachedLatestVersion; }
    public void setCachedLatestVersion(String cachedLatestVersion) {
        this.cachedLatestVersion = safeString(cachedLatestVersion);
    }

    public String getCachedReleaseUrl() { return cachedReleaseUrl; }
    public void setCachedReleaseUrl(String cachedReleaseUrl) {
        this.cachedReleaseUrl = safeString(cachedReleaseUrl);
    }

    public String getCachedReleaseNotes() { return cachedReleaseNotes; }
    public void setCachedReleaseNotes(String cachedReleaseNotes) {
        this.cachedReleaseNotes = safeString(cachedReleaseNotes);
    }

    public boolean isDeviceAlertEnabled(String deviceId) {
        return deviceId == null ? notificationsEnabled : deviceAlertEnabled.getOrDefault(deviceId, true);
    }

    public void setDeviceAlertEnabled(String deviceId, boolean enabled) {
        if (deviceId != null && !deviceId.isBlank()) {
            deviceAlertEnabled.put(deviceId, enabled);
        }
    }

    public int getDeviceLowThreshold(String deviceId) {
        return deviceId == null ? lowBatteryThreshold : deviceLowThreshold.getOrDefault(deviceId, lowBatteryThreshold);
    }

    public void setDeviceLowThreshold(String deviceId, int threshold) {
        if (deviceId != null && !deviceId.isBlank()) {
            deviceLowThreshold.put(deviceId, Math.max(5, Math.min(50, threshold)));
        }
    }

    public int getDeviceCriticalThreshold(String deviceId) {
        int low = getDeviceLowThreshold(deviceId);
        int raw = deviceId == null ? 10 : deviceCriticalThreshold.getOrDefault(deviceId, Math.min(10, low));
        return Math.max(1, Math.min(low, raw));
    }

    public void setDeviceCriticalThreshold(String deviceId, int threshold) {
        if (deviceId != null && !deviceId.isBlank()) {
            int low = getDeviceLowThreshold(deviceId);
            deviceCriticalThreshold.put(deviceId, Math.max(1, Math.min(low, threshold)));
        }
    }

    public boolean isSuppressAlertWhenCharging(String deviceId) {
        return deviceId == null ? true : deviceSuppressWhenCharging.getOrDefault(deviceId, true);
    }

    public void setSuppressAlertWhenCharging(String deviceId, boolean suppress) {
        if (deviceId != null && !deviceId.isBlank()) {
            deviceSuppressWhenCharging.put(deviceId, suppress);
        }
    }

    public void resetAlertDefaults() {
        notificationsEnabled = true;
        lowBatteryThreshold = 20;
        mediumBatteryThreshold = 50;
        deviceAlertEnabled.clear();
        deviceLowThreshold.clear();
        deviceCriticalThreshold.clear();
        deviceSuppressWhenCharging.clear();
    }

    private static boolean parseBool(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    private static int parseInt(Properties p, String key, int def) {
        return parseIntString(p.getProperty(key), def);
    }

    private static int parseIntString(String v, int def) {
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long parseLong(Properties p, String key, long def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Integer parseOptionalInt(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) return null;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String safeString(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private static DisplayMode parseDisplayMode(String raw, DisplayMode def) {
        if (raw == null || raw.isBlank()) return def;
        try {
            return DisplayMode.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}
