package br.com.wirelessbattery.ui;

import br.com.wirelessbattery.config.AppSettings;
import br.com.wirelessbattery.i18n.I18n;
import br.com.wirelessbattery.update.UpdateCheckResult;
import br.com.wirelessbattery.update.UpdateCheckerService;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.net.URI;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;

/**
 * Diálogo leve para verificação manual de atualizações no GitHub Releases
 * fora da EDT, sem download/instalação automática nem envio de telemetria.
 */
public final class UpdateCheckerDialog extends JDialog {

    private final UpdateCheckerService updateService;
    private final AppSettings settings;
    private final I18n i18n;

    private final JLabel installedLabel = new JLabel();
    private final JLabel latestLabel = new JLabel();
    private final JLabel statusLabel = new JLabel();
    private final JTextArea notesArea = new JTextArea(9, 44);
    private final JButton openReleaseBtn = new JButton();
    private final JButton recheckBtn = new JButton();

    private String currentReleaseUrl = "";

    public UpdateCheckerDialog(
            JFrame owner,
            UpdateCheckerService updateService,
            AppSettings settings,
            I18n i18n
    ) {
        super(owner, (i18n != null ? i18n : new I18n()).t("update.title"), true);
        this.updateService = updateService != null ? updateService : new UpdateCheckerService();
        this.settings = settings;
        this.i18n = i18n != null ? i18n : new I18n();

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));
        setPreferredSize(new Dimension(540, 390));
        setMinimumSize(new Dimension(460, 330));

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(BorderFactory.createEmptyBorder(14, 16, 4, 16));
        installedLabel.setFont(ThemeManager.fontRegular(12.5f));
        latestLabel.setFont(ThemeManager.fontBold(13f));
        statusLabel.setFont(ThemeManager.fontRegular(12.5f));
        top.add(installedLabel);
        top.add(Box.createVerticalStrut(4));
        top.add(latestLabel);
        top.add(Box.createVerticalStrut(8));
        top.add(statusLabel);

        notesArea.setEditable(false);
        notesArea.setLineWrap(true);
        notesArea.setWrapStyleWord(true);
        JPanel center = new JPanel(new BorderLayout(6, 6));
        center.setBorder(BorderFactory.createEmptyBorder(4, 16, 4, 16));
        center.add(new JLabel(this.i18n.t("update.notesLabel")), BorderLayout.NORTH);
        center.add(new JScrollPane(notesArea), BorderLayout.CENTER);

        openReleaseBtn.setText(this.i18n.t("update.openRelease"));
        openReleaseBtn.setEnabled(false);
        openReleaseBtn.addActionListener(e -> openReleasePage());

        recheckBtn.setText(this.i18n.t("update.recheck"));
        recheckBtn.addActionListener(e -> runCheckAsync());

        JButton closeBtn = new JButton(this.i18n.t("update.close"));
        closeBtn.addActionListener(e -> dispose());

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        bottom.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));
        bottom.add(recheckBtn);
        bottom.add(openReleaseBtn);
        bottom.add(closeBtn);

        add(top, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(owner);
        runCheckAsync();
    }

    private void runCheckAsync() {
        recheckBtn.setEnabled(false);
        openReleaseBtn.setEnabled(false);
        installedLabel.setText(i18n.t("update.installed", br.com.wirelessbattery.config.AppVersion.getVersion()));
        latestLabel.setText("...");
        statusLabel.setText(i18n.t("update.checking"));
        notesArea.setText("");

        new SwingWorker<UpdateCheckResult, Void>() {
            @Override
            protected UpdateCheckResult doInBackground() {
                return updateService.checkForUpdates(settings);
            }

            @Override
            protected void done() {
                recheckBtn.setEnabled(true);
                try {
                    applyResult(get());
                } catch (Exception ex) {
                    statusLabel.setText(i18n.t("update.offline", ex.getMessage()));
                }
            }
        }.execute();
    }

    private void applyResult(UpdateCheckResult result) {
        if (result == null) {
            return;
        }
        installedLabel.setText(i18n.t("update.installed", result.currentVersion()));
        String latest = (result.latestVersion() == null || result.latestVersion().isBlank())
                ? "-"
                : result.latestVersion();
        latestLabel.setText(i18n.t("update.latest", latest));
        currentReleaseUrl = result.releaseUrl() != null ? result.releaseUrl() : "";
        openReleaseBtn.setEnabled(!currentReleaseUrl.isBlank());
        notesArea.setText(result.releaseNotes() != null ? result.releaseNotes() : "");
        notesArea.setCaretPosition(0);

        switch (result.status()) {
            case UPDATE_AVAILABLE -> statusLabel.setText(i18n.t("update.available"));
            case UP_TO_DATE -> statusLabel.setText(i18n.t("update.upToDate"));
            case RATE_LIMITED -> statusLabel.setText(i18n.t("update.rateLimited"));
            case OFFLINE_OR_ERROR -> statusLabel.setText(i18n.t("update.offline", result.errorMessage()));
        }
    }

    private void openReleasePage() {
        if (currentReleaseUrl.isBlank()) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(currentReleaseUrl));
            }
        } catch (Exception ignored) {
            // Melhor esforço ao abrir navegador padrão
        }
    }
}
