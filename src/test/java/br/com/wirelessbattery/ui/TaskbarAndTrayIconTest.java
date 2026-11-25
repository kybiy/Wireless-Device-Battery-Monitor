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
    void widgetStaysVerticallyCenteredInsideTaskbar() {
        assertEquals(1045, TaskbarWidgetWindow.widgetTop(1040, 40, 30));
        assertEquals(1049, TaskbarWidgetWindow.widgetTop(1040, 48, 30));
    }

    @Test
    void shellWindowsDoNotSwitchWidgetToSystemTray() {
        assertFalse(WindowsTaskbarLocator.useShellIndicators("Progman"));
        assertFalse(WindowsTaskbarLocator.useShellIndicators("WorkerW"));
        assertFalse(WindowsTaskbarLocator.useShellIndicators("TopLevelWindowForOverflowXamlIsland"));
        assertFalse(WindowsTaskbarLocator.useShellIndicators("NotifyIconOverflowWindow"));
        assertFalse(WindowsTaskbarLocator.useShellIndicators("MultitaskingViewFrame"));
        assertFalse(WindowsTaskbarLocator.useShellIndicators("Shell_TrayWnd"));
        assertFalse(WindowsTaskbarLocator.useShellIndicators("Notepad"));
    }

    @Test
    void svgIconFactoryCachesInstancesByIconSizeAndColor() {
        var icon1 = DeviceIconFactory.createSvgIcon(DeviceIconFactory.IconName.MOUSE, 18, DeviceIconFactory.COLOR_HIGH);
        var icon2 = DeviceIconFactory.createSvgIcon(DeviceIconFactory.IconName.MOUSE, 18, DeviceIconFactory.COLOR_HIGH);
        var iconDifferentColor = DeviceIconFactory.createSvgIcon(DeviceIconFactory.IconName.MOUSE, 18, DeviceIconFactory.COLOR_LOW);
        assertSame(icon1, icon2);
        assertNotSame(icon1, iconDifferentColor);
    }

    @Test
    void premultipliedBgraAppliesHitTestAlphaAndScalesChannels() {
        int[] src = {
                0x00000000, // totalmente transparente -> 0x01000000 (hit-test)
                0xFF204080, // opaco -> inalterado
                0x80FF0000  // 50% vermelho -> R ~= 128
        };
        int[] out = NativeTaskbarWidget.toPremultipliedBgraWithHitTestAlpha(src);
        assertEquals(0x01000000, out[0]);
        assertEquals(0xFF204080, out[1]);
        assertEquals(0x80, (out[2] >>> 24) & 0xFF);
        int pr = (out[2] >>> 16) & 0xFF;
        assertTrue(pr >= 127 && pr <= 129, "Canal vermelho pré-multiplicado: " + pr);
    }

    @Test
    void nativeWidgetCreatesLayeredChildWindowAndFreesGdiUserHandlesOnWindows() {
        if (!WindowsTaskbarLocator.isWindows()) {
            return;
        }
        AppSettings settings = new AppSettings();
        var logger = new br.com.wirelessbattery.diagnostics.AppLogger();
        var sim = new br.com.wirelessbattery.simulation.SimulationManager(logger);
        sim.setEnabled(true);
        List<DeviceReading> items = sim.getCurrentReadings();

        var hInst = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetModuleHandle(null);
        var parent = com.sun.jna.platform.win32.User32.INSTANCE.CreateWindowEx(
                0, "STATIC", "TestTaskbarHost", 0x80000000, // WS_POPUP
                0, 0, 400, 40, null, null, hInst, null
        );
        assertNotNull(parent, "Janela pai Win32 de teste deve ser criada");
        try {
            var widget = new NativeTaskbarWidget(settings, logger, () -> {}, () -> {}, () -> {}, () -> {});
            // Primeiro ciclo aquece classes/fontes
            assertTrue(widget.verifyChildCreationAndRenderOnCurrentThread(parent, items),
                    "WS_CHILD + WS_EX_LAYERED + UpdateLayeredWindow deve ter sucesso na janela pai");

            int gdiBefore = NativeTaskbarWidget.getProcessGuiResources(0); // GR_GDIOBJECTS
            int userBefore = NativeTaskbarWidget.getProcessGuiResources(1); // GR_USEROBJECTS

            for (int i = 0; i < 20; i++) {
                assertTrue(widget.verifyChildCreationAndRenderOnCurrentThread(parent, items));
            }

            int gdiAfter = NativeTaskbarWidget.getProcessGuiResources(0);
            int userAfter = NativeTaskbarWidget.getProcessGuiResources(1);

            assertTrue(Math.abs(gdiAfter - gdiBefore) <= 2,
                    "Sem vazamento de handles GDI após 20 ciclos: before=" + gdiBefore + " after=" + gdiAfter);
            assertEquals(userBefore, userAfter,
                    "Sem vazamento de handles USER após 20 ciclos: before=" + userBefore + " after=" + userAfter);
        } finally {
            com.sun.jna.platform.win32.User32.INSTANCE.DestroyWindow(parent);
        }
    }

    @Test
    void realAndSimulatedReadingsShareSameNativeBackendAndDiagnosticPipeline() throws Exception {
        if (!WindowsTaskbarLocator.isWindows()) {
            return;
        }
        AppSettings settings = new AppSettings();
        ThemeManager theme = new ThemeManager();
        var logger = new br.com.wirelessbattery.diagnostics.AppLogger();
        var sim = new br.com.wirelessbattery.simulation.SimulationManager(logger);
        sim.setEnabled(true);

        List<DeviceReading> simulatedItems = sim.getCurrentReadings();
        List<DeviceReading> realItems = List.of(
                DeviceReading.realConnected("delux-m800-pro", "Delux M800 Pro", DeviceType.MOUSE, 78, false, "2.4 GHz", "OK"),
                DeviceReading.realConnected("akko-verge-s9", "Akko S9 Ultra", DeviceType.HEADSET, 62, false, "2.4 GHz", "OK"),
                DeviceReading.realConnected("gamesir-cyclone-2", "GameSir Cyclone 2", DeviceType.GAMEPAD, 90, false, "2.4 GHz", "OK")
        );

        javax.swing.SwingUtilities.invokeAndWait(() -> {
            TaskbarWidgetWindow widgetWindow = new TaskbarWidgetWindow(
                    settings, theme, logger, () -> {}, () -> {}, () -> {}, () -> {}
            );
            try {
                assertEquals("NATIVE", widgetWindow.getRequestedBackendName());
                assertEquals("NATIVE", widgetWindow.getEffectiveBackendName());
                assertEquals("nenhum", widgetWindow.getFallbackReason());

                widgetWindow.updateReadings(realItems);
                assertEquals("NATIVE", widgetWindow.getEffectiveBackendName());
                assertEquals(3, widgetWindow.getCurrentItems().size());
                String realDiag = widgetWindow.formatDiagnosticSummary(false, 3, 0);
                assertTrue(realDiag.contains("modo=REAL"));
                assertTrue(realDiag.contains("backendSolicitado=NATIVE"));
                assertTrue(realDiag.contains("backendEfetivo=NATIVE"));

                widgetWindow.updateReadings(simulatedItems);
                assertEquals("NATIVE", widgetWindow.getEffectiveBackendName());
                assertEquals(3, widgetWindow.getCurrentItems().size());
                String simDiag = widgetWindow.formatDiagnosticSummary(true, 0, 3);
                assertTrue(simDiag.contains("modo=SIMULACAO"));
                assertTrue(simDiag.contains("backendSolicitado=NATIVE"));
                assertTrue(simDiag.contains("backendEfetivo=NATIVE"));
            } finally {
                widgetWindow.shutdown();
            }
        });

        var hInst = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetModuleHandle(null);
        var parent = com.sun.jna.platform.win32.User32.INSTANCE.CreateWindowEx(
                0, "STATIC", "TestTaskbarHostDual", 0x80000000,
                0, 0, 400, 40, null, null, hInst, null
        );
        assertNotNull(parent);
        try {
            var nativeWidget = new NativeTaskbarWidget(settings, logger, () -> {}, () -> {}, () -> {}, () -> {});
            assertTrue(nativeWidget.verifyChildCreationAndRenderOnCurrentThread(parent, realItems));
            assertTrue(nativeWidget.getLastRenderStatus().contains("modo=REAL"));

            assertTrue(nativeWidget.verifyChildCreationAndRenderOnCurrentThread(parent, simulatedItems));
            assertTrue(nativeWidget.getLastRenderStatus().contains("modo=SIMULACAO"));
        } finally {
            com.sun.jna.platform.win32.User32.INSTANCE.DestroyWindow(parent);
        }
    }

    @Test
    void diagnosticsKeepRecentLinesAfterMultilineMessages() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            var area = new javax.swing.JTextArea();
            int limit = br.com.wirelessbattery.diagnostics.AppLogger.MAX_ENTRIES;
            for (int i = 0; i < limit; i++) HidDiagnosticsDialog.appendRecentLog(area, "entry-" + i);
            HidDiagnosticsDialog.appendRecentLog(area, "first\nsecond");
            assertEquals(limit + 1, area.getLineCount());
            assertTrue(area.getText().startsWith("entry-2\n"));
            assertTrue(area.getText().endsWith("first\nsecond\n"));
        });
    }


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
    void taskbarWidgetTooltipAndItemsUpdateCleanlyAcrossHeadsetSwitches(@TempDir Path tempDir) throws Exception {
        DeviceReading mouse = DeviceReading.realConnected(
                "delux-m800-pro", "Delux M800 Pro", DeviceType.MOUSE, 85, false, "2.4 GHz", "OK"
        );
        DeviceReading gamepad = DeviceReading.realConnected(
                "gamesir-cyclone-2", "GameSir Cyclone 2", DeviceType.GAMEPAD, 90, false, "2.4 GHz / USB", "OK"
        );
        DeviceReading akko = DeviceReading.realConnected(
                "akko-verge-s9", "Akko Verge S9 Ultra", DeviceType.HEADSET, 62, false, "2.4 GHz", "OK"
        );
        DeviceReading qcy = DeviceReading.realConnected(
                "qcy-t13", "QCY T13", DeviceType.HEADSET, 74, false, "Bluetooth", "OK"
        );
        DeviceReading qcyUnavailable = DeviceReading.realBatteryUnavailable(
                "qcy-t13", "QCY T13", DeviceType.HEADSET, "Bluetooth", "Sem leitura HFP/PnP"
        );
        DeviceReading genericNone = DeviceReading.realDisconnected(
                "headset-slot", "Fone", DeviceType.HEADSET, "Sem conexão", "Nenhum headset conectado"
        );

        List<DeviceReading> withAkko = List.of(mouse, akko, gamepad);
        List<DeviceReading> withQcy = List.of(mouse, qcy, gamepad);
        List<DeviceReading> withQcyNoBatt = List.of(mouse, qcyUnavailable, gamepad);
        List<DeviceReading> withNone = List.of(mouse, genericNone, gamepad);

        // 1. Tooltip nativo em texto puro (NativeTaskbarWidget.buildPlainTooltip)
        String tipAkko = NativeTaskbarWidget.buildPlainTooltip(withAkko);
        assertTrue(tipAkko.contains("Akko Verge S9 Ultra: 62% [2.4 GHz]"), tipAkko);
        assertFalse(tipAkko.contains("QCY T13"), tipAkko);

        String tipQcy = NativeTaskbarWidget.buildPlainTooltip(withQcy);
        assertTrue(tipQcy.contains("QCY T13: 74% [Bluetooth]"), tipQcy);
        assertFalse(tipQcy.contains("Akko"), tipQcy);
        assertFalse(tipQcy.contains("62%"), tipQcy);

        String tipQcyUnavail = NativeTaskbarWidget.buildPlainTooltip(withQcyNoBatt);
        assertTrue(tipQcyUnavail.contains("QCY T13: Conectado · Bluetooth (leitura indisponível)"), tipQcyUnavail);
        assertFalse(tipQcyUnavail.contains("74%"), tipQcyUnavail);

        String tipNone = NativeTaskbarWidget.buildPlainTooltip(withNone);
        assertTrue(tipNone.contains("Fone: Desconectado"), tipNone);
        assertFalse(tipNone.contains("62%"), tipNone);
        assertFalse(tipNone.contains("74%"), tipNone);

        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        // 2. Atualização do TaskbarWidgetWindow mantém 3 slots na ordem [MOUSE, HEADSET, GAMEPAD] e limpa tooltip
        AppSettings settings = new AppSettings(tempDir.resolve("tooltip-swap.properties"));
        ThemeManager themeManager = new ThemeManager();
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            TaskbarWidgetWindow widget = new TaskbarWidgetWindow(
                    settings, themeManager, () -> {}, () -> {}, () -> {}, () -> {}
            );
            try {
                javax.swing.JComponent pane = (javax.swing.JComponent) widget.getContentPane();

                widget.updateReadings(List.of(gamepad, akko, mouse));
                assertEquals(List.of(DeviceType.MOUSE, DeviceType.HEADSET, DeviceType.GAMEPAD),
                        widget.getCurrentItems().stream().map(DeviceReading::deviceType).toList());
                assertEquals("Akko Verge S9 Ultra", widget.getCurrentItems().get(1).displayName());
                assertTrue(pane.getToolTipText().contains("Akko Verge S9 Ultra: 62% [2.4 GHz]"));
                assertFalse(pane.getToolTipText().contains("QCY T13"));

                widget.updateReadings(List.of(gamepad, qcy, mouse));
                assertEquals(3, widget.getCurrentItems().size());
                assertEquals("QCY T13", widget.getCurrentItems().get(1).displayName());
                assertTrue(pane.getToolTipText().contains("QCY T13: 74% [Bluetooth]"));
                assertFalse(pane.getToolTipText().contains("Akko"));
                assertFalse(pane.getToolTipText().contains("62%"));

                widget.updateReadings(withQcyNoBatt);
                assertEquals(3, widget.getCurrentItems().size());
                assertTrue(pane.getToolTipText().contains("QCY T13: Conectado · Bluetooth (leitura indisponível)"));
                assertFalse(pane.getToolTipText().contains("74%"));

                widget.updateReadings(withNone);
                assertEquals(3, widget.getCurrentItems().size());
                assertEquals("Fone", widget.getCurrentItems().get(1).displayName());
                assertNull(widget.getCurrentItems().get(1).lastKnownPercent());
                assertTrue(pane.getToolTipText().contains("Fone: Desconectado"));
                assertFalse(pane.getToolTipText().contains("62%"));
                assertFalse(pane.getToolTipText().contains("74%"));
            } finally {
                widget.shutdown();
            }
        });
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
