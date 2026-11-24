package br.com.wirelessbattery.ui;

import br.com.wirelessbattery.config.AppSettings;
import br.com.wirelessbattery.model.ConnectionStatus;
import br.com.wirelessbattery.model.DeviceReading;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.time.format.DateTimeFormatter;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;

/**
 * Cartão horizontal minimalista de dispositivo, fiel ao design de referência do Windows 11.
 *
 * Estrutura:
 * - Topo: Badge quadrado arredondado com ícone Lucide à esquerda, nome + subtítulo de status
 *   ao centro, e porcentagem em destaque (com indicador de raio quando carregando) à direita.
 * - Base: Barra de progresso fina (5 px) em formato de pílula com animação suave.
 */
public final class DeviceCardPanel extends JPanel {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final ThemeManager themeManager;
    private final AppSettings settings;

    private final IconBadge iconBadge = new IconBadge();
    private final JLabel nameLabel = new JLabel();
    private final JLabel subtitleLabel = new JLabel();
    private final JLabel chargingIconLabel = new JLabel();
    private final JLabel percentLabel = new JLabel("--%");
    private final SlimProgressBar progressBar = new SlimProgressBar();

    private DeviceReading currentReading;
    private boolean hovered;
    private float animatedProgress = 0f;
    private float targetProgress = 0f;
    private final Timer progressAnimator;

    public DeviceCardPanel(ThemeManager themeManager, AppSettings settings, DeviceReading initialReading) {
        this.themeManager = themeManager;
        this.settings = settings;

        setOpaque(false);
        setLayout(new BorderLayout(0, 12));
        setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        setPreferredSize(new Dimension(500, 88));
        setMinimumSize(new Dimension(380, 84));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 92));

        // Linha superior: [Badge Ícone] + [Nome / Subtítulo] ... [Raio + Porcentagem]
        JPanel topRow = new JPanel(new BorderLayout(12, 0));
        topRow.setOpaque(false);

        JPanel textStack = new JPanel();
        textStack.setOpaque(false);
        textStack.setLayout(new BoxLayout(textStack, BoxLayout.Y_AXIS));
        nameLabel.setFont(ThemeManager.fontBold(14.5f));
        subtitleLabel.setFont(ThemeManager.fontRegular(12f));
        textStack.add(nameLabel);
        textStack.add(Box.createVerticalStrut(2));
        textStack.add(subtitleLabel);

        JPanel rightMetric = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        rightMetric.setOpaque(false);
        chargingIconLabel.setIcon(DeviceIconFactory.createBoltIcon(15, DeviceIconFactory.COLOR_CHARGING));
        chargingIconLabel.setVisible(false);
        percentLabel.setFont(ThemeManager.fontBold(19f));
        rightMetric.add(chargingIconLabel);
        rightMetric.add(percentLabel);

        topRow.add(iconBadge, BorderLayout.WEST);
        topRow.add(textStack, BorderLayout.CENTER);
        topRow.add(rightMetric, BorderLayout.EAST);

        add(topRow, BorderLayout.CENTER);
        add(progressBar, BorderLayout.SOUTH);

        progressAnimator = new Timer(16, e -> stepProgressAnimation());

        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { hovered = true; repaint(); }
            @Override public void mouseExited(MouseEvent e) { hovered = false; repaint(); }
        });

        updateReading(initialReading);
    }

    public void updateReading(DeviceReading reading) {
        this.currentReading = reading;
        nameLabel.setText(reading.displayName());
        subtitleLabel.setText(buildSubtitle(reading));
        setToolTipText(buildTooltip(reading));

        if (reading.hasBatteryPercent()) {
            int pct = reading.batteryPercent();
            percentLabel.setText(pct + "%");
            chargingIconLabel.setVisible(reading.charging());
            animateProgressTo(pct);
        } else if (reading.hasLastKnownPercent()
                && (reading.status() == ConnectionStatus.ASLEEP || reading.status() == ConnectionStatus.DISCONNECTED)) {
            int last = reading.lastKnownPercent();
            percentLabel.setText(last + "%");
            chargingIconLabel.setVisible(false);
            animateProgressTo(last);
        } else {
            percentLabel.setText("--%");
            chargingIconLabel.setVisible(false);
            animateProgressTo(0);
        }

        refreshThemeColors();
        repaint();
    }

    private String buildSubtitle(DeviceReading r) {
        String via = r.connectionLabel();
        ConnectionStatus st = r.status();
        String base = switch (st) {
            case CONNECTED -> "Conectado · " + via;
            case CHARGING -> "Carregando · " + via;
            case ASLEEP -> r.hasLastKnownPercent()
                    ? "Em repouso · Última leitura"
                    : "Em repouso · " + via;
            case NO_RESPONSE -> "Receptor conectado, sem resposta · " + via;
            case OPEN_FAILED -> "Falha ao abrir interface HID · " + via;
            case READ_FAILED -> "Falha de leitura · " + via;
            case TIMEOUT -> "Timeout de leitura · " + via;
            case PENDING_PROTOCOL, BATTERY_UNAVAILABLE -> "Leitura indisponível";
            case ERROR -> "Erro de leitura · " + via;
            case DISCONNECTED -> r.hasLastKnownPercent()
                    ? "Desconectado · Última às " + (r.lastKnownAt() != null ? r.lastKnownAt().format(TIME_FMT) : "--:--")
                    : "Desconectado · " + via;
        };
        return base;
    }

    private String buildTooltip(DeviceReading r) {
        StringBuilder sb = new StringBuilder();
        if (r.isSimulated()) {
            sb.append("[Simulação] ");
        }
        sb.append(r.displayName()).append(" — ").append(r.status().getLabel());
        if (r.hasBatteryPercent()) {
            sb.append(" (").append(r.batteryPercent()).append("%)");
        } else if (r.hasLastKnownPercent()) {
            sb.append(" (última conhecida: ").append(r.lastKnownPercent()).append("%)");
        }
        if (!r.detailMessage().isBlank()) {
            sb.append(" · ").append(r.detailMessage());
        }
        return sb.toString();
    }

    public void refreshThemeColors() {
        Color primary = themeManager.primaryText();
        Color secondary = themeManager.secondaryText();
        Color muted = themeManager.mutedText();

        nameLabel.setForeground(primary);
        subtitleLabel.setForeground(secondary);

        if (currentReading != null) {
            if (currentReading.hasBatteryPercent()) {
                int pct = currentReading.batteryPercent();
                boolean isLow = !currentReading.charging() && pct <= settings.getLowBatteryThreshold();
                if (currentReading.charging()) {
                    percentLabel.setForeground(DeviceIconFactory.COLOR_CHARGING);
                } else if (isLow) {
                    percentLabel.setForeground(DeviceIconFactory.COLOR_LOW);
                } else {
                    percentLabel.setForeground(primary);
                }
            } else if (currentReading.hasLastKnownPercent()) {
                percentLabel.setForeground(secondary);
            } else {
                percentLabel.setForeground(muted);
            }
        }
        iconBadge.repaint();
        progressBar.repaint();
        repaint();
    }

    @Override
    public void removeNotify() {
        progressAnimator.stop();
        super.removeNotify();
    }

    private void animateProgressTo(int target) {
        this.targetProgress = Math.max(0, Math.min(100, target));
        if (!isShowing() || Math.abs(animatedProgress - targetProgress) < 0.5f) {
            animatedProgress = targetProgress;
            progressAnimator.stop();
            if (isShowing()) {
                progressBar.repaint();
            }
            return;
        }
        if (!progressAnimator.isRunning()) {
            progressAnimator.start();
        }
    }

    private void stepProgressAnimation() {
        float diff = targetProgress - animatedProgress;
        if (Math.abs(diff) <= 1.2f) {
            animatedProgress = targetProgress;
            progressAnimator.stop();
        } else {
            animatedProgress += diff * 0.28f;
        }
        progressBar.repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        boolean sim = currentReading != null && currentReading.isSimulated();
        Color bg = hovered ? themeManager.cardHoverBackground() : themeManager.cardBackground(sim);
        g2.setColor(bg);
        g2.fill(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, 15f, 15f));
        g2.setColor(themeManager.cardBorder(sim));
        g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1.5f, getHeight() - 1.5f, 15f, 15f));
        g2.dispose();
        super.paintComponent(g);
    }

    /**
     * Caixa quadrada arredondada (38x38) que abriga o ícone vetorial Lucide do periférico.
     */
    private final class IconBadge extends JComponent {
        IconBadge() {
            setPreferredSize(new Dimension(38, 38));
            setMinimumSize(new Dimension(38, 38));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(themeManager.elevatedBackground());
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 11f, 11f));

            if (currentReading != null) {
                boolean active = currentReading.hasBatteryPercent();
                Color iconColor = active ? themeManager.primaryText() : themeManager.secondaryText();
                var icon = DeviceIconFactory.createDeviceIcon(currentReading.deviceType(), 18, iconColor);
                int ix = (getWidth() - icon.getIconWidth()) / 2;
                int iy = (getHeight() - icon.getIconHeight()) / 2;
                icon.paintIcon(this, g2, ix, iy);
            }
            g2.dispose();
        }
    }

    /**
     * Barra de progresso fina (5 px) em formato de pílula.
     */
    private final class SlimProgressBar extends JComponent {
        SlimProgressBar() {
            setPreferredSize(new Dimension(100, 6));
            setMinimumSize(new Dimension(100, 6));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            float h = 5f;
            float w = getWidth();
            g2.setColor(themeManager.trackBackground());
            g2.fill(new RoundRectangle2D.Float(0, 0.5f, w, h, h, h));

            if (currentReading != null && animatedProgress > 0.5f) {
                Color fill;
                if (currentReading.hasBatteryPercent()) {
                    fill = DeviceIconFactory.batteryColor(
                            currentReading.batteryPercent(),
                            currentReading.charging(),
                            settings
                    );
                } else {
                    // Valor em repouso / última leitura conhecida: traço discreto
                    Color base = themeManager.mutedText();
                    fill = new Color(base.getRed(), base.getGreen(), base.getBlue(), 130);
                }
                float fillW = Math.max(h, (w * animatedProgress) / 100f);
                g2.setColor(fill);
                g2.fill(new RoundRectangle2D.Float(0, 0.5f, Math.min(w, fillW), h, h, h));
            }
            g2.dispose();
        }
    }
}
