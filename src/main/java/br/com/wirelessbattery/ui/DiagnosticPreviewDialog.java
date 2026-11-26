package br.com.wirelessbattery.ui;

import br.com.wirelessbattery.diagnostics.DiagnosticBundleExporter;
import br.com.wirelessbattery.i18n.I18n;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.File;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Permite ao usuário revisar e inspecionar todo o diagnóstico anonimizado antes
 * de gravar o arquivo {@code .zip} localmente (nenhum envio automático é feito).
 */
public final class DiagnosticPreviewDialog extends JDialog {

    private final Map<String, String> entries;
    private final I18n i18n;

    public DiagnosticPreviewDialog(JDialog owner, Map<String, String> entries, I18n i18n) {
        super(owner, (i18n != null ? i18n : new I18n()).t("diag.previewTitle"), true);
        this.entries = entries;
        this.i18n = i18n != null ? i18n : new I18n();

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));
        setPreferredSize(new Dimension(780, 540));

        JLabel hint = new JLabel("<html><body style='width: 720px'>" + this.i18n.t("diag.previewHint") + "</body></html>");
        hint.setBorder(BorderFactory.createEmptyBorder(10, 14, 2, 14));

        JTextArea previewArea = new JTextArea(DiagnosticBundleExporter.buildPreviewText(entries));
        previewArea.setEditable(false);
        previewArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        previewArea.setCaretPosition(0);

        JScrollPane scroll = new JScrollPane(previewArea);
        scroll.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 14));

        JButton saveBtn = new JButton(this.i18n.t("diag.saveZip"));
        JButton closeBtn = new JButton(this.i18n.t("history.close"));
        saveBtn.addActionListener(e -> saveZip());
        closeBtn.addActionListener(e -> dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        buttons.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));
        buttons.add(saveBtn);
        buttons.add(closeBtn);

        add(hint, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(owner);
    }

    private void saveZip() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(i18n.t("diag.saveZip"));
        chooser.setFileFilter(new FileNameExtensionFilter("ZIP (*.zip)", "zip"));
        chooser.setSelectedFile(new File("wireless-battery-diagnostics.zip"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = chooser.getSelectedFile().toPath();
        Path fileName = target.getFileName();
        if (fileName != null && !fileName.toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            target = target.resolveSibling(fileName.toString() + ".zip");
        }
        try {
            DiagnosticBundleExporter.exportZip(entries, target);
            JOptionPane.showMessageDialog(
                    this,
                    i18n.t("diag.saveZipSuccess", target.toAbsolutePath()),
                    i18n.t("diag.previewTitle"),
                    JOptionPane.INFORMATION_MESSAGE
            );
            dispose();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(
                    this,
                    i18n.t("diag.saveZipError", ex.getMessage()),
                    i18n.t("diag.previewTitle"),
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }
}
