package br.com.wirelessbattery.ui;

import br.com.wirelessbattery.simulation.SimulatedBatteryDevice;
import br.com.wirelessbattery.simulation.SimulationManager;
import br.com.wirelessbattery.simulation.SimulationScenario;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;

/**
 * Painel de controles do modo de simulação para Delux M800 Pro,
 * Akko Verge S9 Ultra, QCY T13 e GameSir Cyclone 2, incluindo os 12 cenários
 * pré-configurados de seleção de fones e Bluetooth.
 */
public final class SimulationControlPanel extends JPanel {

    private record AudioOption(String id, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final SimulationManager simulationManager;
    private final List<Runnable> rowRefreshes = new ArrayList<>();
    private final JComboBox<SimulationScenario> scenarioCombo;
    private final JComboBox<AudioOption> audioOutputCombo;
    private final JCheckBox bluetoothAdapterBox;
    private boolean syncingControls;

    public SimulationControlPanel(SimulationManager simulationManager) {
        this.simulationManager = simulationManager;
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));

        JPanel topControls = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(2, 2, 4, 2);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;

        JLabel warning = new JLabel(
                "Valores fictícios para teste visual — completamente separados das leituras reais."
        );
        warning.setFont(ThemeManager.fontRegular(11.5f));
        gc.gridx = 0;
        gc.gridy = 0;
        gc.gridwidth = 3;
        gc.weightx = 1.0;
        topControls.add(warning, gc);

        // Linha de cenários pré-configurados (12 cenários)
        scenarioCombo = new JComboBox<>(SimulationScenario.values());
        if (simulationManager.getActiveScenario() != null) {
            scenarioCombo.setSelectedItem(simulationManager.getActiveScenario());
        }
        JButton applyScenarioBtn = new JButton("Aplicar cenário");
        applyScenarioBtn.addActionListener(e -> {
            SimulationScenario chosen = (SimulationScenario) scenarioCombo.getSelectedItem();
            if (chosen != null) {
                simulationManager.applyScenario(chosen);
                syncHeaderAndRowsFromManager();
            }
        });

        gc.gridy = 1;
        gc.gridwidth = 1;
        gc.gridx = 0;
        gc.weightx = 0.0;
        topControls.add(new JLabel("Cenário:"), gc);
        gc.gridx = 1;
        gc.weightx = 1.0;
        topControls.add(scenarioCombo, gc);
        gc.gridx = 2;
        gc.weightx = 0.0;
        topControls.add(applyScenarioBtn, gc);

        // Linha de saída de áudio padrão e adaptador Bluetooth simulado
        audioOutputCombo = new JComboBox<>(new AudioOption[]{
                new AudioOption(SimulationManager.SIM_AKKO_ID, "Akko Verge S9 Ultra"),
                new AudioOption(SimulationManager.SIM_QCY_T13_ID, "QCY T13 (Bluetooth)"),
                new AudioOption(SimulationManager.SIM_SYSTEM_SPEAKERS_ID, "Alto-falantes do sistema")
        });
        bluetoothAdapterBox = new JCheckBox("Adaptador Bluetooth ativo", simulationManager.isBluetoothAdapterAvailable());

        audioOutputCombo.addActionListener(e -> {
            if (syncingControls) {
                return;
            }
            AudioOption opt = (AudioOption) audioOutputCombo.getSelectedItem();
            if (opt != null) {
                simulationManager.setDefaultAudioDeviceId(opt.id());
            }
        });
        bluetoothAdapterBox.addActionListener(e -> {
            if (syncingControls) {
                return;
            }
            simulationManager.setBluetoothAdapterAvailable(bluetoothAdapterBox.isSelected());
        });

        gc.gridy = 2;
        gc.gridx = 0;
        gc.weightx = 0.0;
        topControls.add(new JLabel("Saída padrão:"), gc);
        gc.gridx = 1;
        gc.weightx = 1.0;
        topControls.add(audioOutputCombo, gc);
        gc.gridx = 2;
        gc.weightx = 0.0;
        topControls.add(bluetoothAdapterBox, gc);

        add(topControls, BorderLayout.NORTH);

        JPanel rows = new JPanel(new GridLayout(0, 1, 0, 8));
        for (SimulatedBatteryDevice device : simulationManager.getSimulatedDevices()) {
            rows.add(createDeviceControlRow(device));
        }

        JScrollPane scroll = new JScrollPane(rows);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setPreferredSize(new Dimension(500, 240));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        syncHeaderAndRowsFromManager();
    }

    private void syncHeaderAndRowsFromManager() {
        syncingControls = true;
        try {
            bluetoothAdapterBox.setSelected(simulationManager.isBluetoothAdapterAvailable());
            String audioId = simulationManager.getDefaultAudioDeviceId();
            for (int i = 0; i < audioOutputCombo.getItemCount(); i++) {
                if (audioOutputCombo.getItemAt(i).id().equals(audioId)) {
                    audioOutputCombo.setSelectedIndex(i);
                    break;
                }
            }
            for (Runnable refresh : rowRefreshes) {
                refresh.run();
            }
        } finally {
            syncingControls = false;
        }
    }

    private JPanel createDeviceControlRow(SimulatedBatteryDevice device) {
        JPanel row = new JPanel(new BorderLayout(8, 4));
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEtchedBorder(),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)
        ));

        JLabel title = new JLabel(device.getDisplayName()
                + " (" + device.getDeviceType().getLabel()
                + " · " + device.getConnectionDescription() + ")");
        title.setFont(ThemeManager.fontBold(12.5f));

        JSlider slider = new JSlider(0, 100, device.getBatteryPercent());
        slider.setMajorTickSpacing(25);
        slider.setPaintTicks(true);

        JLabel valueLabel = new JLabel(device.getBatteryPercent() + "%");
        valueLabel.setFont(ThemeManager.fontBold(12.5f));

        JCheckBox connectedBox = new JCheckBox("Conectado", device.isConnected());
        JCheckBox asleepBox = new JCheckBox("Repouso", device.isAsleep());
        JCheckBox chargingBox = new JCheckBox("Carregando", device.isCharging());
        JButton lowBtn = new JButton("15%");
        lowBtn.setToolTipText("Simular bateria fraca (15%)");

        Runnable updateEnabledState = () -> {
            boolean isConn = connectedBox.isSelected();
            boolean isAsleep = isConn && asleepBox.isSelected();
            slider.setEnabled(isConn && !isAsleep);
            asleepBox.setEnabled(isConn);
            chargingBox.setEnabled(isConn && !isAsleep);
            lowBtn.setEnabled(isConn);
        };

        Runnable refreshFromDevice = () -> {
            slider.setValue(device.getBatteryPercent());
            if (device.isBatteryUnavailable()) {
                valueLabel.setText("--%");
            } else {
                valueLabel.setText(device.getBatteryPercent() + "%");
            }
            connectedBox.setSelected(device.isConnected());
            asleepBox.setSelected(device.isAsleep() || device.isReceiverPresentOff());
            chargingBox.setSelected(device.isCharging());
            updateEnabledState.run();
        };
        rowRefreshes.add(refreshFromDevice);

        Runnable apply = () -> {
            if (syncingControls) {
                return;
            }
            int val = slider.getValue();
            valueLabel.setText(val + "%");
            boolean isConn = connectedBox.isSelected();
            boolean isAsleep = isConn && asleepBox.isSelected();
            boolean isCharging = isConn && !isAsleep && chargingBox.isSelected();
            updateEnabledState.run();
            simulationManager.updateDevice(device.getId(), val, isCharging, isConn, isAsleep);
        };

        slider.addChangeListener(e -> apply.run());
        connectedBox.addActionListener(e -> apply.run());
        asleepBox.addActionListener(e -> {
            if (!syncingControls && asleepBox.isSelected()) {
                chargingBox.setSelected(false);
            }
            apply.run();
        });
        chargingBox.addActionListener(e -> {
            if (!syncingControls && chargingBox.isSelected()) {
                asleepBox.setSelected(false);
            }
            apply.run();
        });
        lowBtn.addActionListener(e -> {
            syncingControls = true;
            try {
                connectedBox.setSelected(true);
                asleepBox.setSelected(false);
                chargingBox.setSelected(false);
                slider.setValue(15);
                valueLabel.setText("15%");
                updateEnabledState.run();
            } finally {
                syncingControls = false;
            }
            simulationManager.updateDevice(device.getId(), 15, false, true, false);
        });

        JPanel top = new JPanel(new BorderLayout());
        top.add(title, BorderLayout.WEST);
        top.add(valueLabel, BorderLayout.EAST);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        controls.add(lowBtn);
        controls.add(chargingBox);
        controls.add(asleepBox);
        controls.add(connectedBox);

        row.add(top, BorderLayout.NORTH);
        row.add(slider, BorderLayout.CENTER);
        row.add(controls, BorderLayout.SOUTH);
        return row;
    }
}
