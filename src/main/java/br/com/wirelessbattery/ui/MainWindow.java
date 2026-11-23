package br.com.wirelessbattery.ui;

import br.com.wirelessbattery.config.AppSettings;
import br.com.wirelessbattery.config.DisplayMode;
import br.com.wirelessbattery.config.WindowsStartupManager;
import br.com.wirelessbattery.device.DeviceManager;
import br.com.wirelessbattery.diagnostics.AppLogger;
import br.com.wirelessbattery.diagnostics.HidDiagnosticsService;
import br.com.wirelessbattery.model.ConnectionStatus;
import br.com.wirelessbattery.model.DeviceReading;
import br.com.wirelessbattery.simulation.SimulationManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * Janela principal compacta e minimalista do Wireless Battery Monitor (560x450),
 * desenhada conforme o layout de referência do Windows 11.
 */
public final class MainWindow extends JFrame {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final AppSettings settings;
    private final ThemeManager themeManager;
    private final DeviceManager deviceManager;
    private final SimulationManager simulationManager;
    private final HidDiagnosticsService diagnosticsService;
    private final AppLogger logger;
    private final Runnable onExitCallback;

    private final JPanel rootPanel = new JPanel(new BorderLayout(0, 0));
    private final JLabel headerBatteryIcon = new JLabel();
    private final JLabel titleLabel = new JLabel("Wireless Battery");
    private final JLabel subtitleLabel = new JLabel("Monitor de dispositivos");
    private final IconButton refreshButton;
    private final IconButton settingsButton;
    private final JPanel headerDivider = new JPanel();

    private final JPanel cardsListPanel = new JPanel();
    private final Map<String, DeviceCardPanel> cardMap = new LinkedHashMap<>();

    private final JLabel updatedStatusLabel = new JLabel("Atualizado agora");
    private final JLabel diagnosticsLink = new JLabel("Diagnóstico");
    private final JLabel simulationAdjustLink = new JLabel("Simulação");
    private final JLabel deviceCountLabel = new JLabel("0 dispositivos");
    private final JLabel simulationCaptionLabel = new JLabel(
            "Modo de simulação ativo — valores fictícios, separados das leituras reais."
    );

    private final TrayDisplayController trayController;
    private final TaskbarWidgetWindow taskbarWidget;

    public MainWindow(
            AppSettings settings,
            ThemeManager themeManager,
            DeviceManager deviceManager,
            SimulationManager simulationManager,
            HidDiagnosticsService diagnosticsService,
            AppLogger logger,
            Runnable onExitCallback
    ) {
        super("Wireless Battery");
        this.settings = settings;
        this.themeManager = themeManager;
        this.deviceManager = deviceManager;
        this.simulationManager = simulationManager;
        this.diagnosticsService = diagnosticsService;
        this.logger = logger;
        this.onExitCallback = onExitCallback;

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(480, 360));
        setPreferredSize(new Dimension(560, 492));

        this.refreshButton = new IconButton(DeviceIconFactory.IconName.REFRESH, "Atualizar agora", () -> {
            updatedStatusLabel.setText("Atualizando...");
            deviceManager.refreshNow();
        });
        this.settingsButton = new IconButton(DeviceIconFactory.IconName.SLIDERS, "Configurações", () -> openSettingsDialog(false));

        this.trayController = new TrayDisplayController(
                settings,
                deviceManager,
                logger,
                this::restoreFromTray,
                deviceManager::refreshNow,
                () -> openSettingsDialog(false),
                this::toggleTheme,
                this::handleSettingsChangedFromTray,
                this::exitApplication
        );

        this.taskbarWidget = new TaskbarWidgetWindow(
                settings,
                themeManager,
                this::restoreFromTray,
                deviceManager::refreshNow,
                () -> openSettingsDialog(false),
                this::exitApplication
        );
        this.taskbarWidget.setOnAvailabilityChanged(this::syncExternalIndicators);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                rememberWindowPosition();
                if (settings.isMinimizeToTray() && trayController.isSupported()) {
                    minimizeToTray();
                } else {
                    exitApplication();
                }
            }
        });

        rootPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        rootPanel.add(buildHeader(), BorderLayout.NORTH);
        rootPanel.add(buildCenterCardsArea(), BorderLayout.CENTER);
        rootPanel.add(buildFooter(), BorderLayout.SOUTH);
        setContentPane(rootPanel);

        bindListeners();
        applyCurrentSettings(false);
        refreshDisplayedCards();

        pack();
        restoreWindowPosition();
    }

    private JPanel buildHeader() {
        JPanel headerWrap = new JPanel(new BorderLayout(0, 12));
        headerWrap.setOpaque(false);
        headerWrap.setBorder(BorderFactory.createEmptyBorder(16, 22, 6, 22));

        JPanel topRow = new JPanel(new BorderLayout(12, 0));
        topRow.setOpaque(false);

        JPanel brandGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        brandGroup.setOpaque(false);

        JPanel titleStack = new JPanel();
        titleStack.setOpaque(false);
        titleStack.setLayout(new BoxLayout(titleStack, BoxLayout.Y_AXIS));
        titleLabel.setFont(ThemeManager.fontBold(16.5f));
        subtitleLabel.setFont(ThemeManager.fontRegular(12f));
        titleStack.add(titleLabel);
        titleStack.add(Box.createVerticalStrut(1));
        titleStack.add(subtitleLabel);

        brandGroup.add(headerBatteryIcon);
        brandGroup.add(titleStack);

        JPanel actionGroup = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        actionGroup.setOpaque(false);
        actionGroup.add(refreshButton);
        actionGroup.add(settingsButton);

        topRow.add(brandGroup, BorderLayout.WEST);
        topRow.add(actionGroup, BorderLayout.EAST);

        headerDivider.setPreferredSize(new Dimension(100, 1));
        headerWrap.add(topRow, BorderLayout.CENTER);
        headerWrap.add(headerDivider, BorderLayout.SOUTH);
        return headerWrap;
    }

    private JScrollPane buildCenterCardsArea() {
        cardsListPanel.setOpaque(false);
        cardsListPanel.setLayout(new BoxLayout(cardsListPanel, BoxLayout.Y_AXIS));
        cardsListPanel.setBorder(BorderFactory.createEmptyBorder(10, 22, 6, 22));

        JScrollPane scroll = new JScrollPane(cardsListPanel);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(14);
        return scroll;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel();
        footer.setOpaque(false);
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        footer.setBorder(BorderFactory.createEmptyBorder(6, 22, 16, 22));

        JPanel statusRow = new JPanel(new BorderLayout(8, 0));
        statusRow.setOpaque(false);

        JPanel leftLinks = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        leftLinks.setOpaque(false);
        updatedStatusLabel.setFont(ThemeManager.fontRegular(12f));

        diagnosticsLink.setFont(ThemeManager.fontRegular(12f));
        diagnosticsLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        diagnosticsLink.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));
        diagnosticsLink.setToolTipText("Abrir ferramenta de diagnóstico HID (somente leitura)");
        diagnosticsLink.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                new HidDiagnosticsDialog(MainWindow.this, diagnosticsService).setVisible(true);
            }
        });

        simulationAdjustLink.setFont(ThemeManager.fontRegular(12f));
        simulationAdjustLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        simulationAdjustLink.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));
        simulationAdjustLink.setToolTipText("Ajustar valores do modo de simulação");
        simulationAdjustLink.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                openSettingsDialog(true);
            }
        });

        leftLinks.add(updatedStatusLabel);
        leftLinks.add(diagnosticsLink);
        leftLinks.add(simulationAdjustLink);

        deviceCountLabel.setFont(ThemeManager.fontRegular(12f));
        deviceCountLabel.setHorizontalAlignment(SwingConstants.RIGHT);

        statusRow.add(leftLinks, BorderLayout.WEST);
        statusRow.add(deviceCountLabel, BorderLayout.EAST);

        JPanel captionRow = new JPanel(new BorderLayout());
        captionRow.setOpaque(false);
        captionRow.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        simulationCaptionLabel.setFont(ThemeManager.fontRegular(12f));
        captionRow.add(simulationCaptionLabel, BorderLayout.WEST);

        footer.add(statusRow);
        footer.add(captionRow);
        return footer;
    }

    private void bindListeners() {
        deviceManager.addListener(readings -> SwingUtilities.invokeLater(this::refreshDisplayedCards));
        simulationManager.addListener(readings -> SwingUtilities.invokeLater(this::refreshDisplayedCards));
    }

    private void refreshDisplayedCards() {
        boolean simActive = simulationManager.isEnabled();
        simulationCaptionLabel.setVisible(simActive);
        simulationAdjustLink.setText(simActive ? "· Ajustar simulação" : "· Simulação");

        List<DeviceReading> activeList = new ArrayList<>();
        if (simActive) {
            activeList.addAll(simulationManager.getCurrentReadings());
        } else {
            for (DeviceReading r : deviceManager.getCurrentReadings()) {
                if (shouldShowRealReading(r)) {
                    activeList.add(r);
                }
            }
        }

        cardsListPanel.removeAll();
        if (activeList.isEmpty()) {
            cardsListPanel.add(buildEmptyStatePanel());
        } else {
            for (int i = 0; i < activeList.size(); i++) {
                DeviceReading reading = activeList.get(i);
                DeviceCardPanel card = cardMap.get(reading.deviceId());
                if (card == null) {
                    card = new DeviceCardPanel(themeManager, settings, reading);
                    cardMap.put(reading.deviceId(), card);
                } else {
                    card.updateReading(reading);
                }
                cardsListPanel.add(card);
                if (i < activeList.size() - 1) {
                    cardsListPanel.add(Box.createVerticalStrut(10));
                }
            }
        }

        updateFooterSummary(activeList);
        syncExternalIndicators();
        cardsListPanel.revalidate();
        cardsListPanel.repaint();
    }

    private boolean shouldShowRealReading(DeviceReading r) {
        if (settings.isDeviceHidden(r.deviceId())) {
            return false;
        }
        if (settings.isShowDisconnectedDevices()) {
            return true;
        }
        return r.status().isOnline()
                || r.status() == ConnectionStatus.ASLEEP
                || deviceManager.isDeviceDetected(r.deviceId());
    }

    private JPanel buildEmptyStatePanel() {
        JPanel empty = new JPanel();
        empty.setOpaque(false);
        empty.setLayout(new BoxLayout(empty, BoxLayout.Y_AXIS));
        empty.setBorder(BorderFactory.createEmptyBorder(26, 16, 26, 16));

        JLabel icon = new JLabel(DeviceIconFactory.createSvgIcon(
                DeviceIconFactory.IconName.DISCONNECTED, 28, themeManager.mutedText()
        ));
        icon.setAlignmentX(CENTER_ALIGNMENT);

        JLabel title = new JLabel("Nenhum dispositivo detectado");
        title.setFont(ThemeManager.fontBold(14f));
        title.setForeground(themeManager.primaryText());
        title.setAlignmentX(CENTER_ALIGNMENT);

        JLabel sub = new JLabel("Conecte o receptor USB 2.4 GHz ou ative a simulação para testar o visual.");
        sub.setFont(ThemeManager.fontRegular(12f));
        sub.setForeground(themeManager.secondaryText());
        sub.setAlignmentX(CENTER_ALIGNMENT);

        JButton simBtn = new JButton("Ativar modo de simulação");
        simBtn.setAlignmentX(CENTER_ALIGNMENT);
        simBtn.addActionListener(e -> {
            settings.setSimulationEnabled(true);
            simulationManager.setEnabled(true);
            saveSettingsQuietly();
            refreshDisplayedCards();
        });

        empty.add(icon);
        empty.add(Box.createVerticalStrut(10));
        empty.add(title);
        empty.add(Box.createVerticalStrut(4));
        empty.add(sub);
        empty.add(Box.createVerticalStrut(14));
        empty.add(simBtn);
        return empty;
    }

    private void updateFooterSummary(List<DeviceReading> activeList) {
        LocalTime last = deviceManager.getLastPollTime();
        if (last == null || Duration.between(last, LocalTime.now()).getSeconds() < 15) {
            updatedStatusLabel.setText("Atualizado agora");
        } else {
            updatedStatusLabel.setText("Atualizado às " + last.format(TIME_FMT));
        }

        int count = activeList.size();
        String label = count == 1 ? "1 dispositivo" : count + " dispositivos";
        deviceCountLabel.setText(label);
        Color checkColor = count > 0 ? DeviceIconFactory.COLOR_HIGH : themeManager.mutedText();
        deviceCountLabel.setIcon(DeviceIconFactory.createSvgIcon(
                DeviceIconFactory.IconName.CHECK_CIRCLE, 14, checkColor
        ));
    }

    private void syncExternalIndicators() {
        List<DeviceReading> visibleReal = new ArrayList<>();
        for (DeviceReading r : deviceManager.getCurrentReadings()) {
            if (shouldShowRealReading(r)) {
                visibleReal.add(r);
            }
        }

        List<DeviceReading> indicatorItems;
        boolean anyRealDetected = !visibleReal.isEmpty();
        if (!anyRealDetected && simulationManager.isEnabled()) {
            indicatorItems = new ArrayList<>();
            for (DeviceReading r : simulationManager.getCurrentReadings()) {
                if (settings.isShowDisconnectedDevices() || r.hasBatteryPercent() || r.status() == ConnectionStatus.ASLEEP) {
                    indicatorItems.add(r);
                }
            }
        } else {
            indicatorItems = visibleReal;
        }

        DisplayMode mode = settings.getDisplayMode();
        boolean wantTray = mode.usesSystemTray();
        boolean wantTaskbar = mode.usesTaskbarWidget();

        if (wantTaskbar) {
            taskbarWidget.updateReadings(indicatorItems);
            taskbarWidget.setWidgetEnabled(true);
            if (!taskbarWidget.isVisible()) {
                wantTray = true;
            }
        } else {
            taskbarWidget.setWidgetEnabled(false);
        }

        trayController.setActive(wantTray);
        if (wantTray) {
            trayController.updateReadings(indicatorItems);
        }
    }

    private void handleSettingsChangedFromTray() {
        applyCurrentSettings(true);
        saveSettingsQuietly();
    }

    private void openSettingsDialog(boolean openSimulationTab) {
        boolean prevStartup = settings.isStartWithWindows();
        int prevInterval = settings.getPollIntervalSeconds();

        SettingsDialog dialog = new SettingsDialog(
                this,
                settings,
                deviceManager.getRegisteredDevices(),
                simulationManager,
                openSimulationTab
        );
        dialog.setVisible(true);
        if (!dialog.wasSaved()) {
            return;
        }

        if (prevStartup != settings.isStartWithWindows()) {
            WindowsStartupManager.applyStartupSetting(settings.isStartWithWindows(), logger);
        }
        if (prevInterval != settings.getPollIntervalSeconds()) {
            deviceManager.updatePollInterval(settings.getPollIntervalSeconds());
        }
        taskbarWidget.refreshLayoutFromSettings();
        applyCurrentSettings(true);
        saveSettingsQuietly();
    }

    private void applyCurrentSettings(boolean updateUiTheme) {
        ThemeManager.Theme targetTheme = settings.isDarkTheme()
                ? ThemeManager.Theme.DARK
                : ThemeManager.Theme.LIGHT;
        if (updateUiTheme && themeManager.getCurrentTheme() != targetTheme) {
            themeManager.applyTheme(targetTheme);
        }
        simulationManager.setEnabled(settings.isSimulationEnabled());
        applyThemePalette();
        refreshDisplayedCards();
    }

    private void toggleTheme() {
        ThemeManager.Theme next = themeManager.toggleTheme();
        settings.setDarkTheme(next == ThemeManager.Theme.DARK);
        applyThemePalette();
        refreshDisplayedCards();
        saveSettingsQuietly();
        logger.info("Tema alterado para: " + next.getLabel());
    }

    private void applyThemePalette() {
        rootPanel.setBackground(themeManager.windowBackground());
        headerBatteryIcon.setIcon(DeviceIconFactory.createSvgIcon(
                DeviceIconFactory.IconName.BATTERY, 20, themeManager.accentColor()
        ));
        titleLabel.setForeground(themeManager.primaryText());
        subtitleLabel.setForeground(themeManager.secondaryText());
        headerDivider.setBackground(themeManager.subtleBorder());

        updatedStatusLabel.setForeground(themeManager.secondaryText());
        diagnosticsLink.setForeground(themeManager.secondaryText());
        diagnosticsLink.setText("· Diagnóstico");
        simulationAdjustLink.setForeground(themeManager.secondaryText());
        deviceCountLabel.setForeground(themeManager.secondaryText());
        simulationCaptionLabel.setForeground(themeManager.secondaryText());

        refreshButton.refreshIcon();
        settingsButton.refreshIcon();
        for (DeviceCardPanel card : cardMap.values()) {
            card.refreshThemeColors();
        }
        repaint();
    }

    private void rememberWindowPosition() {
        if (isShowing()) {
            Point p = getLocation();
            settings.setWindowPosition(p.x, p.y);
            saveSettingsQuietly();
        }
    }

    private void restoreWindowPosition() {
        Integer x = settings.getWindowX();
        Integer y = settings.getWindowY();
        if (x != null && y != null && isInsideAnyScreen(x, y)) {
            setLocation(x, y);
        } else {
            setLocationRelativeTo(null);
        }
    }

    private static boolean isInsideAnyScreen(int x, int y) {
        var ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (var gd : ge.getScreenDevices()) {
            var b = gd.getDefaultConfiguration().getBounds();
            if (b.contains(x + 40, y + 40)) {
                return true;
            }
        }
        return false;
    }

    public void minimizeToTray() {
        rememberWindowPosition();
        if (trayController.isSupported() && settings.isMinimizeToTray()) {
            setVisible(false);
            logger.info("Aplicativo minimizado para a bandeja.");
        } else {
            setState(ICONIFIED);
        }
    }

    private void restoreFromTray() {
        setVisible(true);
        setState(NORMAL);
        toFront();
        requestFocus();
    }

    private void saveSettingsQuietly() {
        try {
            settings.save();
        } catch (IOException e) {
            logger.error("Não foi possível salvar as configurações", e);
        }
    }

    private void exitApplication() {
        rememberWindowPosition();
        trayController.removeAllIcons();
        taskbarWidget.shutdown();
        dispose();
        onExitCallback.run();
    }

    /**
     * Botão circular/arredondado minimalista de cabeçalho com ícone SVG Lucide.
     */
    private final class IconButton extends JButton {
        private final DeviceIconFactory.IconName iconName;
        private boolean hovered;

        IconButton(DeviceIconFactory.IconName iconName, String tooltip, Runnable action) {
            this.iconName = iconName;
            setToolTipText(tooltip);
            setPreferredSize(new Dimension(30, 30));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addActionListener(e -> action.run());
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hovered = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hovered = false; repaint(); }
            });
            refreshIcon();
        }

        void refreshIcon() {
            Color c = hovered ? themeManager.primaryText() : themeManager.secondaryText();
            setIcon(DeviceIconFactory.createSvgIcon(iconName, 16, c));
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (hovered) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(themeManager.elevatedBackground());
                g2.fill(new RoundRectangle2D.Float(1, 1, getWidth() - 2, getHeight() - 2, 8f, 8f));
                g2.dispose();
            }
            refreshIcon();
            super.paintComponent(g);
        }
    }
}
