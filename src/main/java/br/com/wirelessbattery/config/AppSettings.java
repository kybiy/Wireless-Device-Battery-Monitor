package br.com.wirelessbattery.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Preferências persistentes do aplicativo salvas localmente em arquivo {@code settings.properties}.
 */
public final class AppSettings {
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
            "hiddenDeviceIds"
    );

    private final Path storagePath;
    private final Map<String, String> extraProperties = new LinkedHashMap<>();
    private boolean startWithWindows = false;
    private boolean startMinimized = false;
    private boolean minimizeToTray = true;
    private int pollIntervalSeconds = 20;
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

        hiddenDeviceIds.clear();
        String hidden = p.getProperty("hiddenDeviceIds", "");
        if (!hidden.isBlank()) {
            Arrays.stream(hidden.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(hiddenDeviceIds::add);
        }

        extraProperties.clear();
        for (String name : p.stringPropertyNames()) {
            if (!KNOWN_KEYS.contains(name)) {
                extraProperties.put(name, p.getProperty(name));
            }
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

    private static boolean parseBool(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    private static int parseInt(Properties p, String key, int def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
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

    private static DisplayMode parseDisplayMode(String raw, DisplayMode def) {
        if (raw == null || raw.isBlank()) return def;
        try {
            return DisplayMode.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}
