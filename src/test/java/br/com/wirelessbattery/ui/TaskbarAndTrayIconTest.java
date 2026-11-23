package br.com.wirelessbattery.ui;

import static org.junit.jupiter.api.Assertions.*;

import br.com.wirelessbattery.config.AppSettings;
import br.com.wirelessbattery.model.DeviceReading;
import br.com.wirelessbattery.model.DeviceType;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskbarAndTrayIconTest {

    @Test
    void trayIconsUseActualDeviceTypeAndSeparateRealFromSimulated(@TempDir Path tempDir) {
        AppSettings settings = new AppSettings(tempDir.resolve("s.properties"));

        BufferedImage mouseIcon = (BufferedImage) TrayIconFactory.createDevicePercentIcon(
                DeviceType.MOUSE, 85, false, false, false, settings
        );
        BufferedImage headsetIcon = (BufferedImage) TrayIconFactory.createDevicePercentIcon(
                DeviceType.HEADSET, 85, false, false, false, settings
        );
        BufferedImage gamepadIcon = (BufferedImage) TrayIconFactory.createDevicePercentIcon(
                DeviceType.GAMEPAD, 85, false, false, false, settings
        );
        BufferedImage simulatedMouseIcon = (BufferedImage) TrayIconFactory.createDevicePercentIcon(
                DeviceType.MOUSE, 85, false, false, true, settings
        );

        assertEquals(64, mouseIcon.getWidth());
        assertEquals(64, mouseIcon.getHeight());

        // Mesmo percentual (85%), mas tipos diferentes precisam gerar ícones distintos
        assertNotEquals(pixelHash(mouseIcon), pixelHash(headsetIcon));
        assertNotEquals(pixelHash(mouseIcon), pixelHash(gamepadIcon));
        assertNotEquals(pixelHash(headsetIcon), pixelHash(gamepadIcon));

        // Real vs Simulado deve ser visualmente distinto
        assertNotEquals(pixelHash(mouseIcon), pixelHash(simulatedMouseIcon));
    }

    @Test
    void taskbarWidgetAccommodatesIconsAndPercentagesForUpToThreeDevices(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Requer ambiente gráfico Swing");

        AppSettings settings = new AppSettings(tempDir.resolve("w.properties"));
        ThemeManager themeManager = new ThemeManager();

        final int[] widths = new int[3];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            TaskbarWidgetWindow widget = new TaskbarWidgetWindow(
                    settings, themeManager, () -> {}, () -> {}, () -> {}, () -> {}
            );
            try {
                DeviceReading mouse = DeviceReading.realConnected(
                        "delux", "Delux M800 Pro", DeviceType.MOUSE, 85, false, "2.4 GHz", "OK"
                );
                DeviceReading headset = DeviceReading.realConnected(
                        "akko", "Akko Verge S9", DeviceType.HEADSET, 62, false, "2.4 GHz", "OK"
                );
                DeviceReading gamepad = DeviceReading.realConnected(
                        "cyclone2", "GameSir Cyclone 2", DeviceType.GAMEPAD, 90, false, "2.4 GHz", "OK"
                );

                widget.updateReadings(List.of(mouse));
                widths[0] = widget.getContentPane().getPreferredSize().width;

                widget.updateReadings(List.of(gamepad, mouse));
                widths[1] = widget.getContentPane().getPreferredSize().width;

                widget.updateReadings(List.of(gamepad, headset, mouse));
                widths[2] = widget.getContentPane().getPreferredSize().width;
            } finally {
                widget.shutdown();
            }
        });

        assertTrue(widths[0] >= 62, "Largura mínima para 1 dispositivo");
        assertTrue(widths[1] > widths[0], "2 dispositivos devem ocupar mais espaço que 1");
        assertTrue(widths[2] > widths[1], "3 dispositivos devem ocupar mais espaço que 2");
    }

    @Test
    void taskbarWidgetUsesYellowAtOrBelowMediumThresholdAndRedAtOrBelowLow(@TempDir Path tempDir) {
        AppSettings settings = new AppSettings(tempDir.resolve("colors.properties"));
        Color darkFg = new Color(0xF1, 0xF3, 0xF5);
        Color lightFg = new Color(0x11, 0x18, 0x27);

        // Tema escuro: <= 50% e > 20% deve ficar amarelo; <= 20% vermelho; > 50% ou carregando padrão
        assertEquals(DeviceIconFactory.COLOR_MEDIUM,
                TaskbarWidgetWindow.resolvePercentTextColor(50, false, false, settings, darkFg));
        assertEquals(DeviceIconFactory.COLOR_MEDIUM,
                TaskbarWidgetWindow.resolvePercentTextColor(36, false, false, settings, darkFg));
        assertEquals(DeviceIconFactory.COLOR_MEDIUM,
                TaskbarWidgetWindow.resolvePercentTextColor(21, false, false, settings, darkFg));

        assertEquals(DeviceIconFactory.COLOR_LOW,
                TaskbarWidgetWindow.resolvePercentTextColor(20, false, false, settings, darkFg));
        assertEquals(DeviceIconFactory.COLOR_LOW,
                TaskbarWidgetWindow.resolvePercentTextColor(10, false, false, settings, darkFg));

        assertEquals(darkFg,
                TaskbarWidgetWindow.resolvePercentTextColor(85, false, false, settings, darkFg));
        assertEquals(darkFg,
                TaskbarWidgetWindow.resolvePercentTextColor(12, true, false, settings, darkFg));

        // Tema claro: âmbar e vermelho de alto contraste
        assertEquals(new Color(0xB4, 0x53, 0x09),
                TaskbarWidgetWindow.resolvePercentTextColor(36, false, true, settings, lightFg));
        assertEquals(new Color(0xDC, 0x26, 0x26),
                TaskbarWidgetWindow.resolvePercentTextColor(15, false, true, settings, lightFg));
    }

    @Test
    void multiResTrayIconCopiesLosslesslyUnderWTrayIconPeerDefaultDraw(@TempDir Path tempDir) {
        AppSettings settings = new AppSettings(tempDir.resolve("m.properties"));
        Image mri = TrayIconFactory.createMultiResTrayIcon(
                DeviceType.MOUSE, 85, false, false, false, settings
        );

        for (int targetSize : new int[]{16, 20, 24, 32}) {
            BufferedImage dst = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = dst.createGraphics();
            g.setPaintMode();
            g.drawImage(mri, 0, 0, targetSize, targetSize, null);
            g.dispose();

            BufferedImage expected = TrayIconFactory.renderAtSize(
                    targetSize, DeviceType.MOUSE, 85, false, false, false, settings
            );
            assertImagesEqual(expected, dst, "Variante " + targetSize + "x" + targetSize + " deve copiar sem perda");
        }
    }

    @Test
    void compactSixteenAndTwentyPixelIconsAreDistinctCrispAndHandleEdgeValues(@TempDir Path tempDir) {
        AppSettings settings = new AppSettings(tempDir.resolve("c.properties"));

        for (int sz : new int[]{16, 20}) {
            BufferedImage mouse = TrayIconFactory.renderAtSize(sz, DeviceType.MOUSE, 85, false, false, false, settings);
            BufferedImage headset = TrayIconFactory.renderAtSize(sz, DeviceType.HEADSET, 85, false, false, false, settings);
            BufferedImage gamepad = TrayIconFactory.renderAtSize(sz, DeviceType.GAMEPAD, 85, false, false, false, settings);
            BufferedImage other = TrayIconFactory.renderAtSize(sz, DeviceType.OTHER, 85, false, false, false, settings);
            BufferedImage simulated = TrayIconFactory.renderAtSize(sz, DeviceType.MOUSE, 85, false, false, true, settings);

            assertEquals(sz, mouse.getWidth());
            assertEquals(sz, mouse.getHeight());
            assertNotEquals(pixelHash(mouse), pixelHash(headset));
            assertNotEquals(pixelHash(mouse), pixelHash(gamepad));
            assertNotEquals(pixelHash(mouse), pixelHash(other));
            assertNotEquals(pixelHash(headset), pixelHash(gamepad));
            assertNotEquals(pixelHash(mouse), pixelHash(simulated));

            int upperEndY = sz <= 18 ? 8 : 10;
            int lowerStartY = sz <= 18 ? 9 : 11;
            for (BufferedImage variant : new BufferedImage[]{
                    mouse, headset, gamepad, other,
                    TrayIconFactory.renderAtSize(sz, DeviceType.MOUSE, 100, true, false, false, settings),
                    TrayIconFactory.renderAtSize(sz, DeviceType.GAMEPAD, 0, false, false, false, settings),
                    TrayIconFactory.renderAtSize(sz, DeviceType.HEADSET, null, false, false, false, settings),
                    TrayIconFactory.renderAtSize(sz, DeviceType.MOUSE, 10, false, false, false, settings)
            }) {
                assertTrue(hasOpaquePixelsInBand(variant, 0, upperEndY),
                        "Banda superior do ícone " + sz + "px deve conter pixels opacos");
                assertTrue(hasOpaquePixelsInBand(variant, lowerStartY, sz),
                        "Banda inferior de dígitos " + sz + "px deve conter pixels opacos");
            }
        }
    }

    private static boolean hasOpaquePixelsInBand(BufferedImage img, int yStart, int yEndExclusive) {
        for (int y = yStart; y < yEndExclusive; y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int alpha = (img.getRGB(x, y) >>> 24) & 0xFF;
                if (alpha == 255) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void assertImagesEqual(BufferedImage expected, BufferedImage actual, String msg) {
        assertEquals(expected.getWidth(), actual.getWidth(), msg);
        assertEquals(expected.getHeight(), actual.getHeight(), msg);
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                assertEquals(expected.getRGB(x, y), actual.getRGB(x, y),
                        msg + " em (" + x + "," + y + ")");
            }
        }
    }

    private static long pixelHash(BufferedImage img) {
        long h = 1469598103934665603L;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                h ^= img.getRGB(x, y);
                h *= 1099511628211L;
            }
        }
        return h;
    }
}
