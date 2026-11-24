package br.com.wirelessbattery.ui;

import br.com.wirelessbattery.config.AppSettings;
import br.com.wirelessbattery.model.DeviceType;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.Icon;

/**
 * Fábrica centralizada de ícones vetoriais estilo Lucide (via {@link FlatSVGIcon} do FlatLaf Extras)
 * e paleta semântica de cores de bateria.
 */
public final class DeviceIconFactory {
    // Cores semânticas harmoniosas (conforme especificação de design)
    public static final Color COLOR_HIGH = new Color(0x59, 0xB8, 0x8A);      // #59B88A — Bateria alta (> 50%)
    public static final Color COLOR_MEDIUM = new Color(0xD5, 0xAA, 0x59);    // #D5AA59 — Bateria média (21%–50%)
    public static final Color COLOR_LOW = new Color(0xE1, 0x6B, 0x70);       // #E16B70 — Bateria baixa (<= 20%)
    public static final Color COLOR_CHARGING = new Color(0x77, 0xA7, 0xED);  // #77A7ED — Carregando
    public static final Color COLOR_NEUTRAL = new Color(0x6B, 0x72, 0x80);   // #6B7280 — Indisponível / Repouso

    public enum IconName {
        MOUSE("icons/mouse.svg"),
        HEADSET("icons/headset.svg"),
        GAMEPAD("icons/gamepad.svg"),
        BATTERY("icons/battery.svg"),
        ZAP("icons/zap.svg"),
        CHECK_CIRCLE("icons/check-circle.svg"),
        DISCONNECTED("icons/disconnected.svg"),
        SLIDERS("icons/sliders.svg"),
        REFRESH("icons/refresh.svg"),
        DIAGNOSTICS("icons/diagnostics.svg"),
        MINIMIZE("icons/minimize.svg"),
        CLOSE("icons/close.svg");

        private final String resourcePath;

        IconName(String resourcePath) {
            this.resourcePath = resourcePath;
        }

        public String getResourcePath() {
            return resourcePath;
        }
    }

    private record SvgCacheKey(IconName iconName, int size, int rgba) {}

    private static final Map<SvgCacheKey, Icon> SVG_CACHE = new ConcurrentHashMap<>();

    private DeviceIconFactory() {}

    public static Color batteryColor(Integer percent, boolean charging, AppSettings settings) {
        if (charging) {
            return COLOR_CHARGING;
        }
        if (percent == null) {
            return COLOR_NEUTRAL;
        }
        int low = settings != null ? settings.getLowBatteryThreshold() : 20;
        int med = settings != null ? settings.getMediumBatteryThreshold() : 50;
        if (percent <= low) {
            return COLOR_LOW;
        }
        if (percent <= med) {
            return COLOR_MEDIUM;
        }
        return COLOR_HIGH;
    }

    /**
     * Carrega um ícone vetorial SVG estilo Lucide empacotado localmente com filtro de cor e suporte a HiDPI.
     * Reutiliza instâncias em cache por {@code (ícone, tamanho, cor)} para evitar re-parsing de XML/SVG
     * a cada repaint de cards e indicadores da barra de tarefas.
     */
    public static Icon createSvgIcon(IconName iconName, int size, Color color) {
        int rgba = color != null ? color.getRGB() : 0;
        SvgCacheKey key = new SvgCacheKey(iconName, size, rgba);
        return SVG_CACHE.computeIfAbsent(key, k -> {
            FlatSVGIcon svg = new FlatSVGIcon(k.iconName().getResourcePath(), k.size(), k.size());
            if (color != null) {
                FlatSVGIcon.ColorFilter filter = new FlatSVGIcon.ColorFilter(c -> color);
                svg.setColorFilter(filter);
            }
            return svg;
        });
    }

    public static Icon createDeviceIcon(DeviceType type, int size, Color foreground) {
        IconName name = switch (type) {
            case MOUSE -> IconName.MOUSE;
            case HEADSET -> IconName.HEADSET;
            case GAMEPAD -> IconName.GAMEPAD;
            default -> IconName.BATTERY;
        };
        return createSvgIcon(name, size, foreground);
    }

    public static Icon createBoltIcon(int size, Color color) {
        return createSvgIcon(IconName.ZAP, size, color);
    }

    /**
     * Desenho direto em Graphics2D usado dentro de BufferedImage de TrayIcon e no canvas da barra de tarefas.
     */
    public static void drawDeviceGlyph(Graphics2D g2, DeviceType type, int size, Color color) {
        Icon icon = createDeviceIcon(type, size, color);
        if (icon.getIconWidth() > 0) {
            icon.paintIcon(null, g2, 0, 0);
            return;
        }
        // Fallback geométrico caso executado sem recursos de classpath
        g2.setColor(color);
        float stroke = Math.max(1.5f, size / 12f);
        g2.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        if (type == DeviceType.MOUSE) {
            double w = size * 0.56, h = size * 0.80;
            double ox = (size - w) / 2.0, oy = (size - h) / 2.0;
            g2.draw(new RoundRectangle2D.Double(ox, oy, w, h, w * 0.85, w * 0.85));
            int cx = size / 2;
            g2.drawLine(cx, (int) Math.round(oy + h * 0.18), cx, (int) Math.round(oy + h * 0.38));
        } else {
            double arcW = size * 0.68, arcH = size * 0.64;
            double ox = (size - arcW) / 2.0, oy = size * 0.14;
            g2.draw(new Arc2D.Double(ox, oy, arcW, arcH, 0, 180, Arc2D.OPEN));
        }
    }

    public static void drawBoltGlyph(Graphics2D g2, double x, double y, double size, Color color) {
        g2.setColor(color);
        Path2D bolt = new Path2D.Double();
        bolt.moveTo(x + size * 0.56, y + size * 0.08);
        bolt.lineTo(x + size * 0.18, y + size * 0.54);
        bolt.lineTo(x + size * 0.48, y + size * 0.54);
        bolt.lineTo(x + size * 0.38, y + size * 0.92);
        bolt.lineTo(x + size * 0.82, y + size * 0.44);
        bolt.lineTo(x + size * 0.52, y + size * 0.44);
        bolt.closePath();
        g2.fill(bolt);
    }
}
