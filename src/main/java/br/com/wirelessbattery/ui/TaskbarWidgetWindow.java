package br.com.wirelessbattery.ui;

import br.com.wirelessbattery.config.AppSettings;
import br.com.wirelessbattery.diagnostics.AppLogger;
import br.com.wirelessbattery.i18n.I18n;
import br.com.wirelessbattery.model.DeviceReading;
import br.com.wirelessbattery.model.DeviceType;
import br.com.wirelessbattery.model.ReadingAgeFormatter;
import java.time.Instant;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Modo 2 — Widget minimalista posicionado ao lado da bandeja da barra de tarefas do Windows
 * (inspirado em {@code src/widget.cpp} do projeto C++ original).
 *
 * Exibe indicadores horizontais no formato {@code [Mouse] 85%   [Headset] 62%   [Controle] 90%},
 * adaptados ao tema claro/escuro, DPI e múltiplos monitores, com atualização sem piscar
 * (cache de assinatura visual e geometria) e detecção de reinicialização do Explorer.
 */
public final class TaskbarWidgetWindow extends JWindow {
    public static final String BACKEND_PROPERTY = "wirelessbattery.taskbar.backend";
    private static final int BOLT_BOX = 11;
    private static final int BOLT_VISIBLE_END = 9; // ≈ 0.82 * 11
    // Mesmo respiro visual que existe entre o raio do último item e a borda direita (padding 10px,
    // descontando ~1px de margem interna do SVG do próximo ícone).
    private static final int GAP_AFTER_BOLT = 9;
    private final AppSettings settings;
    private final ThemeManager themeManager;
    private final AppLogger logger;
    private final Runnable onOpenWindow;
    private final Runnable onRefresh;
    private final Runnable onOpenSettings;
    private final Runnable onExit;

    private final NativeTaskbarWidget nativeWidget;
    private volatile I18n i18n = new I18n();
    private final boolean preferNativeBackend;
    private boolean loggedFallbackToSwing;

    private final WidgetCanvas canvas = new WidgetCanvas();
    private final Timer positionTimer;
    private List<DeviceReading> items = List.of();
    private volatile boolean enabled;
    private volatile boolean hovered;
    private volatile boolean lightTaskbar;

    private String lastRenderSignature = "";
    private Rectangle lastBounds;
    private volatile long lastTaskbarPeer;
    private Runnable onAvailabilityChanged;
    private AutoCloseable shellFocusHook;
    private volatile boolean zOrderCheckPending;

    public TaskbarWidgetWindow(
            AppSettings settings,
            ThemeManager themeManager,
            Runnable onOpenWindow,
            Runnable onRefresh,
            Runnable onOpenSettings,
            Runnable onExit
    ) {
        this(settings, themeManager, new AppLogger(), onOpenWindow, onRefresh, onOpenSettings, onExit);
    }

    public TaskbarWidgetWindow(
            AppSettings settings,
            ThemeManager themeManager,
            AppLogger logger,
            Runnable onOpenWindow,
            Runnable onRefresh,
            Runnable onOpenSettings,
            Runnable onExit
    ) {
        this.settings = settings;
        this.themeManager = themeManager;
        this.logger = logger != null ? logger : new AppLogger();
        this.onOpenWindow = onOpenWindow;
        this.onRefresh = onRefresh;
        this.onOpenSettings = onOpenSettings;
        this.onExit = onExit;

        String backendProp = System.getProperty(BACKEND_PROPERTY, "native").trim().toLowerCase();
        this.preferNativeBackend = WindowsTaskbarLocator.isWindows() && !"swing".equals(backendProp);
        if (this.preferNativeBackend) {
            this.nativeWidget = new NativeTaskbarWidget(
                    settings, this.logger, onOpenWindow, onRefresh, onOpenSettings, onExit
            );
            this.logger.info("[WIDGET-NATIVE] Backend nativo Win32 habilitado (para usar Swing: -D"
                    + BACKEND_PROPERTY + "=swing).");
        } else {
            this.nativeWidget = null;
            this.logger.info("[WIDGET-NATIVE] Backend Swing (JWindow) selecionado ("
                    + BACKEND_PROPERTY + "=" + backendProp + ").");
        }

        setType(Type.UTILITY);
        setAlwaysOnTop(true);
        setFocusableWindowState(false);
        setAutoRequestFocus(false);
        setBackground(new Color(0, 0, 0, 0));

        canvas.setOpaque(false);
        canvas.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setContentPane(canvas);

        JPopupMenu popup = buildPopupMenu();
        canvas.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    onOpenWindow.run();
                } else if (SwingUtilities.isRightMouseButton(e)) {
                    popup.show(canvas, e.getX(), e.getY());
                }
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                hovered = true;
                canvas.repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hovered = false;
                canvas.repaint();
            }
        });

        positionTimer = new Timer(1500, e -> repositionFromTimer());
    }

    public void setOnAvailabilityChanged(Runnable onAvailabilityChanged) {
        this.onAvailabilityChanged = onAvailabilityChanged;
    }

    public void setI18n(I18n i18n) {
        this.i18n = i18n != null ? i18n : new I18n();
        if (nativeWidget != null) {
            nativeWidget.setI18n(this.i18n);
        }
        canvas.setToolTipText(buildTooltip(this.items));
    }

    private boolean isUsingNativeBackend() {
        if (!preferNativeBackend || nativeWidget == null) {
            return false;
        }
        if (nativeWidget.isPermanentFallbackToSwing()) {
            if (!loggedFallbackToSwing) {
                loggedFallbackToSwing = true;
                logger.warn("[WIDGET-NATIVE] Fallback automático para o widget Swing (JWindow) ativado: motivo="
                        + nativeWidget.getFallbackReason());
            }
            return false;
        }
        return true;
    }

    public String getRequestedBackendName() {
        return preferNativeBackend ? "NATIVE" : "SWING";
    }

    public String getEffectiveBackendName() {
        return isUsingNativeBackend() ? "NATIVE" : "SWING";
    }

    public String getFallbackReason() {
        if (!preferNativeBackend) {
            return "solicitado SWING via propriedade " + BACKEND_PROPERTY;
        }
        if (nativeWidget != null && nativeWidget.isPermanentFallbackToSwing()) {
            return nativeWidget.getFallbackReason();
        }
        return "nenhum";
    }

    public long getWidgetHwndPeer() {
        if (isUsingNativeBackend()) {
            return nativeWidget.getChildHwndPeer();
        }
        if (super.isDisplayable()) {
            try {
                var ptr = com.sun.jna.Native.getComponentPointer(this);
                return ptr != null ? com.sun.jna.Pointer.nativeValue(ptr) : 0L;
            } catch (Throwable ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    public long getTaskbarHwndPeer() {
        if (isUsingNativeBackend()) {
            long attached = nativeWidget.getAttachedTaskbarPeer();
            return attached != 0L ? attached : WindowsTaskbarLocator.taskbarPeer();
        }
        return lastTaskbarPeer != 0L ? lastTaskbarPeer : WindowsTaskbarLocator.taskbarPeer();
    }

    public String getLastRenderStatus() {
        if (isUsingNativeBackend()) {
            return nativeWidget.getLastRenderStatus();
        }
        return super.isVisible() ? "OK (Swing JWindow)" : "INATIVO";
    }

    public List<DeviceReading> getCurrentItems() {
        return List.copyOf(items);
    }

    public String getTooltipText() {
        return canvas.getToolTipText();
    }

    public String formatDiagnosticSummary(boolean simModeActive, int realCount, int simCount) {
        return String.format(
                "[WIDGET-DIAG] modo=%s | backendSolicitado=%s | backendEfetivo=%s | reais=%d | simulados=%d | exibidos=%d | widgetHwnd=0x%X | Shell_TrayWnd=0x%X | render=%s | fallback=%s",
                simModeActive ? "SIMULACAO" : "REAL",
                getRequestedBackendName(),
                getEffectiveBackendName(),
                realCount,
                simCount,
                items.size(),
                getWidgetHwndPeer(),
                getTaskbarHwndPeer(),
                getLastRenderStatus(),
                getFallbackReason()
        );
    }

    @Override
    public boolean isVisible() {
        if (isUsingNativeBackend()) {
            return nativeWidget.isActiveAndVisible();
        }
        return super.isVisible();
    }

    private void repositionFromTimer() {
        boolean wasVisible = isVisible();
        reposition();
        if (wasVisible != isVisible() && onAvailabilityChanged != null) {
            onAvailabilityChanged.run();
        }
    }

    private JPopupMenu buildPopupMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem open = new JMenuItem("Abrir Wireless Battery [Swing]");
        open.addActionListener(e -> onOpenWindow.run());
        JMenuItem refresh = new JMenuItem("Atualizar agora");
        refresh.addActionListener(e -> onRefresh.run());
        JMenuItem cfg = new JMenuItem("Configurações...");
        cfg.addActionListener(e -> onOpenSettings.run());
        JMenuItem exit = new JMenuItem("Sair");
        exit.addActionListener(e -> onExit.run());
        menu.add(open);
        menu.add(refresh);
        menu.add(cfg);
        menu.addSeparator();
        menu.add(exit);
        return menu;
    }

    public void setWidgetEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        if (!enabled) {
            positionTimer.stop();
            uninstallShellFocusHook();
            if (nativeWidget != null) {
                nativeWidget.setEnabled(false);
            }
            if (super.isVisible()) {
                super.setVisible(false);
            }
            return;
        }
        if (isUsingNativeBackend()) {
            if (super.isVisible()) {
                super.setVisible(false);
            }
            nativeWidget.updateReadings(this.items);
            nativeWidget.setEnabled(true);
        } else {
            reposition();
            installShellFocusHook();
        }
        if (!positionTimer.isRunning()) {
            positionTimer.start();
        }
    }

    private void installShellFocusHook() {
        if (shellFocusHook != null) {
            return;
        }
        shellFocusHook = WindowsTaskbarLocator.registerShellFocusHook(this::scheduleZOrderCheck);
    }

    private void uninstallShellFocusHook() {
        if (shellFocusHook != null) {
            try {
                shellFocusHook.close();
            } catch (Exception ignored) {
                // ignore
            }
            shellFocusHook = null;
        }
    }

    private void scheduleZOrderCheck() {
        if (!enabled || zOrderCheckPending) {
            return;
        }
        zOrderCheckPending = true;
        SwingUtilities.invokeLater(() -> {
            zOrderCheckPending = false;
            if (!enabled || !isVisible()) {
                return;
            }
            long tbPeer = lastTaskbarPeer != 0 ? lastTaskbarPeer : WindowsTaskbarLocator.taskbarPeer();
            if (tbPeer != 0) {
                WindowsTaskbarLocator.keepAboveTaskbarIfCovered(this, tbPeer);
            }
        });
    }

    public void updateReadings(List<DeviceReading> readings) {
        List<DeviceReading> sorted = new ArrayList<>(readings);
        sorted.sort(Comparator.comparingInt(r -> deviceOrder(r.deviceType())));
        this.items = sorted;
        canvas.setToolTipText(buildTooltip(this.items));

        if (isUsingNativeBackend()) {
            nativeWidget.updateReadings(this.items);
            return;
        }

        if (!enabled) {
            return;
        }
        String sig = computeRenderSignature();
        boolean visualChanged = !sig.equals(lastRenderSignature);
        lastRenderSignature = sig;

        reposition();
        if (visualChanged) {
            canvas.repaint();
        }
    }

    public void refreshLayoutFromSettings() {
        lastRenderSignature = "";
        lastBounds = null;
        if (isUsingNativeBackend()) {
            nativeWidget.refreshLayout();
            return;
        }
        if (enabled) {
            reposition();
            canvas.repaint();
        }
    }

    public boolean reposition() {
        if (!enabled) {
            if (nativeWidget != null) {
                nativeWidget.setEnabled(false);
            }
            if (super.isVisible()) {
                super.setVisible(false);
            }
            return false;
        }
        if (isUsingNativeBackend()) {
            if (super.isVisible()) {
                super.setVisible(false);
            }
            return nativeWidget.requestReposition();
        }
        Optional<WindowsTaskbarLocator.TaskbarAnchor> anchorOpt = WindowsTaskbarLocator.findTaskbarAnchor();
        if (anchorOpt.isEmpty()) {
            if (super.isVisible()) {
                super.setVisible(false);
            }
            return false;
        }
        WindowsTaskbarLocator.TaskbarAnchor anchor = anchorOpt.get();
        boolean themeChanged = (this.lightTaskbar != anchor.lightTaskbar());
        boolean explorerRestarted = (lastTaskbarPeer != 0 && lastTaskbarPeer != anchor.taskbarHwndPeer());
        this.lightTaskbar = anchor.lightTaskbar();
        this.lastTaskbarPeer = anchor.taskbarHwndPeer();

        Dimension pref = canvas.computePreferredSize();
        int h = Math.min(Math.max(24, anchor.taskbarHeight() - 10), Math.max(26, settings.getTaskbarIconSize() + 14));
        int w = pref.width;
        int x = Math.max(anchor.taskbarLeftX() + 12, anchor.rightEdgeX() - w - settings.getTaskbarOffsetX());
        int y = widgetTop(anchor.taskbarTopY(), anchor.taskbarHeight(), h);

        Rectangle nextBounds = new Rectangle(x, y, w, h);
        if (!nextBounds.equals(lastBounds) || explorerRestarted) {
            lastBounds = nextBounds;
            setBounds(nextBounds);
        }
        if (!super.isVisible()) {
            super.setVisible(true);
            WindowsTaskbarLocator.applyWidgetWindowStyles(this);
        }
        WindowsTaskbarLocator.keepAboveTaskbarIfCovered(this, anchor.taskbarHwndPeer());
        if (themeChanged) {
            canvas.repaint();
        }
        return true;
    }

    static int widgetTop(int taskbarTop, int taskbarHeight, int height) {
        return taskbarTop + (taskbarHeight - height) / 2;
    }

    public void shutdown() {
        positionTimer.stop();
        uninstallShellFocusHook();
        if (nativeWidget != null) {
            nativeWidget.shutdown();
        }
        dispose();
    }

    static Color resolvePercentTextColor(
            int pct,
            boolean charging,
            boolean lightTaskbar,
            AppSettings settings,
            Color fgNormal
    ) {
        if (charging) {
            return fgNormal;
        }
        int low = settings != null ? settings.getLowBatteryThreshold() : 20;
        int med = settings != null ? settings.getMediumBatteryThreshold() : 50;
        if (pct <= low) {
            return lightTaskbar ? new Color(0xDC, 0x26, 0x26) : DeviceIconFactory.COLOR_LOW;
        }
        if (pct <= med) {
            return lightTaskbar ? new Color(0xB4, 0x53, 0x09) : DeviceIconFactory.COLOR_MEDIUM;
        }
        return fgNormal;
    }

    private static int deviceOrder(DeviceType type) {
        return switch (type) {
            case MOUSE -> 0;
            case HEADSET -> 1;
            case GAMEPAD -> 2;
            default -> 3;
        };
    }

    private String computeRenderSignature() {
        StringBuilder sb = new StringBuilder();
        sb.append(settings.getTaskbarIconSize()).append('|')
                .append(settings.getTaskbarFontSize()).append('|')
                .append(settings.getTaskbarSpacing()).append('|')
                .append(settings.isTaskbarBackgroundPlate()).append('|')
                .append(settings.getLowBatteryThreshold()).append('|')
                .append(settings.getMediumBatteryThreshold()).append('|')
                .append(themeManager.isDark()).append('|');
        for (DeviceReading r : items) {
            sb.append(r.deviceId()).append(':')
                    .append(r.displayName()).append(':')
                    .append(r.deviceType()).append(':')
                    .append(r.status()).append(':')
                    .append(r.batteryPercent()).append(':')
                    .append(r.lastKnownPercent()).append(':')
                    .append(r.charging()).append(':')
                    .append(r.connectionLabel()).append(':')
                    .append(r.isSimulated()).append(';');
        }
        return sb.toString();
    }

    private String buildTooltip(List<DeviceReading> list) {
        I18n loc = this.i18n;
        if (list.isEmpty()) {
            return loc.t("widget.noneDetected");
        }
        Instant now = Instant.now();
        StringBuilder sb = new StringBuilder("<html>");
        for (int i = 0; i < list.size(); i++) {
            DeviceReading r = list.get(i);
            if (i > 0) sb.append("<br>");
            if (r.isSimulated()) sb.append("<b>").append(loc.t("widget.simPrefix").trim()).append("</b> ");
            sb.append(r.displayName()).append(": ");
            if (r.hasBatteryPercent()) {
                sb.append(r.batteryPercent()).append("%");
                if (r.charging()) sb.append(' ').append(loc.t("widget.chargingTag"));
                appendConnectionIfRelevant(sb, r);
                if (r.validReadingInstant() != null) {
                    sb.append(" · ").append(ReadingAgeFormatter.formatRelativeAge(r.validReadingInstant(), now, loc, false));
                }
            } else if (r.hasLastKnownPercent()) {
                sb.append(r.lastKnownPercent()).append("% (").append(loc.t("card.cachedBadge")).append(")");
                appendConnectionIfRelevant(sb, r);
                if (r.lastKnownInstant() != null) {
                    sb.append(" · ").append(ReadingAgeFormatter.formatRelativeAge(r.lastKnownInstant(), now, loc, true));
                }
            } else if (r.status() == br.com.wirelessbattery.model.ConnectionStatus.BATTERY_UNAVAILABLE
                    && "Bluetooth".equalsIgnoreCase(r.connectionLabel())) {
                sb.append(loc.t("card.btUnavailable", r.connectionLabel()));
            } else {
                sb.append(r.status().getLocalizedLabel(loc));
            }
        }
        sb.append("</html>");
        return sb.toString();
    }

    private static void appendConnectionIfRelevant(StringBuilder sb, DeviceReading r) {
        if (r.connectionLabel() != null
                && !r.connectionLabel().isBlank()
                && !"Sem conexão".equalsIgnoreCase(r.connectionLabel())) {
            sb.append(" [").append(r.connectionLabel()).append("]");
        }
    }

    private final class WidgetCanvas extends JPanel {
        private final Font simBadgeFont = new Font("Segoe UI", Font.BOLD, 9);
        private int cachedFontSize = -1;
        private Font cachedTextFont;

        private Font getTextFont() {
            int size = settings.getTaskbarFontSize();
            if (cachedTextFont == null || cachedFontSize != size) {
                cachedFontSize = size;
                cachedTextFont = new Font("Segoe UI", Font.BOLD, size);
            }
            return cachedTextFont;
        }

        @Override
        public Dimension getPreferredSize() {
            return computePreferredSize();
        }

        Dimension computePreferredSize() {
            Font font = getTextFont();
            FontMetrics fm = getFontMetrics(font);
            int iconSize = settings.getTaskbarIconSize();
            int spacing = settings.getTaskbarSpacing();

            boolean hasSim = items.stream().anyMatch(DeviceReading::isSimulated);
            int width = 20; // 10px padding esquerdo + 10px padding direito
            if (hasSim) {
                width += 30;
            }
            if (items.isEmpty()) {
                width += fm.stringWidth("--%");
            } else {
                for (int i = 0; i < items.size(); i++) {
                    DeviceReading r = items.get(i);
                    String label = r.hasBatteryPercent()
                            ? r.batteryPercent() + "%"
                            : (r.hasLastKnownPercent() ? r.lastKnownPercent() + "%" : "--%");
                    width += iconSize + 6 + fm.stringWidth(label);
                    if (r.charging()) {
                        width += 1 + BOLT_VISIBLE_END;
                    }
                    if (i < items.size() - 1) {
                        width += r.charging() ? GAP_AFTER_BOLT : spacing;
                    }
                }
            }
            int height = Math.max(26, iconSize + 12);
            return new Dimension(Math.max(62, width), height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);

            boolean useLight = lightTaskbar;
            boolean hasSim = items.stream().anyMatch(DeviceReading::isSimulated);

            if (settings.isTaskbarBackgroundPlate() || hovered) {
                Color bg = useLight
                        ? new Color(0xF4, 0xF5, 0xF7, hovered ? 245 : 220)
                        : new Color(0x1A, 0x1C, 0x20, hovered ? 245 : 220);
                Color border = hasSim
                        ? new Color(0xD5, 0xAA, 0x59, 210)
                        : (useLight
                                ? new Color(0xD8, 0xDE, 0xE8, 210)
                                : new Color(0x30, 0x34, 0x3B, 210));
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                g2.setColor(border);
                g2.drawRoundRect(0, 0, getWidth() - 2, getHeight() - 2, 10, 10);
            }

            int x = 10;
            int midY = getHeight() / 2;
            int iconSize = settings.getTaskbarIconSize();
            int spacing = settings.getTaskbarSpacing();

            if (hasSim) {
                g2.setFont(simBadgeFont);
                g2.setColor(new Color(0xD5, 0xAA, 0x59));
                g2.fillRoundRect(x, midY - 7, 24, 14, 6, 6);
                g2.setColor(new Color(0x10, 0x11, 0x14));
                g2.drawString("SIM", x + 3, midY + 3);
                x += 30;
            }

            Font textFont = getTextFont();
            g2.setFont(textFont);
            FontMetrics fm = g2.getFontMetrics();
            int textY = midY + (fm.getAscent() - fm.getDescent()) / 2;

            Color fgNormal = useLight ? new Color(0x11, 0x18, 0x27) : new Color(0xF1, 0xF3, 0xF5);
            Color fgDim = useLight ? new Color(0x6B, 0x72, 0x80, 175) : new Color(0x9C, 0xA3, 0xAF, 175);

            if (items.isEmpty()) {
                g2.setColor(fgDim);
                g2.drawString("--%", x, textY);
                g2.dispose();
                return;
            }

            for (int i = 0; i < items.size(); i++) {
                DeviceReading r = items.get(i);
                boolean hasCurrent = r.hasBatteryPercent();
                Color iconColor = hasCurrent ? fgNormal : fgDim;

                var icon = DeviceIconFactory.createDeviceIcon(r.deviceType(), iconSize, iconColor);
                icon.paintIcon(this, g2, x, midY - (iconSize / 2));
                x += iconSize + 6;

                String label;
                Color textColor;
                if (hasCurrent) {
                    int pct = r.batteryPercent();
                    label = pct + "%";
                    textColor = resolvePercentTextColor(pct, r.charging(), useLight, settings, fgNormal);
                } else if (r.hasLastKnownPercent()) {
                    label = r.lastKnownPercent() + "%";
                    textColor = fgDim;
                } else {
                    label = "--%";
                    textColor = fgDim;
                }

                g2.setColor(textColor);
                g2.drawString(label, x, textY);
                x += fm.stringWidth(label);

                if (r.charging()) {
                    // O raio ocupa visualmente de 18% a 82% da caixa de 11px (≈2px vazios de cada lado).
                    // Desloca a caixa para que a borda visível fique a 3px do "%" e termine
                    // exatamente onde começa o espaçamento, mantendo distâncias simétricas.
                    int boltX = x + 1;
                    DeviceIconFactory.drawBoltGlyph(g2, boltX, midY - 6, BOLT_BOX, DeviceIconFactory.COLOR_CHARGING);
                    x = boltX + BOLT_VISIBLE_END;
                }
                if (i < items.size() - 1) {
                    x += r.charging() ? GAP_AFTER_BOLT : spacing;
                }
            }

            g2.dispose();
        }
    }
}
