package br.com.wirelessbattery.ui;

import br.com.wirelessbattery.config.AppSettings;
import br.com.wirelessbattery.config.DisplayMode;
import br.com.wirelessbattery.config.WindowsStartupManager;
import br.com.wirelessbattery.device.BatteryDevice;
import br.com.wirelessbattery.device.DeviceManager;
import br.com.wirelessbattery.diagnostics.AppLogger;
import br.com.wirelessbattery.model.DeviceReading;
import br.com.wirelessbattery.model.DeviceType;
import java.awt.AWTException;
import java.awt.CheckboxMenuItem;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.ItemEvent;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.SwingUtilities;

/**
 * Modo 1 — Controlador da bandeja do sistema (SystemTray) com ícones individuais por dispositivo,
 * cache de assinatura visual e de menu para evitar piscar, e menu de contexto completo.
 */
public final class TrayDisplayController {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final AppSettings settings;
    private final DeviceManager deviceManager;
    private final AppLogger logger;
    private final Runnable onOpenWindow;
    private final Runnable onRefresh;
    private final Runnable onOpenSettings;
    private final Runnable onToggleTheme;
    private final Runnable onSettingsMutated;
    private final Runnable onExit;

    private final Map<String, TrayIcon> deviceIcons = new LinkedHashMap<>();
    private final Map<String, String> iconSignatures = new HashMap<>();
    private final Map<String, String> menuSignatures = new HashMap<>();
    private final Set<String> lowBatteryNotified = new HashSet<>();
    private boolean active;

    public TrayDisplayController(
            AppSettings settings,
            DeviceManager deviceManager,
            AppLogger logger,
            Runnable onOpenWindow,
            Runnable onRefresh,
            Runnable onOpenSettings,
            Runnable onToggleTheme,
            Runnable onSettingsMutated,
            Runnable onExit
    ) {
        this.settings = settings;
        this.deviceManager = deviceManager;
        this.logger = logger;
        this.onOpenWindow = onOpenWindow;
        this.onRefresh = onRefresh;
        this.onOpenSettings = onOpenSettings;
        this.onToggleTheme = onToggleTheme;
        this.onSettingsMutated = onSettingsMutated;
        this.onExit = onExit;
    }

    public boolean isSupported() {
        try {
            return SystemTray.isSupported();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isActive() {
        return active;
    }

    public boolean hasInstalledIcons() {
        if (!active || !isSupported() || deviceIcons.isEmpty()) {
            return false;
        }
        try {
            TrayIcon[] installed = SystemTray.getSystemTray().getTrayIcons();
            return installed != null && installed.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public void setActive(boolean active) {
        this.active = active;
        if (!active) {
            removeAllIcons();
        }
    }

    /**
     * Força a recriação completa dos ícones na bandeja do sistema (por exemplo, após
     * reinicialização do Windows Explorer ou inicialização tardia da bandeja no boot).
     */
    public void reinstallIcons(List<DeviceReading> displayReadings) {
        removeAllIcons();
        if (active && isSupported()) {
            updateReadings(displayReadings);
        }
    }

    public void updateReadings(List<DeviceReading> displayReadings) {
        if (!isSupported() || !active) {
            removeAllIcons();
            return;
        }

        if (!deviceIcons.isEmpty() && !hasInstalledIcons()) {
            logger.warn("[TRAY] Ícones da bandeja ausentes no SystemTray do Windows — recriando ícones...");
            removeAllIcons();
        }

        List<DeviceReading> items = new ArrayList<>(displayReadings);
        if (items.isEmpty()) {
            syncFallbackIcon();
            return;
        }

        removeIconByKey("__fallback__");

        String currentMenuSig = computeMenuSignature();
        Set<String> validKeys = new HashSet<>();
        for (DeviceReading r : items) {
            String key = r.deviceType() != DeviceType.OTHER
                    ? "slot-" + r.deviceType().name()
                    : r.deviceId();
            validKeys.add(key);

            Integer shownPct = r.hasBatteryPercent()
                    ? r.batteryPercent()
                    : (r.hasLastKnownPercent() ? r.lastKnownPercent() : null);
            boolean dimmed = !r.hasBatteryPercent() && r.hasLastKnownPercent();

            String sig = r.deviceType() + ":" + shownPct + ":" + r.charging() + ":" + dimmed + ":" + r.isSimulated()
                    + ":" + WindowsTaskbarLocator.isWindowsTaskbarLight()
                    + ":" + WindowsTaskbarLocator.getTrayIconPixelSize()
                    + ":" + settings.getLowBatteryThreshold() + ":" + settings.getMediumBatteryThreshold();
            String tooltip = buildTooltip(r);

            TrayIcon icon = deviceIcons.get(key);
            if (icon == null) {
                var img = TrayIconFactory.createMultiResTrayIcon(
                        r.deviceType(), shownPct, r.charging(), dimmed, r.isSimulated(), settings
                );
                icon = createTrayIcon(img, tooltip);
                if (icon != null) {
                    deviceIcons.put(key, icon);
                    iconSignatures.put(key, sig);
                    menuSignatures.put(key, currentMenuSig);
                }
            } else {
                if (!sig.equals(iconSignatures.get(key))) {
                    var img = TrayIconFactory.createMultiResTrayIcon(
                            r.deviceType(), shownPct, r.charging(), dimmed, r.isSimulated(), settings
                    );
                    icon.setImage(img);
                    iconSignatures.put(key, sig);
                }
                icon.setToolTip(tooltip);
                if (!currentMenuSig.equals(menuSignatures.get(key))) {
                    icon.setPopupMenu(buildContextMenu());
                    menuSignatures.put(key, currentMenuSig);
                }
            }

            checkLowBatteryNotification(icon, r);
        }

        List<String> staleKeys = new ArrayList<>();
        for (String existingKey : deviceIcons.keySet()) {
            if (!validKeys.contains(existingKey)) {
                staleKeys.add(existingKey);
            }
        }
        for (String stale : staleKeys) {
            removeIconByKey(stale);
        }
    }

    private void syncFallbackIcon() {
        List<String> toRemove = new ArrayList<>(deviceIcons.keySet());
        toRemove.remove("__fallback__");
        for (String key : toRemove) {
            removeIconByKey(key);
        }
        String currentMenuSig = computeMenuSignature();
        if (!deviceIcons.containsKey("__fallback__")) {
            var img = TrayIconFactory.createMultiResTrayIcon(DeviceType.OTHER, null, false, true, false, settings);
            TrayIcon icon = createTrayIcon(img, "Wireless Battery — Nenhum dispositivo detectado");
            if (icon != null) {
                deviceIcons.put("__fallback__", icon);
                menuSignatures.put("__fallback__", currentMenuSig);
            }
        } else {
            TrayIcon fallback = deviceIcons.get("__fallback__");
            if (fallback != null && !currentMenuSig.equals(menuSignatures.get("__fallback__"))) {
                fallback.setPopupMenu(buildContextMenu());
                menuSignatures.put("__fallback__", currentMenuSig);
            }
        }
    }

    private void checkLowBatteryNotification(TrayIcon icon, DeviceReading r) {
        if (icon == null || r.isSimulated() || !settings.isNotificationsEnabled()) {
            return;
        }
        if (r.hasBatteryPercent() && !r.charging() && r.batteryPercent() <= settings.getLowBatteryThreshold()) {
            if (lowBatteryNotified.add(r.deviceId())) {
                icon.displayMessage(
                        "Bateria baixa — " + r.displayName(),
                        "Nível atual: " + r.batteryPercent() + "%. Conecte o cabo para recarregar.",
                        TrayIcon.MessageType.WARNING
                );
            }
        } else if (r.hasBatteryPercent() && (r.charging() || r.batteryPercent() > settings.getLowBatteryThreshold() + 8)) {
            lowBatteryNotified.remove(r.deviceId());
        }
    }

    private TrayIcon createTrayIcon(java.awt.Image img, String tooltip) {
        TrayIcon icon = new TrayIcon(img, tooltip, buildContextMenu());
        icon.setImageAutoSize(true);
        icon.addActionListener(e -> SwingUtilities.invokeLater(onOpenWindow));
        try {
            SystemTray.getSystemTray().add(icon);
            return icon;
        } catch (AWTException e) {
            logger.warn("Não foi possível adicionar ícone na bandeja do Windows: " + e.getMessage());
            return null;
        }
    }

    private String computeMenuSignature() {
        StringBuilder sb = new StringBuilder();
        sb.append(settings.getDisplayMode()).append('|')
                .append(settings.isStartWithWindows()).append('|')
                .append(settings.isShowDisconnectedDevices()).append('|');
        for (BatteryDevice dev : deviceManager.getRegisteredDevices()) {
            sb.append(dev.getId()).append(':').append(!settings.isDeviceHidden(dev.getId())).append(';');
        }
        return sb.toString();
    }

    private PopupMenu buildContextMenu() {
        PopupMenu menu = new PopupMenu();

        List<BatteryDevice> knownDevices = deviceManager.getRegisteredDevices();
        if (!knownDevices.isEmpty()) {
            for (BatteryDevice dev : knownDevices) {
                boolean visible = !settings.isDeviceHidden(dev.getId());
                CheckboxMenuItem item = new CheckboxMenuItem("Exibir " + dev.getDisplayName(), visible);
                item.addItemListener(e -> SwingUtilities.invokeLater(() -> {
                    boolean show = e.getStateChange() == ItemEvent.SELECTED;
                    settings.setDeviceHidden(dev.getId(), !show);
                    onSettingsMutated.run();
                }));
                menu.add(item);
            }
            menu.addSeparator();
        }

        DisplayMode currentMode = settings.getDisplayMode();
        CheckboxMenuItem modeTray = new CheckboxMenuItem(
                "Mostrar como ícones na bandeja", currentMode == DisplayMode.SYSTEM_TRAY
        );
        modeTray.addItemListener(e -> SwingUtilities.invokeLater(() -> {
            settings.setDisplayMode(DisplayMode.SYSTEM_TRAY);
            onSettingsMutated.run();
        }));

        CheckboxMenuItem modeWidget = new CheckboxMenuItem(
                "Mostrar na barra de tarefas (widget)", currentMode == DisplayMode.TASKBAR_WIDGET
        );
        modeWidget.addItemListener(e -> SwingUtilities.invokeLater(() -> {
            settings.setDisplayMode(DisplayMode.TASKBAR_WIDGET);
            onSettingsMutated.run();
        }));

        CheckboxMenuItem modeBoth = new CheckboxMenuItem(
                "Mostrar em ambos", currentMode == DisplayMode.BOTH
        );
        modeBoth.addItemListener(e -> SwingUtilities.invokeLater(() -> {
            settings.setDisplayMode(DisplayMode.BOTH);
            onSettingsMutated.run();
        }));

        menu.add(modeTray);
        menu.add(modeWidget);
        menu.add(modeBoth);
        menu.addSeparator();

        MenuItem open = new MenuItem("Abrir aplicativo");
        open.addActionListener(e -> SwingUtilities.invokeLater(onOpenWindow));

        MenuItem refresh = new MenuItem("Atualizar agora");
        refresh.addActionListener(e -> onRefresh.run());

        MenuItem cfg = new MenuItem("Abrir configurações...");
        cfg.addActionListener(e -> SwingUtilities.invokeLater(onOpenSettings));

        MenuItem theme = new MenuItem("Alternar tema (Claro/Escuro)");
        theme.addActionListener(e -> SwingUtilities.invokeLater(onToggleTheme));

        CheckboxMenuItem autostart = new CheckboxMenuItem(
                "Iniciar com o Windows", settings.isStartWithWindows()
        );
        autostart.addItemListener(e -> SwingUtilities.invokeLater(() -> {
            boolean enable = e.getStateChange() == ItemEvent.SELECTED;
            settings.setStartWithWindows(enable);
            WindowsStartupManager.applyStartupSetting(enable, logger);
            onSettingsMutated.run();
        }));

        MenuItem exit = new MenuItem("Sair");
        exit.addActionListener(e -> SwingUtilities.invokeLater(onExit));

        menu.add(open);
        menu.add(refresh);
        menu.add(cfg);
        menu.add(theme);
        menu.add(autostart);
        menu.addSeparator();
        menu.add(exit);

        return menu;
    }

    private String buildTooltip(DeviceReading r) {
        StringBuilder sb = new StringBuilder();
        if (r.isSimulated()) {
            sb.append("[Simulação] ");
        }
        sb.append(r.displayName()).append(" — ");
        if (r.hasBatteryPercent()) {
            sb.append(r.batteryPercent()).append("%");
            if (r.charging()) {
                sb.append(" (carregando)");
            }
        } else {
            sb.append(r.status().getLabel());
            if (r.hasLastKnownPercent()) {
                sb.append(" · Última: ").append(r.lastKnownPercent()).append("%");
                if (r.lastKnownAt() != null) {
                    sb.append(" (").append(r.lastKnownAt().format(TIME_FMT)).append(")");
                }
            }
        }
        if (r.connectionLabel() != null
                && !r.connectionLabel().isBlank()
                && !"Sem conexão".equalsIgnoreCase(r.connectionLabel())) {
            sb.append(" [").append(r.connectionLabel()).append("]");
        }
        String text = sb.toString();
        return text.length() > 120 ? text.substring(0, 120) : text;
    }

    private void removeIconByKey(String key) {
        iconSignatures.remove(key);
        menuSignatures.remove(key);
        TrayIcon icon = deviceIcons.remove(key);
        if (icon != null && SystemTray.isSupported()) {
            SystemTray.getSystemTray().remove(icon);
        }
    }

    public void removeAllIcons() {
        iconSignatures.clear();
        menuSignatures.clear();
        if (SystemTray.isSupported()) {
            for (TrayIcon icon : deviceIcons.values()) {
                SystemTray.getSystemTray().remove(icon);
            }
        }
        deviceIcons.clear();
    }
}
