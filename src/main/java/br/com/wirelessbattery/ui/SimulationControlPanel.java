package br.com.wirelessbattery.ui;

import br.com.wirelessbattery.simulation.SimulatedBatteryDevice;
import br.com.wirelessbattery.simulation.SimulationManager;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;

/**
 * Painel de controles manuais do modo de simulação para Delux M800 Pro,
 * Akko Verge S9 e GameSir Cyclone 2.
 */
public final class SimulationControlPanel extends JPanel {
    private final SimulationManager simulationManager;

    public SimulationControlPanel(SimulationManager simulationManager) {
        this.simulationManager = simulationManager;
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(6, 4, 4, 4));

        JLabel warning = new JLabel(
                "Valores fictícios para teste visual — completamente separados das leituras reais."
        );
        warning.setFont(ThemeManager.fontRegular(11.5f));
        add(warning, BorderLayout.NORTH);

        JPanel rows = new JPanel(new GridLayout(0, 1, 0, 8));
        for (SimulatedBatteryDevice device : simulationManager.getSimulatedDevices()) {
            rows.add(createDeviceControlRow(device));
        }
        add(rows, BorderLayout.CENTER);
    }

    private JPanel createDeviceControlRow(SimulatedBatteryDevice device) {
        JPanel row = new JPanel(new BorderLayout(8, 4));
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEtchedBorder(),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)
        ));

        JLabel title = new JLabel(device.getDisplayName() + " (" + device.getDeviceType().getLabel() + ")");
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

        Runnable apply = () -> {
            int val = slider.getValue();
            valueLabel.setText(val + "%");
            boolean isConn = connectedBox.isSelected();
            boolean isAsleep = isConn && asleepBox.isSelected();
            boolean isCharging = isConn && !isAsleep && chargingBox.isSelected();

            slider.setEnabled(isConn && !isAsleep);
            asleepBox.setEnabled(isConn);
            chargingBox.setEnabled(isConn && !isAsleep);
            lowBtn.setEnabled(isConn);

            simulationManager.updateDevice(device.getId(), val, isCharging, isConn, isAsleep);
        };

        slider.addChangeListener(e -> apply.run());
        connectedBox.addActionListener(e -> apply.run());
        asleepBox.addActionListener(e -> {
            if (asleepBox.isSelected()) {
                chargingBox.setSelected(false);
            }
            apply.run();
        });
        chargingBox.addActionListener(e -> {
            if (chargingBox.isSelected()) {
                asleepBox.setSelected(false);
            }
            apply.run();
        });
        lowBtn.addActionListener(e -> {
            connectedBox.setSelected(true);
            asleepBox.setSelected(false);
            chargingBox.setSelected(false);
            slider.setValue(15);
            apply.run();
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
