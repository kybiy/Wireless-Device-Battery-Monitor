package br.com.wirelessbattery.ui;

import br.com.wirelessbattery.diagnostics.HidDeviceInfo;
import br.com.wirelessbattery.diagnostics.HidDiagnosticsService;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;

/**
 * Diálogo de diagnóstico HID somente-leitura para identificar VID/PID, interfaces,
 * Usage Page, Usage, Path e status de identificação, com cópia e exportação de relatório.
 */
public final class HidDiagnosticsDialog extends JDialog {
    private final HidDiagnosticsService diagnosticsService;
    private final HidTableModel tableModel = new HidTableModel();
    private final JTable table = new JTable(tableModel);
    private final JTextField filterField = new JTextField(26);
    private final JLabel statusLabel = new JLabel("Carregando dispositivos HID...");
    private final JButton refreshButton = new JButton("Atualizar lista");
    private final JButton copyButton = new JButton("Copiar relatório");
    private final JButton exportButton = new JButton("Exportar .txt");

    private List<HidDeviceInfo> allDevices = List.of();

    public HidDiagnosticsDialog(JFrame owner, HidDiagnosticsService diagnosticsService) {
        super(owner, "Diagnóstico HID (Somente Leitura)", false);
        this.diagnosticsService = diagnosticsService;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));
        setPreferredSize(new Dimension(1060, 520));

        JPanel top = new JPanel(new BorderLayout(8, 8));
        top.setBorder(BorderFactory.createEmptyBorder(12, 14, 0, 14));

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
        scroll.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 14));

        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        bottom.setBorder(BorderFactory.createEmptyBorder(0, 14, 12, 14));
        bottom.add(statusLabel, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton closeButton = new JButton("Fechar");
        buttons.add(refreshButton);
        buttons.add(copyButton);
        buttons.add(exportButton);
        buttons.add(closeButton);
        bottom.add(buttons, BorderLayout.EAST);

        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        refreshButton.addActionListener(e -> loadDevicesAsync());
        copyButton.addActionListener(e -> copyReportToClipboard());
        exportButton.addActionListener(e -> exportReportToFile());
        closeButton.addActionListener(e -> dispose());

        filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });

        pack();
        setLocationRelativeTo(owner);
        loadDevicesAsync();
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
                "Produto", "Fabricante", "VID", "PID", "IF", "Usage Page", "Usage", "Status de identificação", "Device Path"
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
                case 7 -> d.safeIdentificationStatus();
                case 8 -> d.safePath();
                default -> "";
            };
        }
    }
}
