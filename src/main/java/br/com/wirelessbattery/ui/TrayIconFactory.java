package br.com.wirelessbattery.ui;

import br.com.wirelessbattery.config.AppSettings;
import br.com.wirelessbattery.model.DeviceType;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.AbstractMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.Icon;

/**
 * Renderizador de ícones individuais transparentes para a bandeja do sistema do Windows
 * ({@code SystemTray}) com suporte a {@link AbstractMultiResolutionImage}.
 *
 * Em escalas pequenas ({@code 16x16} a {@code 20x20}), utiliza bitmaps alinhados por pixel
 * para evitar borramento causado pelo {@code WTrayIconPeer}; em escalas maiores, utiliza
 * renderização vetorial supersampled 4x com downsampling bicúbico progressivo.
 */
public final class TrayIconFactory {
    private static final String[] MOUSE_16 = {
            "..XXX..",
            ".X...X.",
            "X..X..X",
            "X..X..X",
            "X.....X",
            "X.....X",
            ".X...X.",
            "..XXX.."
    };

    private static final String[] HEADSET_16 = {
            "..XXXXXXX..",
            ".X.......X.",
            "X.........X",
            "X.........X",
            "XX.......XX",
            "XXX.....XXX",
            "XXX.....XXX",
            ".XX.....XX."
    };

    private static final String[] GAMEPAD_16 = {
            "..XXXXXXXXXX..",
            ".XXXXXXXXXXXX.",
            "XXX.XXXXXX.XXX",
            "XX...XXXX.X.XX",
            "XXX.XXXXXX.XXX",
            "XXXXX....XXXXX",
            ".XXX......XXX.",
            "..XX......XX.."
    };

    private static final String[] BATTERY_16 = {
            "XXXXXXXXXXX.",
            "X.........XX",
            "X.........XX",
            "X.........XX",
            "X.........XX",
            "XXXXXXXXXXX."
    };

    private static final String[] MOUSE_20 = {
            "..XXXXX..",
            ".X.....X.",
            "X...X...X",
            "X...X...X",
            "X...X...X",
            "X.......X",
            "X.......X",
            "X.......X",
            ".X.....X.",
            "..XXXXX.."
    };

    private static final String[] HEADSET_20 = {
            "...XXXXXXX...",
            "..X.......X..",
            ".X.........X.",
            "X...........X",
            "X...........X",
            "XXX.......XXX",
            "XXXX.....XXXX",
            "XXXX.....XXXX",
            "XXXX.....XXXX",
            ".XX.......XX."
    };

    private static final String[] GAMEPAD_20 = {
            "...XXXXXXXXXX...",
            "..XXXXXXXXXXXX..",
            ".XXX.XXXXXX.XXX.",
            "XXX...XXXX.X.XXX",
            "XXXX.XXXXXX.XXXX",
            "XXXXX......XXXXX",
            ".XXXX......XXXX.",
            ".XXX........XXX.",
            "..XX........XX.."
    };

    private static final String[] BATTERY_20 = {
            "XXXXXXXXXXXXX.",
            "X...........XX",
            "X...........XX",
            "X...........XX",
            "X...........XX",
            "X...........XX",
            "X...........XX",
            "XXXXXXXXXXXXX."
    };

    // Dígitos 5x7 para grade 16x16
    private static final String[][] DIGITS_5X7 = {
            {".XXX.", "X...X", "X...X", "X...X", "X...X", "X...X", ".XXX."}, // 0
            {"..X..", ".XX..", "..X..", "..X..", "..X..", "..X..", ".XXX."}, // 1
            {".XXX.", "X...X", "....X", "..XX.", ".X...", "X....", "XXXXX"}, // 2
            {".XXX.", "X...X", "....X", "..XX.", "....X", "X...X", ".XXX."}, // 3
            {"...X.", "..XX.", ".X.X.", "X..X.", "XXXXX", "...X.", "...X."}, // 4
            {"XXXXX", "X....", "XXXX.", "....X", "....X", "X...X", ".XXX."}, // 5
            {".XXX.", "X....", "X....", "XXXX.", "X...X", "X...X", ".XXX."}, // 6
            {"XXXXX", "....X", "...X.", "..X..", ".X...", ".X...", ".X..."}, // 7
            {".XXX.", "X...X", "X...X", ".XXX.", "X...X", "X...X", ".XXX."}, // 8
            {".XXX.", "X...X", "X...X", ".XXXX", "....X", "....X", ".XXX."}  // 9
    };

    // Dígitos compactos para "100" em 16x16 (3+1+4+1+4 = 13px)
    private static final String[] ONE_3X7 = {
            ".X.", "XX.", ".X.", ".X.", ".X.", ".X.", "XXX"
    };
    private static final String[] ZERO_4X7 = {
            ".XX.", "X..X", "X..X", "X..X", "X..X", "X..X", ".XX."
    };

    // Dígitos 6x9 bold (hastes de 2px) para grade 20x20
    private static final String[][] DIGITS_6X9 = {
            {".XXXX.", "XX..XX", "XX..XX", "XX..XX", "XX..XX", "XX..XX", "XX..XX", "XX..XX", ".XXXX."}, // 0
            {"..XX..", ".XXX..", "XXXX..", "..XX..", "..XX..", "..XX..", "..XX..", "..XX..", "XXXXXX"}, // 1
            {".XXXX.", "XX..XX", "....XX", "...XX.", "..XX..", ".XX...", "XX....", "XX....", "XXXXXX"}, // 2
            {".XXXX.", "XX..XX", "....XX", "..XXX.", "....XX", "....XX", "....XX", "XX..XX", ".XXXX."}, // 3
            {"...XX.", "..XXX.", ".X.XX.", "X..XX.", "XXXXXX", "...XX.", "...XX.", "...XX.", "...XX."}, // 4
            {"XXXXXX", "XX....", "XX....", "XXXXX.", "....XX", "....XX", "....XX", "XX..XX", ".XXXX."}, // 5
            {".XXXX.", "XX..XX", "XX....", "XXXXX.", "XX..XX", "XX..XX", "XX..XX", "XX..XX", ".XXXX."}, // 6
            {"XXXXXX", "XX..XX", "....XX", "...XX.", "..XX..", "..XX..", ".XX...", ".XX...", ".XX..."}, // 7
            {".XXXX.", "XX..XX", "XX..XX", ".XXXX.", "XX..XX", "XX..XX", "XX..XX", "XX..XX", ".XXXX."}, // 8
            {".XXXX.", "XX..XX", "XX..XX", ".XXXXX", "....XX", "....XX", "....XX", "XX..XX", ".XXXX."}  // 9
    };

    // Dígitos compactos para "100" em 20x20 (4+1+5+1+5 = 16px)
    private static final String[] ONE_4X9 = {
            "..X.", ".XX.", "X.X.", "..X.", "..X.", "..X.", "..X.", "..X.", "XXXX"
    };
    private static final String[] ZERO_5X9 = {
            ".XXX.", "X...X", "X...X", "X...X", "X...X", "X...X", "X...X", "X...X", ".XXX."
    };

    private static final String[] BOLT_3X5 = {
            "..X",
            ".XX",
            "XXX",
            ".X.",
            ".X."
    };

    private static final String[] BOLT_4X6 = {
            "..XX",
            ".XX.",
            "XXXX",
            ".XX.",
            ".X..",
            "X..."
    };

    private TrayIconFactory() {}

    /**
     * Retorna uma {@link AbstractMultiResolutionImage} que gera variantes na resolução nativa
     * solicitada pelo Windows (16px em 100%, 20px em 125%, 24px em 150%, 32px em 200%, etc.).
     */
    public static Image createMultiResTrayIcon(
            DeviceType type,
            Integer percent,
            boolean charging,
            boolean dimmed,
            boolean simulated,
            AppSettings settings
    ) {
        boolean lightTaskbar = WindowsTaskbarLocator.isWindowsTaskbarLight();
        int lowThreshold = settings != null ? settings.getLowBatteryThreshold() : 20;
        int mediumThreshold = settings != null ? settings.getMediumBatteryThreshold() : 50;
        return new DynamicTrayMultiResImage(
                type, percent, charging, dimmed, simulated, lightTaskbar, lowThreshold, mediumThreshold
        );
    }

    /**
     * Renderiza uma variante {@code BufferedImage} ARGB exata no tamanho em pixels informado.
     */
    public static BufferedImage renderAtSize(
            int size,
            DeviceType type,
            Integer percent,
            boolean charging,
            boolean dimmed,
            boolean simulated,
            AppSettings settings
    ) {
        boolean lightTaskbar = WindowsTaskbarLocator.isWindowsTaskbarLight();
        int lowThreshold = settings != null ? settings.getLowBatteryThreshold() : 20;
        int mediumThreshold = settings != null ? settings.getMediumBatteryThreshold() : 50;
        return renderAtSize(size, type, percent, charging, dimmed, simulated, lightTaskbar, lowThreshold, mediumThreshold);
    }

    public static Image createDevicePercentIcon(
            DeviceType type,
            Integer percent,
            boolean charging,
            boolean dimmed,
            boolean simulated,
            AppSettings settings
    ) {
        return renderAtSize(64, type, percent, charging, dimmed, simulated, settings);
    }

    public static Image createDevicePercentIcon(
            DeviceType type,
            Integer percent,
            boolean charging,
            boolean simulated,
            AppSettings settings
    ) {
        return createDevicePercentIcon(type, percent, charging, false, simulated, settings);
    }

    private static BufferedImage renderAtSize(
            int size,
            DeviceType type,
            Integer percent,
            boolean charging,
            boolean dimmed,
            boolean simulated,
            boolean lightTaskbar,
            int lowThreshold,
            int mediumThreshold
    ) {
        int clampedSize = Math.max(16, Math.min(256, size));
        Palette palette = computePalette(
                percent, charging, dimmed, lightTaskbar, lowThreshold, mediumThreshold, clampedSize <= 20
        );
        if (clampedSize <= 20) {
            return renderCompactPixelIcon(clampedSize, type, percent, charging, simulated, palette);
        }
        return renderSupersampledVectorIcon(clampedSize, type, percent, charging, simulated, lightTaskbar, palette);
    }

    private static Palette computePalette(
            Integer percent,
            boolean charging,
            boolean dimmed,
            boolean lightTaskbar,
            int lowThreshold,
            int mediumThreshold,
            boolean solidAlphaForMask
    ) {
        int alpha = (!solidAlphaForMask && dimmed) ? 165 : 255;
        Color baseFg = lightTaskbar ? new Color(0x11, 0x18, 0x27) : Color.WHITE;
        Color mutedFg = lightTaskbar ? new Color(0x6B, 0x72, 0x80) : new Color(0x9C, 0xA3, 0xAF);
        Color iconColor;
        if (percent == null || dimmed) {
            iconColor = solidAlphaForMask
                    ? mutedFg
                    : withAlpha(baseFg, percent == null ? 185 : alpha);
        } else {
            iconColor = withAlpha(baseFg, 255);
        }

        boolean isLow = !charging && percent != null && percent <= lowThreshold;
        boolean isMedium = !charging && percent != null && !isLow && percent <= mediumThreshold;

        Color textColor;
        Color barBase;
        if (charging) {
            Color c = lightTaskbar ? new Color(0x1D, 0x4E, 0xD8) : DeviceIconFactory.COLOR_CHARGING;
            textColor = withAlpha(c, alpha);
            barBase = c;
        } else if (percent == null) {
            textColor = solidAlphaForMask ? mutedFg : withAlpha(baseFg, 185);
            barBase = DeviceIconFactory.COLOR_NEUTRAL;
        } else if (isLow) {
            Color c = lightTaskbar ? new Color(0xDC, 0x26, 0x26) : DeviceIconFactory.COLOR_LOW;
            textColor = withAlpha(c, alpha);
            barBase = c;
        } else if (isMedium) {
            Color c = lightTaskbar ? new Color(0xB4, 0x53, 0x09) : DeviceIconFactory.COLOR_MEDIUM;
            textColor = withAlpha(c, alpha);
            barBase = c;
        } else {
            textColor = (dimmed && solidAlphaForMask) ? mutedFg : withAlpha(baseFg, alpha);
            barBase = DeviceIconFactory.COLOR_HIGH;
        }

        Color barColor = withAlpha(barBase, alpha);
        Color simColor = lightTaskbar ? new Color(0xD9, 0x77, 0x06, 255) : new Color(0xE5, 0xB8, 0x5A, 255);
        Color boltColor = lightTaskbar ? new Color(0x1D, 0x4E, 0xD8, 255) : new Color(0x77, 0xA7, 0xED, 255);
        return new Palette(baseFg, iconColor, textColor, barColor, simColor, boltColor, alpha);
    }

    /**
     * Renderiza grades nativas de {@code 16x16} (100% DPI) e {@code 20x20} (125% DPI) pixel a pixel,
     * sem suavização borrada e aproveitando todos os pixels úteis sem caixa ou barra desperdiçada.
     */
    private static BufferedImage renderCompactPixelIcon(
            int size,
            DeviceType type,
            Integer percent,
            boolean charging,
            boolean simulated,
            Palette palette
    ) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        DeviceType safeType = type != null ? type : DeviceType.OTHER;
        boolean grid20 = size >= 19;

        String[] deviceBitmap = selectDeviceBitmap(safeType, grid20);
        int devW = deviceBitmap[0].length();
        int devX = (size - devW) / 2;
        int devY = (safeType == DeviceType.OTHER) ? 1 : 0;
        drawBitmap(img, deviceBitmap, devX, devY, palette.iconColor.getRGB());

        if (simulated) {
            int dotArgb = palette.simColor.getRGB();
            setPixelSafe(img, 0, 0, dotArgb);
            setPixelSafe(img, 1, 0, dotArgb);
            setPixelSafe(img, 0, 1, dotArgb);
            setPixelSafe(img, 1, 1, dotArgb);
        }

        if (charging) {
            String[] bolt = grid20 ? BOLT_4X6 : BOLT_3X5;
            drawBitmap(img, bolt, size - bolt[0].length(), 0, palette.boltColor.getRGB());
        }

        int digitRgb = palette.textColor.getRGB();
        if (percent == null) {
            int dashY = grid20 ? 15 : 12;
            int dashW = grid20 ? 5 : 4;
            int gap = 2;
            int totalW = dashW * 2 + gap;
            int startX = (size - totalW) / 2;
            fillRectSafe(img, startX, dashY, dashW, 1, digitRgb);
            fillRectSafe(img, startX + dashW + gap, dashY, dashW, 1, digitRgb);
            if (grid20) {
                fillRectSafe(img, startX, dashY + 1, dashW, 1, digitRgb);
                fillRectSafe(img, startX + dashW + gap, dashY + 1, dashW, 1, digitRgb);
            }
            return img;
        }

        int clamped = Math.max(0, Math.min(100, percent));
        int digitY = size - (grid20 ? 9 : 7);

        if (clamped == 100) {
            if (grid20) {
                int totalW = 4 + 1 + 5 + 1 + 5;
                int startX = (size - totalW) / 2;
                drawBitmap(img, ONE_4X9, startX, digitY, digitRgb);
                drawBitmap(img, ZERO_5X9, startX + 5, digitY, digitRgb);
                drawBitmap(img, ZERO_5X9, startX + 11, digitY, digitRgb);
            } else {
                int totalW = 3 + 1 + 4 + 1 + 4;
                int startX = (size - totalW) / 2;
                drawBitmap(img, ONE_3X7, startX, digitY, digitRgb);
                drawBitmap(img, ZERO_4X7, startX + 4, digitY, digitRgb);
                drawBitmap(img, ZERO_4X7, startX + 9, digitY, digitRgb);
            }
        } else if (clamped < 10) {
            String[] glyph = grid20 ? DIGITS_6X9[clamped] : DIGITS_5X7[clamped];
            int startX = (size - glyph[0].length()) / 2;
            drawBitmap(img, glyph, startX, digitY, digitRgb);
        } else {
            int tens = clamped / 10;
            int ones = clamped % 10;
            String[] left = grid20 ? DIGITS_6X9[tens] : DIGITS_5X7[tens];
            String[] right = grid20 ? DIGITS_6X9[ones] : DIGITS_5X7[ones];
            int gw = left[0].length();
            int gap = 2;
            int startX = (size - (gw * 2 + gap)) / 2;
            drawBitmap(img, left, startX, digitY, digitRgb);
            drawBitmap(img, right, startX + gw + gap, digitY, digitRgb);
        }

        return img;
    }

    /**
     * Renderiza escalas {@code >= 21px} (150% DPI em diante e visualização ampliada 64x64)
     * em canvas supersampled 4x com downsampling bicúbico progressivo.
     */
    private static BufferedImage renderSupersampledVectorIcon(
            int size,
            DeviceType type,
            Integer percent,
            boolean charging,
            boolean simulated,
            boolean lightTaskbar,
            Palette palette
    ) {
        int ss = size * 4;
        BufferedImage hi = new BufferedImage(ss, ss, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = hi.createGraphics();
        applyHighQualityHints(g);

        DeviceType safeType = type != null ? type : DeviceType.OTHER;
        int iconSize = Math.round(ss * 0.48f);
        int iconX = (ss - iconSize) / 2;
        int iconY = 0;
        Icon deviceIcon = DeviceIconFactory.createDeviceIcon(safeType, iconSize, palette.iconColor);
        deviceIcon.paintIcon(null, g, iconX, iconY);

        if (simulated) {
            int dotSize = Math.max(4, Math.round(ss * 0.125f));
            int dotMargin = Math.max(1, Math.round(ss * 0.03f));
            g.setColor(palette.simColor);
            g.fillOval(dotMargin, dotMargin, dotSize, dotSize);
        }

        if (charging) {
            double boltSize = Math.max(8.0, ss * 0.24);
            double boltX = ss - boltSize - Math.max(1.0, ss * 0.02);
            double boltY = Math.max(1.0, ss * 0.02);
            DeviceIconFactory.drawBoltGlyph(g, boltX, boltY, boltSize, palette.boltColor);
        }

        String text = percent == null ? "--" : String.valueOf(Math.max(0, Math.min(100, percent)));
        boolean showBar = size >= 24;
        float fontSize = text.length() >= 3 ? (ss * 0.37f) : (ss * 0.47f);
        g.setFont(new Font("Segoe UI", Font.BOLD, Math.round(fontSize)));
        FontMetrics fm = g.getFontMetrics();

        int barH = showBar ? Math.max(4, Math.round(ss * 0.062f)) : 0;
        int zoneTop = Math.round(ss * 0.46f);
        int zoneBottom = showBar ? (ss - barH - Math.max(2, Math.round(ss * 0.03f))) : ss;
        int zoneH = Math.max(1, zoneBottom - zoneTop);
        int tx = (ss - fm.stringWidth(text)) / 2;
        int ty = zoneTop + ((zoneH - (fm.getAscent() - fm.getDescent())) / 2) + fm.getAscent();
        g.setColor(palette.textColor);
        g.drawString(text, tx, ty);

        if (showBar) {
            int barX = Math.max(2, Math.round(ss * 0.035f));
            int barY = ss - barH;
            int barW = ss - (barX * 2);
            Color trackColor = lightTaskbar
                    ? new Color(0, 0, 0, 55)
                    : new Color(255, 255, 255, 55);
            g.setColor(trackColor);
            g.fill(new RoundRectangle2D.Float(barX, barY, barW, barH, barH, barH));

            if (percent != null && percent > 0) {
                int fillW = Math.max(barH, Math.round(barW * (percent / 100f)));
                g.setColor(palette.barColor);
                g.fill(new RoundRectangle2D.Float(barX, barY, Math.min(barW, fillW), barH, barH, barH));
            }
        }

        g.dispose();
        return progressiveDownsample(hi, size);
    }

    private static BufferedImage progressiveDownsample(BufferedImage src, int targetSize) {
        BufferedImage current = src;
        while (current.getWidth() / 2 >= targetSize && current.getWidth() > targetSize) {
            int nextSize = Math.max(targetSize, current.getWidth() / 2);
            current = scaleImage(current, nextSize);
        }
        if (current.getWidth() != targetSize || current.getHeight() != targetSize) {
            current = scaleImage(current, targetSize);
        }
        return current;
    }

    private static BufferedImage scaleImage(BufferedImage src, int dstSize) {
        BufferedImage dst = new BufferedImage(dstSize, dstSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, dstSize, dstSize, null);
        g.dispose();
        return dst;
    }

    private static void applyHighQualityHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    private static String[] selectDeviceBitmap(DeviceType type, boolean grid20) {
        return switch (type) {
            case MOUSE -> grid20 ? MOUSE_20 : MOUSE_16;
            case HEADSET -> grid20 ? HEADSET_20 : HEADSET_16;
            case GAMEPAD -> grid20 ? GAMEPAD_20 : GAMEPAD_16;
            default -> grid20 ? BATTERY_20 : BATTERY_16;
        };
    }

    private static void drawBitmap(BufferedImage img, String[] rows, int ox, int oy, int argb) {
        for (int r = 0; r < rows.length; r++) {
            String row = rows[r];
            for (int c = 0; c < row.length(); c++) {
                if (row.charAt(c) != '.') {
                    setPixelSafe(img, ox + c, oy + r, argb);
                }
            }
        }
    }

    private static void fillRectSafe(BufferedImage img, int x, int y, int w, int h, int argb) {
        for (int dy = 0; dy < h; dy++) {
            for (int dx = 0; dx < w; dx++) {
                setPixelSafe(img, x + dx, y + dy, argb);
            }
        }
    }

    private static void setPixelSafe(BufferedImage img, int x, int y, int argb) {
        if (x >= 0 && y >= 0 && x < img.getWidth() && y < img.getHeight()) {
            img.setRGB(x, y, argb);
        }
    }

    private static Color withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    private record Palette(
            Color baseFg,
            Color iconColor,
            Color textColor,
            Color barColor,
            Color simColor,
            Color boltColor,
            int alpha
    ) {}

    private static final class DynamicTrayMultiResImage extends AbstractMultiResolutionImage {
        private static final int[] STANDARD_SIZES = {16, 20, 24, 28, 32, 48, 64};

        private final DeviceType type;
        private final Integer percent;
        private final boolean charging;
        private final boolean dimmed;
        private final boolean simulated;
        private final boolean lightTaskbar;
        private final int lowThreshold;
        private final int mediumThreshold;
        private final Map<Integer, BufferedImage> cache = new ConcurrentHashMap<>();

        private DynamicTrayMultiResImage(
                DeviceType type,
                Integer percent,
                boolean charging,
                boolean dimmed,
                boolean simulated,
                boolean lightTaskbar,
                int lowThreshold,
                int mediumThreshold
        ) {
            this.type = type;
            this.percent = percent;
            this.charging = charging;
            this.dimmed = dimmed;
            this.simulated = simulated;
            this.lightTaskbar = lightTaskbar;
            this.lowThreshold = lowThreshold;
            this.mediumThreshold = mediumThreshold;
        }

        private BufferedImage variantFor(int targetSize) {
            int key = Math.max(16, Math.min(256, targetSize));
            return cache.computeIfAbsent(key, sz -> renderAtSize(
                    sz, type, percent, charging, dimmed, simulated, lightTaskbar, lowThreshold, mediumThreshold
            ));
        }

        @Override
        public Image getResolutionVariant(double destImageWidth, double destImageHeight) {
            int target = (int) Math.round(Math.max(destImageWidth, destImageHeight));
            return variantFor(target);
        }

        @Override
        public List<Image> getResolutionVariants() {
            BufferedImage[] out = new BufferedImage[STANDARD_SIZES.length];
            for (int i = 0; i < STANDARD_SIZES.length; i++) {
                out[i] = variantFor(STANDARD_SIZES[i]);
            }
            return List.of(out);
        }

        @Override
        protected Image getBaseImage() {
            return variantFor(16);
        }
    }
}
