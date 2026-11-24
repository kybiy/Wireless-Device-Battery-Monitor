package br.com.wirelessbattery.ui;

import br.com.wirelessbattery.config.AppVersion;
import br.com.wirelessbattery.diagnostics.AppLogger;
import br.com.wirelessbattery.diagnostics.HidDeviceInfo;
import br.com.wirelessbattery.diagnostics.HidDiagnosticsService;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;

/**
 * Diálogo de diagnóstico HID somente-leitura para identificar VID/PID, interfaces,
 * Usage Page, Usage, Path, status de identificação e logs estruturados das consultas HID.
 */
public final class HidDiagnosticsDialog extends JDialog {
    private final HidDiagnosticsService diagnosticsService;
    private final AppLogger logger;
    private final HidTableModel tableModel = new HidTableModel();
    private final JTable table = new JTable(tableModel);
    private final JTextField filterField = new JTextField(26);
    private final JLabel statusLabel = new JLabel("Carregando dispositivos HID...");
    private final JButton refreshButton = new JButton("Atualizar lista");
    private final JButton copyButton = new JButton("Copiar relatório");
    private final JButton exportButton = new JButton("Exportar .txt");

    private JTextArea logArea;
    private Consumer<String> logListener;
    private List<HidDeviceInfo> allDevices = List.of();

    public HidDiagnosticsDialog(JFrame owner, HidDiagnosticsService diagnosticsService) {
        this(owner, diagnosticsService, null);
    }

    public HidDiagnosticsDialog(JFrame owner, HidDiagnosticsService diagnosticsService, AppLogger logger) {
        super(owner, "Diagnóstico HID (Somente Leitura) — " + AppVersion.formatShort(), false);
        this.diagnosticsService = diagnosticsService;
        this.logger = logger;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));
        setPreferredSize(new Dimension(1080, 560));

        JPanel devicesTab = buildDevicesTab();
        if (logger != null) {
            JTabbedPane tabs = new JTabbedPane();
            tabs.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
            tabs.addTab("Interfaces HID (Somente Leitura)", devicesTab);
            tabs.addTab("Logs de Diagnóstico HID", buildLogsTab());
            add(tabs, BorderLayout.CENTER);
        } else {
            add(devicesTab, BorderLayout.CENTER);
        }

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                if (HidDiagnosticsDialog.this.logger != null && logListener != null) {
                    HidDiagnosticsDialog.this.logger.removeListener(logListener);
                }
            }
        });

        pack();
        setLocationRelativeTo(owner);
        loadDevicesAsync();
    }

    private JPanel buildDevicesTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));

        JPanel top = new JPanel(new BorderLayout(8, 8));
        top.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));

        JLabel safetyNotice = new JLabel(
                "Modo passivo (somente enumeração): nenhum comando de controle desconhecido é enviado aos periféricos."
        );
        safetyNotice.setFont(ThemeManager.fontBold(12f));

        JPanel filterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        filterPanel.add(new JLabel("Filtrar (VID, PID, nome, status ou path):"));
        filterPanel.add(filterField);

        top.add(safetyNotice, BorderLayout.NORTH);
        top.add(filterPanel, BorderLayout.SOUTH);

        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);
        table.setRowHeight(25);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));

        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        bottom.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));
        bottom.add(statusLabel, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton closeButton = new JButton("Fechar");
        buttons.add(refreshButton);
        buttons.add(copyButton);
        buttons.add(exportButton);
        buttons.add(closeButton);
        bottom.add(buttons, BorderLayout.EAST);

        panel.add(top, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(bottom, BorderLayout.SOUTH);

        refreshButton.addActionListener(e -> loadDevicesAsync());
        copyButton.addActionListener(e -> copyReportToClipboard());
        exportButton.addActionListener(e -> exportReportToFile());
        closeButton.addActionListener(e -> dispose());

        filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });

        return panel;
    }

    static void appendRecentLog(JTextArea area, String line) {
        area.append(line + "\n");
        // O documento inclui uma linha vazia final após a quebra de linha.
        var lines = area.getDocument().getDefaultRootElement();
        int excess = lines.getElementCount() - 1 - AppLogger.MAX_ENTRIES;
        if (excess > 0) {
            area.replaceRange("", 0, lines.getElement(excess).getStartOffset());
        }
    }

    private JPanel buildLogsTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        for (String line : logger.getHistory()) {
            appendRecentLog(logArea, line);
        }
        logArea.setCaretPosition(logArea.getDocument().getLength());

        logListener = line -> SwingUtilities.invokeLater(() -> {
            if (logArea != null) {
                appendRecentLog(logArea, line);
                logArea.setCaretPosition(logArea.getDocument().getLength());
            }
        });
        logger.addListener(logListener);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton copyLogsBtn = new JButton("Copiar logs");
        copyLogsBtn.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(logArea.getText()), null);
            JOptionPane.showMessageDialog(this, "Logs copiados para a área de transferência.", "Logs HID", JOptionPane.INFORMATION_MESSAGE);
        });
        JButton clearLogsBtn = new JButton("Limpar");
        clearLogsBtn.addActionListener(e -> {
            logger.clear();
            logArea.setText("");
        });
        bottom.add(copyLogsBtn);
        bottom.add(clearLogsBtn);

        panel.add(new JScrollPane(logArea), BorderLayout.CENTER);
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    private void loadDevicesAsync() {
        refreshButton.setEnabled(false);
        statusLabel.setText("Enumerando dispositivos HID...");
        new SwingWorker<List<HidDeviceInfo>, Void>() {
            @Override
            protected List<HidDeviceInfo> doInBackground() {
                return diagnosticsService.listConnectedHidDevices();
            }

            @Override
            protected void done() {
                refreshButton.setEnabled(true);
                try {
                    allDevices = get();
                    applyFilter();
                } catch (Exception e) {
                    statusLabel.setText("Erro ao listar dispositivos HID: " + e.getMessage());
                }
            }
        }.execute();
    }

    private void applyFilter() {
        String query = filterField.getText().trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            tableModel.setDevices(allDevices);
        } else {
            List<HidDeviceInfo> filtered = new ArrayList<>();
            for (HidDeviceInfo d : allDevices) {
                String combined = (d.formatVidPid() + " " + d.formatUsagePage() + " "
                        + d.formatReportCaps() + " "
                        + d.safeManufacturer() + " " + d.safeProduct() + " "
                        + d.safeIdentificationStatus() + " " + d.safePath())
                        .toLowerCase(Locale.ROOT);
                if (combined.contains(query)) {
                    filtered.add(d);
                }
            }
            tableModel.setDevices(filtered);
        }
        statusLabel.setText("Exibindo " + tableModel.getRowCount() + " de " + allDevices.size() + " interface(s) HID.");
    }

    private void copyReportToClipboard() {
        String report = diagnosticsService.buildTextReport(tableModel.getDevices());
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(report), null);
        JOptionPane.showMessageDialog(
                this,
                "Relatório HID completo copiado para a área de transferência.",
                "Diagnóstico HID",
                JOptionPane.INFORMATION_MESSAGE
        );
    }

    private void exportReportToFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("diagnostico-hid.txt"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File target = chooser.getSelectedFile();
            try {
                String report = diagnosticsService.buildTextReport(tableModel.getDevices());
                Files.writeString(target.toPath(), report, StandardCharsets.UTF_8);
                JOptionPane.showMessageDialog(
                        this,
                        "Relatório exportado para:\n" + target.getAbsolutePath(),
                        "Exportação concluída",
                        JOptionPane.INFORMATION_MESSAGE
                );
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(
                        this,
                        "Erro ao exportar relatório: " + ex.getMessage(),
                        "Erro",
                        JOptionPane.ERROR_MESSAGE
                );
            }
        }
    }

    private static final class HidTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {
                "Produto", "Fabricante", "VID", "PID", "IF", "Usage Page", "Usage", "Caps (In/Out/Feat)", "Status de identificação", "Device Path"
        };
        private List<HidDeviceInfo> rows = List.of();

        void setDevices(List<HidDeviceInfo> devices) {
            this.rows = new ArrayList<>(devices);
            fireTableDataChanged();
        }

        List<HidDeviceInfo> getDevices() {
            return rows;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int column) { return COLUMNS[column]; }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            HidDeviceInfo d = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> d.safeProduct();
                case 1 -> d.safeManufacturer();
                case 2 -> d.formatVid();
                case 3 -> d.formatPid();
                case 4 -> d.interfaceNumber();
                case 5 -> d.formatUsagePage();
                case 6 -> d.formatUsage();
                case 7 -> d.formatReportCaps();
                case 8 -> d.safeIdentificationStatus();
                case 9 -> d.safePath();
                default -> "";
            };
        }
    }
}
