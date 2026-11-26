package br.com.wirelessbattery.ui;

import br.com.wirelessbattery.config.AppSettings;
import br.com.wirelessbattery.config.AppVersion;
import br.com.wirelessbattery.config.DisplayMode;
import br.com.wirelessbattery.device.AkkoVergeS9;
import br.com.wirelessbattery.device.BatteryDevice;
import br.com.wirelessbattery.device.DeluxM800Pro;
import br.com.wirelessbattery.device.GameSirCyclone2;
import br.com.wirelessbattery.device.QcyT13Bluetooth;
import br.com.wirelessbattery.history.BatteryHistoryStore;
import br.com.wirelessbattery.i18n.AppLanguage;
import br.com.wirelessbattery.i18n.I18n;
import br.com.wirelessbattery.notification.BatteryAlertManager;
import br.com.wirelessbattery.simulation.SimulationManager;
import br.com.wirelessbattery.update.UpdateCheckerService;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.SpinnerNumberModel;

/**
 * Janela de configurações organizada em 5 abas enxutas:
 * Geral, Segurança e Alertas, Barra e Tema, Histórico e Atualizações, e Simulação.
 */
public final class SettingsDialog extends JDialog {

    private record LanguageOption(AppLanguage language, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private record DeviceAlertControls(
            String deviceId,
            String displayName,
            JCheckBox enabledBox,
            JSpinner lowSpinner,
            JSpinner criticalSpinner,
            JCheckBox suppressChargingBox
    ) {}

    private final AppSettings settings;
    private final SimulationManager simulationManager;
    private final ThemeManager themeManager;
    private final I18n i18n;
    private final BatteryHistoryStore historyStore;
    private final UpdateCheckerService updateService;
    private final BatteryAlertManager alertManager;
    private final BatteryAlertManager.NotificationSink testNotificationSink;

    private final JComboBox<LanguageOption> languageCombo = new JComboBox<>();
    private final JCheckBox startWithWindowsBox;
    private final JCheckBox startMinimizedBox;
    private final JCheckBox minimizeToTrayBox;
    private final JCheckBox showDisconnectedBox;
    private final JCheckBox ecoModeBox;
    private final JComboBox<Integer> pollIntervalCombo;
    private final JSpinner mediumThresholdSpinner;

    private final JCheckBox readOnlyModeBox;
    private final JCheckBox gameSirMonitoringBox;
    private final JCheckBox notificationsBox;
    private final Map<String, DeviceAlertControls> deviceAlertControls = new LinkedHashMap<>();

    private final JComboBox<String> themeCombo;
    private final JComboBox<DisplayMode> displayModeCombo;
    private final JCheckBox taskbarPlateBox;
    private final JSpinner taskbarIconSizeSpinner;
    private final JSpinner taskbarFontSizeSpinner;
    private final JSpinner taskbarSpacingSpinner;
    private final JSpinner taskbarOffsetXSpinner;

    private final JCheckBox historyEnabledBox;
    private final JComboBox<String> historyRetentionCombo = new JComboBox<>();
    private final JCheckBox autoCheckUpdatesBox;

    private final JCheckBox simulationBox;
    private final Map<String, JCheckBox> deviceVisibilityBoxes = new LinkedHashMap<>();

    private boolean saved;

    public SettingsDialog(
            JFrame owner,
            AppSettings settings,
            List<BatteryDevice> realDevices,
            SimulationManager simulationManager,
            boolean openSimulationTab
    ) {
        this(owner, settings, realDevices, simulationManager, openSimulationTab,
                new ThemeManager(), new I18n(settings.getLanguage()), null, null, null, null);
    }

    public SettingsDialog(
            JFrame owner,
            AppSettings settings,
            List<BatteryDevice> realDevices,
            SimulationManager simulationManager,
            boolean openSimulationTab,
            ThemeManager themeManager,
            I18n i18n,
            BatteryHistoryStore historyStore,
            UpdateCheckerService updateService,
            BatteryAlertManager alertManager,
            BatteryAlertManager.NotificationSink testNotificationSink
    ) {
        super(owner, (i18n != null ? i18n : new I18n()).t("settings.title", AppVersion.getVersion()), true);
        this.settings = settings;
        this.simulationManager = simulationManager;
        this.themeManager = themeManager != null ? themeManager : new ThemeManager();
        this.i18n = i18n != null ? i18n : new I18n(settings.getLanguage());
        this.historyStore = historyStore;
        this.updateService = updateService != null ? updateService : new UpdateCheckerService();
        this.alertManager = alertManager != null ? alertManager : new BatteryAlertManager();
        this.testNotificationSink = testNotificationSink;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));
        setMinimumSize(new Dimension(640, 520));

        languageCombo.addItem(new LanguageOption(AppLanguage.SYSTEM, this.i18n.t("settings.language.system")));
        languageCombo.addItem(new LanguageOption(AppLanguage.PT_BR, this.i18n.t("settings.language.ptBR")));
        languageCombo.addItem(new LanguageOption(AppLanguage.EN_US, this.i18n.t("settings.language.enUS")));
        selectLanguage(settings.getLanguage());

        startWithWindowsBox = new JCheckBox(this.i18n.t("settings.general.startWithWindows"), settings.isStartWithWindows());
        startMinimizedBox = new JCheckBox(this.i18n.t("settings.general.startMinimized"), settings.isStartMinimized());
        minimizeToTrayBox = new JCheckBox(this.i18n.t("settings.general.minimizeToTray"), settings.isMinimizeToTray());
        showDisconnectedBox = new JCheckBox(this.i18n.t("settings.general.showDisconnected"), settings.isShowDisconnectedDevices());
        ecoModeBox = new JCheckBox(this.i18n.t("settings.general.ecoMode"), settings.isEcoModeEnabled());

        pollIntervalCombo = new JComboBox<>(new Integer[]{5, 10, 15, 30, 60});
        int currentInterval = settings.getPollIntervalSeconds();
        boolean intervalPresent = false;
        for (int i = 0; i < pollIntervalCombo.getItemCount(); i++) {
            if (pollIntervalCombo.getItemAt(i) == currentInterval) {
                intervalPresent = true;
                break;
            }
        }
        if (!intervalPresent) {
            pollIntervalCombo.addItem(currentInterval);
        }
        pollIntervalCombo.setSelectedItem(currentInterval);

        mediumThresholdSpinner = new JSpinner(new SpinnerNumberModel(settings.getMediumBatteryThreshold(), 25, 95, 5));

        readOnlyModeBox = new JCheckBox(this.i18n.t("settings.safety.readOnlyMode"), settings.isReadOnlyHidMode());
        gameSirMonitoringBox = new JCheckBox(this.i18n.t("settings.safety.enableGameSir"), settings.isGameSirMonitoringEnabled());
        notificationsBox = new JCheckBox(this.i18n.t("settings.alerts.globalEnabled"), settings.isNotificationsEnabled());

        themeCombo = new JComboBox<>(new String[]{
                this.i18n.t("settings.taskbar.themeDark"),
                this.i18n.t("settings.taskbar.themeLight")
        });
        themeCombo.setSelectedIndex(settings.isDarkTheme() ? 0 : 1);
        displayModeCombo = new JComboBox<>(DisplayMode.values());
        displayModeCombo.setSelectedItem(settings.getDisplayMode());
        taskbarPlateBox = new JCheckBox(this.i18n.t("settings.taskbar.plate"), settings.isTaskbarBackgroundPlate());
        taskbarIconSizeSpinner = new JSpinner(new SpinnerNumberModel(settings.getTaskbarIconSize(), 12, 24, 1));
        taskbarFontSizeSpinner = new JSpinner(new SpinnerNumberModel(settings.getTaskbarFontSize(), 10, 18, 1));
        taskbarSpacingSpinner = new JSpinner(new SpinnerNumberModel(settings.getTaskbarSpacing(), 4, 32, 2));
        taskbarOffsetXSpinner = new JSpinner(new SpinnerNumberModel(settings.getTaskbarOffsetX(), 0, 300, 4));

        historyEnabledBox = new JCheckBox(this.i18n.t("settings.history.enabled"), settings.isHistoryEnabled());
        historyRetentionCombo.addItem(this.i18n.t("settings.history.retentionDays", 7));
        historyRetentionCombo.addItem(this.i18n.t("settings.history.retentionDays", 30));
        historyRetentionCombo.addItem(this.i18n.t("settings.history.retentionDays", 90));
        int ret = settings.getHistoryRetentionDays();
        historyRetentionCombo.setSelectedIndex(ret <= 7 ? 0 : (ret <= 30 ? 1 : 2));
        autoCheckUpdatesBox = new JCheckBox(this.i18n.t("settings.updates.autoCheck"), settings.isAutoCheckUpdates());

        simulationBox = new JCheckBox(this.i18n.t("app.simulationBanner"), settings.isSimulationEnabled());
        simulationBox.addActionListener(e -> {
            settings.setSimulationEnabled(simulationBox.isSelected());
            simulationManager.setEnabled(simulationBox.isSelected());
        });

        JTabbedPane tabs = new JTabbedPane();
        tabs.setBorder(BorderFactory.createEmptyBorder(8, 12, 0, 12));
        tabs.addTab(this.i18n.t("settings.tab.general"), buildGeneralTab(realDevices));
        tabs.addTab(this.i18n.t("settings.tab.safetyAlerts"), buildSafetyAndAlertsTab(owner));
        tabs.addTab(this.i18n.t("settings.tab.taskbarTheme"), buildTaskbarAndThemeTab());
        tabs.addTab(this.i18n.t("settings.tab.historyUpdates"), buildHistoryAndUpdatesTab(owner));
        tabs.addTab(this.i18n.t("settings.tab.simulation"), buildSimulationTab());

        if (openSimulationTab) {
            tabs.setSelectedIndex(4);
        }

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 10));
        buttons.setBorder(BorderFactory.createEmptyBorder(0, 12, 6, 12));
        JButton cancelBtn = new JButton(this.i18n.t("settings.cancel"));
        JButton saveBtn = new JButton(this.i18n.t("settings.save"));
        cancelBtn.addActionListener(e -> dispose());
        saveBtn.addActionListener(e -> {
            applyToSettings();
            saved = true;
            dispose();
        });
        buttons.add(cancelBtn);
        buttons.add(saveBtn);

        add(tabs, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(owner);
    }

    private void selectLanguage(AppLanguage target) {
        for (int i = 0; i < languageCombo.getItemCount(); i++) {
            if (languageCombo.getItemAt(i).language() == target) {
                languageCombo.setSelectedIndex(i);
                return;
            }
        }
    }

    private JPanel buildGeneralTab(List<BatteryDevice> realDevices) {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        addLabelField(form, gc, row++, i18n.t("settings.general.language"), languageCombo);
        addFullRow(form, gc, row++, startWithWindowsBox);
        addFullRow(form, gc, row++, startMinimizedBox);
        addFullRow(form, gc, row++, minimizeToTrayBox);
        addFullRow(form, gc, row++, showDisconnectedBox);
        addFullRow(form, gc, row++, ecoModeBox);

        addLabelField(form, gc, row++, i18n.t("settings.general.pollInterval"), pollIntervalCombo);
        addLabelField(form, gc, row++, i18n.t("settings.general.mediumThreshold"), mediumThresholdSpinner);

        JPanel visPanel = new JPanel(new GridLayout(0, 2, 12, 2));
        visPanel.setBorder(BorderFactory.createTitledBorder(i18n.t("settings.general.allowedDevices")));
        for (BatteryDevice device : realDevices) {
            JCheckBox box = new JCheckBox(device.getDisplayName(), !settings.isDeviceHidden(device.getId()));
            deviceVisibilityBoxes.put(device.getId(), box);
            visPanel.add(box);
        }
        addFullRow(form, gc, row, visPanel);
        return form;
    }

    private JPanel buildSafetyAndAlertsTab(JFrame owner) {
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));

        JPanel safetyPanel = new JPanel();
        safetyPanel.setLayout(new BoxLayout(safetyPanel, BoxLayout.Y_AXIS));
        safetyPanel.setBorder(BorderFactory.createTitledBorder(i18n.t("settings.safety.sectionTitle")));
        safetyPanel.add(readOnlyModeBox);
        JLabel hintLabel = new JLabel("<html><body style='width:540px'>" + i18n.t("settings.safety.readOnlyHint") + "</body></html>");
        hintLabel.setFont(ThemeManager.fontRegular(11.5f));
        hintLabel.setBorder(BorderFactory.createEmptyBorder(2, 22, 6, 4));
        safetyPanel.add(hintLabel);
        safetyPanel.add(gameSirMonitoringBox);

        JPanel alertsPanel = new JPanel(new BorderLayout(6, 6));
        alertsPanel.setBorder(BorderFactory.createTitledBorder(i18n.t("settings.alerts.sectionTitle")));
        alertsPanel.add(notificationsBox, BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(3, 4, 3, 4);
        gc.anchor = GridBagConstraints.WEST;

        String[] knownIds = {
                DeluxM800Pro.DEVICE_ID,
                AkkoVergeS9.DEVICE_ID,
                GameSirCyclone2.DEVICE_ID,
                QcyT13Bluetooth.DEVICE_ID
        };
        String[] knownNames = {
                DeluxM800Pro.DISPLAY_NAME,
                "Akko Verge S9 Ultra",
                GameSirCyclone2.DISPLAY_NAME,
                QcyT13Bluetooth.DISPLAY_NAME
        };

        gc.gridy = 0;
        gc.gridx = 0; grid.add(new JLabel("Dispositivo / Device"), gc);
        gc.gridx = 1; grid.add(new JLabel(i18n.t("settings.alerts.low")), gc);
        gc.gridx = 2; grid.add(new JLabel(i18n.t("settings.alerts.critical")), gc);
        gc.gridx = 3; grid.add(new JLabel(i18n.t("settings.alerts.suppressCharging")), gc);
        gc.gridx = 4; grid.add(new JLabel(""), gc);

        for (int i = 0; i < knownIds.length; i++) {
            String id = knownIds[i];
            String name = knownNames[i];
            JCheckBox enabled = new JCheckBox(name, settings.isDeviceAlertEnabled(id));
            int low = settings.getDeviceLowThreshold(id);
            int crit = Math.min(low, settings.getDeviceCriticalThreshold(id));
            JSpinner lowSpin = new JSpinner(new SpinnerNumberModel(low, 5, 50, 1));
            JSpinner critSpin = new JSpinner(new SpinnerNumberModel(crit, 1, 45, 1));
            JCheckBox suppress = new JCheckBox("", settings.isSuppressAlertWhenCharging(id));
            JButton testBtn = new JButton(i18n.t("settings.alerts.testButton"));
            testBtn.addActionListener(e -> {
                int samplePct = (Integer) lowSpin.getValue();
                alertManager.triggerTestNotification(id, name, samplePct, i18n, (dId, title, msg, isCrit) -> {
                    if (testNotificationSink != null) {
                        testNotificationSink.notify(dId, title, msg, isCrit);
                    } else {
                        javax.swing.JOptionPane.showMessageDialog(owner != null ? owner : this, msg, title,
                                javax.swing.JOptionPane.INFORMATION_MESSAGE);
                    }
                });
            });

            DeviceAlertControls ctrl = new DeviceAlertControls(id, name, enabled, lowSpin, critSpin, suppress);
            deviceAlertControls.put(id, ctrl);

            int row = i + 1;
            gc.gridy = row;
            gc.gridx = 0; grid.add(enabled, gc);
            gc.gridx = 1; grid.add(lowSpin, gc);
            gc.gridx = 2; grid.add(critSpin, gc);
            gc.gridx = 3; grid.add(suppress, gc);
            gc.gridx = 4; grid.add(testBtn, gc);
        }

        JButton restoreBtn = new JButton(i18n.t("settings.alerts.restoreDefaults"));
        restoreBtn.addActionListener(e -> {
            notificationsBox.setSelected(true);
            for (DeviceAlertControls ctrl : deviceAlertControls.values()) {
                ctrl.enabledBox().setSelected(true);
                ctrl.lowSpinner().setValue(20);
                ctrl.criticalSpinner().setValue(10);
                ctrl.suppressChargingBox().setSelected(true);
            }
        });
        JPanel restoreRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        restoreRow.add(restoreBtn);

        alertsPanel.add(new JScrollPane(grid), BorderLayout.CENTER);
        alertsPanel.add(restoreRow, BorderLayout.SOUTH);

        root.add(safetyPanel);
        root.add(Box.createVerticalStrut(8));
        root.add(alertsPanel);
        return root;
    }

    private JPanel buildTaskbarAndThemeTab() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(5, 4, 5, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        addLabelField(form, gc, row++, i18n.t("settings.taskbar.theme"), themeCombo);
        addLabelField(form, gc, row++, i18n.t("settings.taskbar.displayMode"), displayModeCombo);
        addFullRow(form, gc, row++, taskbarPlateBox);
        addLabelField(form, gc, row++, i18n.t("settings.taskbar.iconSize"), taskbarIconSizeSpinner);
        addLabelField(form, gc, row++, i18n.t("settings.taskbar.fontSize"), taskbarFontSizeSpinner);
        addLabelField(form, gc, row++, i18n.t("settings.taskbar.spacing"), taskbarSpacingSpinner);
        addLabelField(form, gc, row++, i18n.t("settings.taskbar.offsetX"), taskbarOffsetXSpinner);
        return form;
    }

    private JPanel buildHistoryAndUpdatesTab(JFrame owner) {
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

        JPanel histPanel = new JPanel(new GridBagLayout());
        histPanel.setBorder(BorderFactory.createTitledBorder(i18n.t("settings.history.sectionTitle")));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;

        addFullRow(histPanel, gc, 0, historyEnabledBox);
        addLabelField(histPanel, gc, 1, i18n.t("settings.history.retention"), historyRetentionCombo);

        JButton openHistoryBtn = new JButton(i18n.t("settings.history.openButton"));
        openHistoryBtn.setEnabled(historyStore != null);
        openHistoryBtn.addActionListener(e -> {
            if (historyStore != null) {
                new BatteryHistoryDialog(owner, historyStore, settings, themeManager, i18n, simulationBox.isSelected())
                        .setVisible(true);
            }
        });
        JPanel histBtnWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        histBtnWrap.add(openHistoryBtn);
        addFullRow(histPanel, gc, 2, histBtnWrap);

        JPanel updPanel = new JPanel(new GridBagLayout());
        updPanel.setBorder(BorderFactory.createTitledBorder(i18n.t("settings.updates.sectionTitle")));
        GridBagConstraints ug = new GridBagConstraints();
        ug.insets = new Insets(4, 4, 4, 4);
        ug.anchor = GridBagConstraints.WEST;
        ug.fill = GridBagConstraints.HORIZONTAL;
        addFullRow(updPanel, ug, 0, autoCheckUpdatesBox);

        JButton checkNowBtn = new JButton(i18n.t("settings.updates.checkNowButton"));
        checkNowBtn.addActionListener(e ->
                new UpdateCheckerDialog(owner, updateService, settings, i18n).setVisible(true)
        );
        JPanel updBtnWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        updBtnWrap.add(checkNowBtn);
        addFullRow(updPanel, ug, 1, updBtnWrap);

        root.add(histPanel);
        root.add(Box.createVerticalStrut(10));
        root.add(updPanel);
        root.add(Box.createVerticalGlue());
        return root;
    }

    private JPanel buildSimulationTab() {
        JPanel tab = new JPanel(new BorderLayout(0, 8));
        tab.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        tab.add(simulationBox, BorderLayout.NORTH);
        tab.add(new SimulationControlPanel(simulationManager), BorderLayout.CENTER);
        return tab;
    }

    public boolean wasSaved() {
        return saved;
    }

    private void applyToSettings() {
        LanguageOption langOpt = (LanguageOption) languageCombo.getSelectedItem();
        if (langOpt != null) {
            settings.setLanguage(langOpt.language());
        }
        settings.setStartWithWindows(startWithWindowsBox.isSelected());
        settings.setStartMinimized(startMinimizedBox.isSelected());
        settings.setMinimizeToTray(minimizeToTrayBox.isSelected());
        settings.setShowDisconnectedDevices(showDisconnectedBox.isSelected());
        settings.setEcoModeEnabled(ecoModeBox.isSelected());
        Integer intervalVal = (Integer) pollIntervalCombo.getSelectedItem();
        if (intervalVal != null) {
            settings.setPollIntervalSeconds(intervalVal);
        }

        settings.setReadOnlyHidMode(readOnlyModeBox.isSelected());
        settings.setGameSirMonitoringEnabled(gameSirMonitoringBox.isSelected());
        settings.setNotificationsEnabled(notificationsBox.isSelected());

        int defaultLow = 20;
        DeviceAlertControls first = deviceAlertControls.get(DeluxM800Pro.DEVICE_ID);
        if (first != null) {
            defaultLow = (Integer) first.lowSpinner().getValue();
        }
        settings.setBatteryThresholds(defaultLow, (Integer) mediumThresholdSpinner.getValue());

        for (DeviceAlertControls ctrl : deviceAlertControls.values()) {
            settings.setDeviceAlertEnabled(ctrl.deviceId(), ctrl.enabledBox().isSelected());
            settings.setDeviceLowThreshold(ctrl.deviceId(), (Integer) ctrl.lowSpinner().getValue());
            settings.setDeviceCriticalThreshold(ctrl.deviceId(), (Integer) ctrl.criticalSpinner().getValue());
            settings.setSuppressAlertWhenCharging(ctrl.deviceId(), ctrl.suppressChargingBox().isSelected());
        }

        settings.setDarkTheme(themeCombo.getSelectedIndex() == 0);
        settings.setDisplayMode((DisplayMode) displayModeCombo.getSelectedItem());
        settings.setTaskbarBackgroundPlate(taskbarPlateBox.isSelected());
        settings.setTaskbarIconSize((Integer) taskbarIconSizeSpinner.getValue());
        settings.setTaskbarFontSize((Integer) taskbarFontSizeSpinner.getValue());
        settings.setTaskbarSpacing((Integer) taskbarSpacingSpinner.getValue());
        settings.setTaskbarOffsetX((Integer) taskbarOffsetXSpinner.getValue());

        settings.setHistoryEnabled(historyEnabledBox.isSelected());
        int retIdx = historyRetentionCombo.getSelectedIndex();
        settings.setHistoryRetentionDays(retIdx == 0 ? 7 : (retIdx == 1 ? 30 : 90));
        settings.setAutoCheckUpdates(autoCheckUpdatesBox.isSelected());

        settings.setSimulationEnabled(simulationBox.isSelected());
        for (Map.Entry<String, JCheckBox> entry : deviceVisibilityBoxes.entrySet()) {
            settings.setDeviceHidden(entry.getKey(), !entry.getValue().isSelected());
        }
    }

    private static void addFullRow(JPanel panel, GridBagConstraints gc, int row, java.awt.Component comp) {
        gc.gridx = 0;
        gc.gridy = row;
        gc.gridwidth = 2;
        gc.weightx = 1.0;
        panel.add(comp, gc);
    }

    private static void addLabelField(JPanel panel, GridBagConstraints gc, int row, String label, java.awt.Component field) {
        gc.gridwidth = 1;
        gc.gridy = row;
        gc.gridx = 0;
        gc.weightx = 0.0;
        panel.add(new JLabel(label), gc);
        gc.gridx = 1;
        gc.weightx = 1.0;
        panel.add(field, gc);
    }
}
