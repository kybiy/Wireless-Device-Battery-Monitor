package br.com.wirelessbattery.ui;

import br.com.wirelessbattery.config.AppSettings;
import br.com.wirelessbattery.device.AkkoVergeS9;
import br.com.wirelessbattery.device.DeluxM800Pro;
import br.com.wirelessbattery.device.GameSirCyclone2;
import br.com.wirelessbattery.device.QcyT13Bluetooth;
import br.com.wirelessbattery.history.AutonomyEstimate;
import br.com.wirelessbattery.history.AutonomyEstimator;
import br.com.wirelessbattery.history.BatteryHistorySample;
import br.com.wirelessbattery.history.BatteryHistoryStats;
import br.com.wirelessbattery.history.BatteryHistoryStore;
import br.com.wirelessbattery.i18n.I18n;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Diálogo leve de histórico de bateria por dispositivo com gráfico Java2D,
 * estatísticas locais, estimativa de autonomia, exportação CSV e limpeza.
 */
public final class BatteryHistoryDialog extends JDialog {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    private record DeviceChoice(String deviceId, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final BatteryHistoryStore historyStore;
    private final AppSettings settings;
    private final ThemeManager themeManager;
    private final I18n i18n;

    private final JComboBox<DeviceChoice> deviceCombo = new JComboBox<>();
    private final JComboBox<String> sourceCombo = new JComboBox<>();
    private final JComboBox<String> retentionCombo = new JComboBox<>();
    private final HistoryChartPanel chartPanel = new HistoryChartPanel();
    private final JLabel statsLabel = new JLabel();
    private final JLabel lastFullLabel = new JLabel();
    private final JLabel autonomyLabel = new JLabel();

    private List<BatteryHistorySample> currentSamples = List.of();
    private boolean updatingCombos;

    public BatteryHistoryDialog(
            JFrame owner,
            BatteryHistoryStore historyStore,
            AppSettings settings,
            ThemeManager themeManager,
            I18n i18n,
            boolean defaultSimulated
    ) {
        super(owner, (i18n != null ? i18n : new I18n()).t("history.title"), true);
        this.historyStore = historyStore;
        this.settings = settings;
        this.themeManager = themeManager;
        this.i18n = i18n != null ? i18n : new I18n();

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));
        setPreferredSize(new Dimension(640, 470));
        setMinimumSize(new Dimension(540, 400));

        add(buildTopControls(defaultSimulated), BorderLayout.NORTH);
        add(buildCenterPanel(), BorderLayout.CENTER);
        add(buildBottomButtons(), BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(owner);
        reloadView();
    }

    private JPanel buildTopControls(boolean defaultSimulated) {
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        top.setBorder(BorderFactory.createEmptyBorder(6, 12, 0, 12));

        Map<String, String> knownNames = new LinkedHashMap<>();
        knownNames.put(DeluxM800Pro.DEVICE_ID, DeluxM800Pro.DISPLAY_NAME);
        knownNames.put(AkkoVergeS9.DEVICE_ID, "Akko Verge S9 Ultra");
        knownNames.put(GameSirCyclone2.DEVICE_ID, GameSirCyclone2.DISPLAY_NAME);
        knownNames.put(QcyT13Bluetooth.DEVICE_ID, QcyT13Bluetooth.DISPLAY_NAME);

        updatingCombos = true;
        for (Map.Entry<String, String> entry : knownNames.entrySet()) {
            deviceCombo.addItem(new DeviceChoice(entry.getKey(), entry.getValue()));
        }
        sourceCombo.addItem(i18n.t("history.source.real"));
        sourceCombo.addItem(i18n.t("history.source.sim"));
        sourceCombo.setSelectedIndex(defaultSimulated ? 1 : 0);

        retentionCombo.addItem(i18n.t("settings.history.retentionDays", 7));
        retentionCombo.addItem(i18n.t("settings.history.retentionDays", 30));
        retentionCombo.addItem(i18n.t("settings.history.retentionDays", 90));
        int days = settings.getHistoryRetentionDays();
        retentionCombo.setSelectedIndex(days <= 7 ? 0 : (days <= 30 ? 1 : 2));
        updatingCombos = false;

        deviceCombo.addActionListener(e -> {
            if (!updatingCombos) reloadView();
        });
        sourceCombo.addActionListener(e -> {
            if (!updatingCombos) reloadView();
        });
        retentionCombo.addActionListener(e -> {
            if (!updatingCombos) {
                int idx = retentionCombo.getSelectedIndex();
                settings.setHistoryRetentionDays(idx == 0 ? 7 : (idx == 1 ? 30 : 90));
                reloadView();
            }
        });

        top.add(new JLabel(i18n.t("history.deviceLabel")));
        top.add(deviceCombo);
        top.add(new JLabel(i18n.t("history.sourceLabel")));
        top.add(sourceCombo);
        top.add(retentionCombo);
        return top;
    }

    private JPanel buildCenterPanel() {
        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.setBorder(BorderFactory.createEmptyBorder(4, 16, 4, 16));

        chartPanel.setPreferredSize(new Dimension(580, 230));
        chartPanel.setBorder(BorderFactory.createLineBorder(themeManager.subtleBorder()));

        JPanel infoStack = new JPanel();
        infoStack.setLayout(new BoxLayout(infoStack, BoxLayout.Y_AXIS));
        infoStack.setBorder(BorderFactory.createEmptyBorder(8, 4, 4, 4));
        statsLabel.setFont(ThemeManager.fontRegular(12f));
        lastFullLabel.setFont(ThemeManager.fontRegular(12f));
        autonomyLabel.setFont(ThemeManager.fontBold(12f));
        infoStack.add(statsLabel);
        infoStack.add(Box.createVerticalStrut(3));
        infoStack.add(lastFullLabel);
        infoStack.add(Box.createVerticalStrut(5));
        infoStack.add(autonomyLabel);

        center.add(chartPanel, BorderLayout.CENTER);
        center.add(infoStack, BorderLayout.SOUTH);
        return center;
    }

    private JPanel buildBottomButtons() {
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        bottom.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));

        JButton exportBtn = new JButton(i18n.t("history.exportCsv"));
        JButton clearBtn = new JButton(i18n.t("history.clear"));
        JButton closeBtn = new JButton(i18n.t("history.close"));

        exportBtn.addActionListener(e -> exportCsv());
        clearBtn.addActionListener(e -> clearSelectedHistory());
        closeBtn.addActionListener(e -> dispose());

        bottom.add(exportBtn);
        bottom.add(clearBtn);
        bottom.add(closeBtn);
        return bottom;
    }

    private boolean isSimulatedSelected() {
        return sourceCombo.getSelectedIndex() == 1;
    }

    private String selectedDeviceId() {
        DeviceChoice choice = (DeviceChoice) deviceCombo.getSelectedItem();
        if (choice == null) {
            return DeluxM800Pro.DEVICE_ID;
        }
        return choice.deviceId();
    }

    private String resolveLookupDeviceId(String baseId, boolean simulated) {
        if (simulated && !baseId.startsWith("sim-")) {
            return "sim-" + baseId;
        }
        return baseId;
    }

    private void reloadView() {
        boolean simulated = isSimulatedSelected();
        String lookupId = resolveLookupDeviceId(selectedDeviceId(), simulated);
        List<BatteryHistorySample> samples = historyStore.getSamples(lookupId, simulated);
        if (samples.isEmpty() && simulated) {
            samples = historyStore.getSamples(selectedDeviceId(), true);
        }
        this.currentSamples = samples;
        BatteryHistoryStats stats = historyStore.computeStats(
                samples.isEmpty() ? lookupId : samples.get(0).deviceId(),
                simulated
        );

        if (!stats.hasSamples()) {
            statsLabel.setText(i18n.t("history.stats.empty"));
            lastFullLabel.setText(i18n.t("history.stats.lastFullNever"));
        } else {
            statsLabel.setText(i18n.t("history.stats.samples", stats.sampleCount())
                    + " · " + i18n.t("history.stats.minMax", stats.minPercent(), stats.maxPercent()));
            if (stats.hasLastFullCharge()) {
                String formatted = TS_FMT.format(Instant.ofEpochMilli(stats.lastFullChargeEpochMillis())
                        .atZone(ZoneId.systemDefault()));
                lastFullLabel.setText(i18n.t("history.stats.lastFull", formatted));
            } else {
                lastFullLabel.setText(i18n.t("history.stats.lastFullNever"));
            }
        }

        if (simulated) {
            autonomyLabel.setText(i18n.t("history.autonomy.insufficient"));
        } else {
            AutonomyEstimate est = AutonomyEstimator.estimate(samples);
            if (!est.sufficientData()) {
                autonomyLabel.setText(i18n.t("history.autonomy.insufficient"));
            } else {
                long mins = est.estimatedRemainingMinutes();
                String durationText = mins >= 60
                        ? (mins / 60) + "h " + (mins % 60) + "m"
                        : mins + " min";
                String rateText = String.format(Locale.US, "%.1f", est.drainPercentPerHour());
                autonomyLabel.setText(i18n.t("history.autonomy.estimated", durationText, rateText));
            }
        }
        chartPanel.repaint();
    }

    private void exportCsv() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(i18n.t("history.exportCsv"));
        chooser.setFileFilter(new FileNameExtensionFilter("CSV (*.csv)", "csv"));
        chooser.setSelectedFile(new File("battery-history-" + selectedDeviceId() + ".csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = chooser.getSelectedFile().toPath();
        Path fileName = target.getFileName();
        if (fileName != null && !fileName.toString().toLowerCase(Locale.ROOT).endsWith(".csv")) {
            target = target.resolveSibling(fileName.toString() + ".csv");
        }
        try {
            boolean simulated = isSimulatedSelected();
            String id = currentSamples.isEmpty()
                    ? resolveLookupDeviceId(selectedDeviceId(), simulated)
                    : currentSamples.get(0).deviceId();
            historyStore.exportToCsv(target, id, simulated);
            JOptionPane.showMessageDialog(this,
                    i18n.t("history.exportSuccess", target.toAbsolutePath()),
                    i18n.t("history.title"),
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    i18n.t("history.exportError", ex.getMessage()),
                    i18n.t("history.title"),
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void clearSelectedHistory() {
        boolean simulated = isSimulatedSelected();
        String sourceName = simulated ? i18n.t("history.source.sim") : i18n.t("history.source.real");
        int choice = JOptionPane.showConfirmDialog(
                this,
                i18n.t("history.clearConfirm", sourceName.toLowerCase(i18n.getEffectiveLocale())),
                i18n.t("history.clear"),
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        if (choice == JOptionPane.YES_OPTION) {
            historyStore.clearHistory(simulated);
            reloadView();
        }
    }

    private final class HistoryChartPanel extends JPanel {
        HistoryChartPanel() {
            setOpaque(true);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Color bg = themeManager.cardBackground(false);
            g2.setColor(bg);
            g2.fillRect(0, 0, getWidth(), getHeight());

            int padLeft = 42;
            int padRight = 18;
            int padTop = 18;
            int padBottom = 28;
            int plotW = Math.max(40, getWidth() - padLeft - padRight);
            int plotH = Math.max(40, getHeight() - padTop - padBottom);

            g2.setFont(new Font("Segoe UI", Font.PLAIN, 10));
            for (int pct : new int[]{0, 25, 50, 75, 100}) {
                int y = padTop + plotH - (int) Math.round((pct / 100.0) * plotH);
                g2.setColor(themeManager.subtleBorder());
                g2.drawLine(padLeft, y, padLeft + plotW, y);
                g2.setColor(themeManager.secondaryText());
                g2.drawString(pct + "%", 8, y + 4);
            }

            if (currentSamples.isEmpty()) {
                g2.setColor(themeManager.mutedText());
                g2.drawString(i18n.t("history.stats.empty"), padLeft + 12, padTop + plotH / 2);
                g2.dispose();
                return;
            }

            long minT = currentSamples.get(0).epochMillis();
            long maxT = currentSamples.get(currentSamples.size() - 1).epochMillis();
            long spanT = Math.max(1L, maxT - minT);

            List<int[]> pts = new ArrayList<>(currentSamples.size());
            for (int i = 0; i < currentSamples.size(); i++) {
                BatteryHistorySample s = currentSamples.get(i);
                int x = currentSamples.size() == 1
                        ? padLeft + plotW / 2
                        : padLeft + (int) Math.round(((double) (s.epochMillis() - minT) / spanT) * plotW);
                int y = padTop + plotH - (int) Math.round((s.percent() / 100.0) * plotH);
                pts.add(new int[]{x, y, s.charging() ? 1 : 0});
            }

            if (pts.size() >= 2) {
                Path2D path = new Path2D.Float();
                path.moveTo(pts.get(0)[0], pts.get(0)[1]);
                for (int i = 1; i < pts.size(); i++) {
                    path.lineTo(pts.get(i)[0], pts.get(i)[1]);
                }
                g2.setColor(DeviceIconFactory.COLOR_HIGH);
                g2.setStroke(new BasicStroke(2f));
                g2.draw(path);
            }

            for (int[] pt : pts) {
                g2.setColor(pt[2] == 1 ? DeviceIconFactory.COLOR_CHARGING : DeviceIconFactory.COLOR_HIGH);
                g2.fill(new Ellipse2D.Float(pt[0] - 3f, pt[1] - 3f, 6f, 6f));
            }

            g2.setColor(themeManager.secondaryText());
            String startStr = TS_FMT.format(Instant.ofEpochMilli(minT).atZone(ZoneId.systemDefault()));
            String endStr = TS_FMT.format(Instant.ofEpochMilli(maxT).atZone(ZoneId.systemDefault()));
            g2.drawString(startStr, padLeft, padTop + plotH + 16);
            int endW = g2.getFontMetrics().stringWidth(endStr);
            g2.drawString(endStr, padLeft + plotW - endW, padTop + plotH + 16);

            g2.dispose();
        }
    }
}
