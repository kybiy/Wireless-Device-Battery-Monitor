package br.com.wirelessbattery.ui;

import br.com.wirelessbattery.config.AppSettings;
import br.com.wirelessbattery.config.AppVersion;
import br.com.wirelessbattery.config.DisplayMode;
import br.com.wirelessbattery.device.BatteryDevice;
import br.com.wirelessbattery.simulation.SimulationManager;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.SpinnerNumberModel;

/**
 * Janela de configurações organizada em 3 abas:
 * Geral, Barra de tarefas e Tema, e Simulação.
 */
public final class SettingsDialog extends JDialog {
    private final AppSettings settings;
    private final SimulationManager simulationManager;

    private final JCheckBox startWithWindowsBox;
    private final JCheckBox startMinimizedBox;
    private final JCheckBox minimizeToTrayBox;
    private final JCheckBox notificationsBox;
    private final JCheckBox showDisconnectedBox;
    private final JSpinner pollIntervalSpinner;
    private final JSpinner lowThresholdSpinner;
    private final JSpinner mediumThresholdSpinner;

    private final JComboBox<String> themeCombo;
    private final JComboBox<DisplayMode> displayModeCombo;
    private final JCheckBox taskbarPlateBox;
    private final JSpinner taskbarIconSizeSpinner;
    private final JSpinner taskbarFontSizeSpinner;
    private final JSpinner taskbarSpacingSpinner;
    private final JSpinner taskbarOffsetXSpinner;

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
        super(owner, "Configurações — Wireless Battery v" + AppVersion.getVersion(), true);
        this.settings = settings;
        this.simulationManager = simulationManager;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));
        setMinimumSize(new Dimension(560, 480));

        startWithWindowsBox = new JCheckBox("Inicializar com o Windows", settings.isStartWithWindows());
        startMinimizedBox = new JCheckBox("Iniciar minimizado na bandeja/barra", settings.isStartMinimized());
        minimizeToTrayBox = new JCheckBox("Minimizar para a bandeja ao fechar a janela", settings.isMinimizeToTray());
        notificationsBox = new JCheckBox("Notificar quando a bateria estiver baixa", settings.isNotificationsEnabled());
        showDisconnectedBox = new JCheckBox("Exibir dispositivos desconectados / pendentes na tela principal", settings.isShowDisconnectedDevices());
        pollIntervalSpinner = new JSpinner(new SpinnerNumberModel(settings.getPollIntervalSeconds(), 5, 600, 5));
        lowThresholdSpinner = new JSpinner(new SpinnerNumberModel(settings.getLowBatteryThreshold(), 5, 45, 1));
        mediumThresholdSpinner = new JSpinner(new SpinnerNumberModel(settings.getMediumBatteryThreshold(), 25, 95, 5));

        themeCombo = new JComboBox<>(new String[]{"Escuro (Windows 11)", "Claro"});
        themeCombo.setSelectedIndex(settings.isDarkTheme() ? 0 : 1);
        displayModeCombo = new JComboBox<>(DisplayMode.values());
        displayModeCombo.setSelectedItem(settings.getDisplayMode());
        taskbarPlateBox = new JCheckBox("Fundo discreto atrás do widget na barra de tarefas", settings.isTaskbarBackgroundPlate());
        taskbarIconSizeSpinner = new JSpinner(new SpinnerNumberModel(settings.getTaskbarIconSize(), 12, 24, 1));
        taskbarFontSizeSpinner = new JSpinner(new SpinnerNumberModel(settings.getTaskbarFontSize(), 10, 18, 1));
        taskbarSpacingSpinner = new JSpinner(new SpinnerNumberModel(settings.getTaskbarSpacing(), 4, 32, 2));
        taskbarOffsetXSpinner = new JSpinner(new SpinnerNumberModel(settings.getTaskbarOffsetX(), 0, 300, 4));

        simulationBox = new JCheckBox("Ativar modo de simulação (prévia ilustrativa com valores fictícios)", settings.isSimulationEnabled());
        simulationBox.addActionListener(e -> {
            settings.setSimulationEnabled(simulationBox.isSelected());
            simulationManager.setEnabled(simulationBox.isSelected());
        });

        JTabbedPane tabs = new JTabbedPane();
        tabs.setBorder(BorderFactory.createEmptyBorder(8, 12, 0, 12));
        tabs.addTab("Geral", buildGeneralTab(realDevices));
        tabs.addTab("Barra de tarefas e Tema", buildTaskbarAndThemeTab());
        tabs.addTab("Simulação", buildSimulationTab());

        if (openSimulationTab) {
            tabs.setSelectedIndex(2);
        }

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 10));
        buttons.setBorder(BorderFactory.createEmptyBorder(0, 12, 6, 12));
        JButton cancelBtn = new JButton("Cancelar");
        JButton saveBtn = new JButton("Salvar");
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

    private JPanel buildGeneralTab(List<BatteryDevice> realDevices) {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        addFullRow(form, gc, row++, startWithWindowsBox);
        addFullRow(form, gc, row++, startMinimizedBox);
        addFullRow(form, gc, row++, minimizeToTrayBox);
        addFullRow(form, gc, row++, notificationsBox);
        addFullRow(form, gc, row++, showDisconnectedBox);

        addLabelField(form, gc, row++, "Intervalo de atualização (segundos):", pollIntervalSpinner);
        addLabelField(form, gc, row++, "Limite bateria baixa (vermelho, %):", lowThresholdSpinner);
        addLabelField(form, gc, row++, "Limite bateria média (amarelo, %):", mediumThresholdSpinner);

        JPanel visPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
        visPanel.setBorder(BorderFactory.createTitledBorder("Dispositivos permitidos"));
        for (BatteryDevice device : realDevices) {
            JCheckBox box = new JCheckBox(device.getDisplayName(), !settings.isDeviceHidden(device.getId()));
            deviceVisibilityBoxes.put(device.getId(), box);
            visPanel.add(box);
        }
        addFullRow(form, gc, row, visPanel);
        return form;
    }

    private JPanel buildTaskbarAndThemeTab() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(5, 4, 5, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        addLabelField(form, gc, row++, "Tema da interface:", themeCombo);
        addLabelField(form, gc, row++, "Modo de indicador no Windows:", displayModeCombo);
        addFullRow(form, gc, row++, taskbarPlateBox);
        addLabelField(form, gc, row++, "Tamanho dos ícones no widget (px):", taskbarIconSizeSpinner);
        addLabelField(form, gc, row++, "Tamanho da fonte no widget (pt):", taskbarFontSizeSpinner);
        addLabelField(form, gc, row++, "Espaçamento entre dispositivos (px):", taskbarSpacingSpinner);
        addLabelField(form, gc, row++, "Deslocamento da bandeja (px):", taskbarOffsetXSpinner);
        return form;
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
        settings.setStartWithWindows(startWithWindowsBox.isSelected());
        settings.setStartMinimized(startMinimizedBox.isSelected());
        settings.setMinimizeToTray(minimizeToTrayBox.isSelected());
        settings.setNotificationsEnabled(notificationsBox.isSelected());
        settings.setShowDisconnectedDevices(showDisconnectedBox.isSelected());
        settings.setPollIntervalSeconds((Integer) pollIntervalSpinner.getValue());
        settings.setBatteryThresholds(
                (Integer) lowThresholdSpinner.getValue(),
                (Integer) mediumThresholdSpinner.getValue()
        );

        settings.setDarkTheme(themeCombo.getSelectedIndex() == 0);
        settings.setDisplayMode((DisplayMode) displayModeCombo.getSelectedItem());
        settings.setTaskbarBackgroundPlate(taskbarPlateBox.isSelected());
        settings.setTaskbarIconSize((Integer) taskbarIconSizeSpinner.getValue());
        settings.setTaskbarFontSize((Integer) taskbarFontSizeSpinner.getValue());
        settings.setTaskbarSpacing((Integer) taskbarSpacingSpinner.getValue());
        settings.setTaskbarOffsetX((Integer) taskbarOffsetXSpinner.getValue());

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
