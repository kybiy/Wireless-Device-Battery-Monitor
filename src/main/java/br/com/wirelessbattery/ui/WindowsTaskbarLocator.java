package br.com.wirelessbattery.ui;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.ShellAPI;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinDef.UINT_PTR;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.geom.AffineTransform;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Localiza a geometria da barra de tarefas do Windows 10/11 ({@code Shell_TrayWnd},
 * {@code TrayNotifyWnd} e {@code ShellFeedsTaskbarViewWindow}), detecta modo tela cheia real,
 * auto-hide, escala DPI por monitor e tema da barra de tarefas usando chamadas
 * estritamente somente-leitura via JNA.
 *
 * Não modifica o Windows Explorer, não altera hierarquia de janelas do sistema e não
 * utiliza injeção de DLL.
 */
public final class WindowsTaskbarLocator {
    private static final String PERSONALIZE_KEY =
            "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";

    // QUNS_BUSY (2) é intencionalmente ignorado porque dispara falsos positivos no Windows 10/11
    // quando há overlays, ferramentas de captura ou Assistente de Foco ativos.
    private static final int QUNS_RUNNING_D3D_FULL_SCREEN = 3;
    private static final int QUNS_PRESENTATION_MODE = 4;
    private static final int ABS_AUTOHIDE = 0x1;

    private static final HWND HWND_TOPMOST = new HWND(Pointer.createConstant(-1L));
    private static final int WS_EX_TOOLWINDOW = 0x00000080;
    private static final int WS_EX_APPWINDOW = 0x00040000;
    private static final int WS_EX_NOACTIVATE = 0x08000000;
    private static final int SWP_FRAMECHANGED = 0x0020;

    private static final int EVENT_SYSTEM_FOREGROUND = 0x0003;
    private static final int EVENT_SYSTEM_SWITCHEND = 0x0015;
    private static final int EVENT_SYSTEM_MINIMIZESTART = 0x0016;
    private static final int EVENT_SYSTEM_MINIMIZEEND = 0x0017;
    private static final int WINEVENT_OUTOFCONTEXT = 0x0000;
    private static final int WINEVENT_SKIPOWNPROCESS = 0x0002;

    private static final Set<String> SHELL_WINDOW_CLASSES = Set.of(
            "Shell_TrayWnd",
            "Shell_SecondaryTrayWnd",
            "Progman",
            "WorkerW",
            "NotifyIconOverflowWindow",
            "TopLevelWindowForOverflowXamlIsland",
            "MultitaskingViewFrame",
            "Windows.UI.Core.CoreWindow"
    );

    private interface Shell32Extra extends StdCallLibrary {
        Shell32Extra INSTANCE = Native.load("shell32", Shell32Extra.class);
        int SHQueryUserNotificationState(IntByReference pquns);
    }

    public record TaskbarAnchor(
            int rightEdgeX,
            int taskbarTopY,
            int taskbarHeight,
            int taskbarLeftX,
            boolean lightTaskbar,
            long taskbarHwndPeer,
            double scaleX,
            double scaleY
    ) {}

    private WindowsTaskbarLocator() {}

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public static boolean isWindowsTaskbarLight() {
        if (!isWindows()) {
            return false;
        }
        try {
            if (Advapi32Util.registryValueExists(
                    WinReg.HKEY_CURRENT_USER, PERSONALIZE_KEY, "SystemUsesLightTheme")) {
                int val = Advapi32Util.registryGetIntValue(
                        WinReg.HKEY_CURRENT_USER, PERSONALIZE_KEY, "SystemUsesLightTheme");
                return val != 0;
            }
        } catch (Throwable ignored) {
            // Assume tema escuro como padrão
        }
        return false;
    }

    public static int getTrayIconPixelSize() {
        if (GraphicsEnvironment.isHeadless()) {
            return 16;
        }
        try {
            GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice()
                    .getDefaultConfiguration();
            if (gc != null && gc.getDefaultTransform() != null && gc.getDefaultTransform().getScaleX() > 0) {
                return Math.max(16, (int) Math.round(16.0 * gc.getDefaultTransform().getScaleX()));
            }
        } catch (Throwable ignored) {
            // Fallback seguro em 16px
        }
        return 16;
    }

    /** Identidade do Explorer, independente de tela cheia, DPI e auto-hide. */
    public static long taskbarPeer() {
        if (!isWindows()) return 0;
        try {
            HWND taskbar = User32.INSTANCE.FindWindow("Shell_TrayWnd", null);
            return taskbar == null ? 0 : Pointer.nativeValue(taskbar.getPointer());
        } catch (RuntimeException | LinkageError unavailable) {
            return 0;
        }
    }

    /**
     * Aplica estilos estendidos Win32 documentados apenas na própria janela do widget
     * ({@code WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE}, removendo {@code WS_EX_APPWINDOW})
     * para garantir que nunca apareça no Alt+Tab nem roube foco ao ser clicada.
     */
    public static void applyWidgetWindowStyles(Window window) {
        if (!isWindows() || window == null || !window.isDisplayable()) {
            return;
        }
        try {
            Pointer ptr = Native.getComponentPointer(window);
            if (ptr == null) {
                return;
            }
            HWND hwnd = new HWND(ptr);
            int exStyle = User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE);
            int desired = (exStyle | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE) & ~WS_EX_APPWINDOW;
            if (desired != exStyle) {
                User32.INSTANCE.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, desired);
                User32.INSTANCE.SetWindowPos(
                        hwnd,
                        HWND_TOPMOST,
                        0, 0, 0, 0,
                        WinUser.SWP_NOMOVE | WinUser.SWP_NOSIZE | WinUser.SWP_NOACTIVATE | SWP_FRAMECHANGED
                );
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Mantém comportamento padrão do Swing caso JNA não esteja disponível
        }
    }

    /**
     * Reafirma a ordem Z da própria janela de widget apenas quando {@code Shell_TrayWnd}
     * passou à frente dela (ex.: após Win+D, Alt+Tab ou clique na barra de tarefas),
     * sem mover o widget, sem roubar foco e sem modificar janelas do Explorer.
     */
    public static boolean keepAboveTaskbarIfCovered(Window widget, long taskbarPeer) {
        if (!isWindows() || widget == null || !widget.isDisplayable() || !widget.isShowing()) {
            return false;
        }
        if (!taskbarAbove(widget, taskbarPeer)) {
            return false;
        }
        try {
            Pointer ptr = Native.getComponentPointer(widget);
            if (ptr == null) {
                return false;
            }
            User32.INSTANCE.SetWindowPos(
                    new HWND(ptr),
                    HWND_TOPMOST,
                    0, 0, 0, 0,
                    WinUser.SWP_NOMOVE | WinUser.SWP_NOSIZE | WinUser.SWP_NOACTIVATE
            );
            return true;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /**
     * Registra um hook Win32 documentado ({@code SetWinEventHook}, {@code WINEVENT_OUTOFCONTEXT})
     * para reagir imediatamente a mudanças de foreground, Alt+Tab e Show Desktop (Win+D),
     * sem depender apenas de polling lento. Retorna um {@link AutoCloseable} para desinstalar o hook.
     */
    public static AutoCloseable registerShellFocusHook(Runnable onShellTransition) {
        if (!isWindows() || onShellTransition == null) {
            return () -> {};
        }
        try {
            WinUser.WinEventProc callback = (hWinEventHook, event, hwnd, idObject, idChild, dwEventThread, dwmsEventTime) -> {
                int ev = event != null ? event.intValue() : 0;
                if (ev == EVENT_SYSTEM_FOREGROUND
                        || ev == EVENT_SYSTEM_SWITCHEND
                        || ev == EVENT_SYSTEM_MINIMIZESTART
                        || ev == EVENT_SYSTEM_MINIMIZEEND) {
                    onShellTransition.run();
                }
            };
            HANDLE hookFg = User32.INSTANCE.SetWinEventHook(
                    EVENT_SYSTEM_FOREGROUND,
                    EVENT_SYSTEM_FOREGROUND,
                    null,
                    callback,
                    0,
                    0,
                    WINEVENT_OUTOFCONTEXT | WINEVENT_SKIPOWNPROCESS
            );
            HANDLE hookSwitchMin = User32.INSTANCE.SetWinEventHook(
                    EVENT_SYSTEM_SWITCHEND,
                    EVENT_SYSTEM_MINIMIZEEND,
                    null,
                    callback,
                    0,
                    0,
                    WINEVENT_OUTOFCONTEXT | WINEVENT_SKIPOWNPROCESS
            );
            return () -> {
                // Mantém referência forte ao callback até o unhook
                if (hookFg != null) {
                    User32.INSTANCE.UnhookWinEvent(hookFg);
                }
                if (hookSwitchMin != null) {
                    User32.INSTANCE.UnhookWinEvent(hookSwitchMin);
                }
                callback.hashCode();
            };
        } catch (RuntimeException | LinkageError ignored) {
            return () -> {};
        }
    }

    /** EnumWindows visits top-level windows in Z order. Stop at the first of the pair. */
    static boolean taskbarAbove(java.awt.Window widget, long taskbarPeer) {
        if (!widget.isShowing() || taskbarPeer == 0) return false;
        try {
            Pointer ptr = Native.getComponentPointer(widget);
            if (ptr == null) return false;
            long widgetPeer = Pointer.nativeValue(ptr);
            boolean[] covered = {false};
            User32.INSTANCE.EnumWindows((window, ignored) -> {
                long peer = Pointer.nativeValue(window.getPointer());
                if (peer == taskbarPeer) covered[0] = true;
                return peer != taskbarPeer && peer != widgetPeer;
            }, null);
            return covered[0];
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    static boolean useShellIndicators(String foregroundClass) {
        // Não transfere automaticamente o widget personalizado para o SystemTray
        // durante interações normais com o Shell (Win+D, Alt+Tab, bandeja).
        return false;
    }

    public static boolean isFullscreenOrBusy() {
        if (!isWindows()) {
            return false;
        }
        try {
            IntByReference state = new IntByReference();
            int hr = Shell32Extra.INSTANCE.SHQueryUserNotificationState(state);
            if (hr == 0) {
                int val = state.getValue();
                return val == QUNS_RUNNING_D3D_FULL_SCREEN
                        || val == QUNS_PRESENTATION_MODE;
            }
        } catch (Throwable ignored) {
            // Não impede exibição caso a API não responda
        }
        return false;
    }

    private static boolean shouldYieldToForeground(HWND taskbar, Rectangle monitorPhysBounds) {
        if (monitorPhysBounds == null) {
            return false;
        }
        try {
            HWND fg = User32.INSTANCE.GetForegroundWindow();
            if (fg == null || fg.equals(taskbar) || !User32.INSTANCE.IsWindowVisible(fg)) {
                return false;
            }
            char[] buf = new char[256];
            int len = User32.INSTANCE.GetClassName(fg, buf, buf.length);
            if (len > 0) {
                String cls = new String(buf, 0, len);
                if (useShellIndicators(cls)) return true;
                if ("MultitaskingViewFrame".equals(cls) || SHELL_WINDOW_CLASSES.contains(cls)) {
                    return false;
                }
            }
            RECT fgRect = new RECT();
            if (!User32.INSTANCE.GetWindowRect(fg, fgRect)) {
                return false;
            }
            return fgRect.left <= monitorPhysBounds.x
                    && fgRect.top <= monitorPhysBounds.y
                    && fgRect.right >= monitorPhysBounds.x + monitorPhysBounds.width
                    && fgRect.bottom >= monitorPhysBounds.y + monitorPhysBounds.height;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static Optional<TaskbarAnchor> findTaskbarAnchor() {
        if (!isWindows() || GraphicsEnvironment.isHeadless()) {
            return Optional.empty();
        }
        try {
            if (isFullscreenOrBusy()) {
                return Optional.empty();
            }

            HWND taskbar = User32.INSTANCE.FindWindow("Shell_TrayWnd", null);
            if (taskbar == null || !User32.INSTANCE.IsWindowVisible(taskbar)) {
                return Optional.empty();
            }
            HWND trayNotify = User32.INSTANCE.FindWindowEx(taskbar, null, "TrayNotifyWnd", null);
            if (trayNotify == null) {
                return Optional.empty();
            }

            RECT tbRect = new RECT();
            RECT trayRect = new RECT();
            if (!User32.INSTANCE.GetWindowRect(taskbar, tbRect)
                    || !User32.INSTANCE.GetWindowRect(trayNotify, trayRect)) {
                return Optional.empty();
            }

            int tbW = tbRect.right - tbRect.left;
            int tbH = tbRect.bottom - tbRect.top;
            // Ignora barra de tarefas vertical ou oculta
            if (tbW <= tbH || tbH <= 16) {
                return Optional.empty();
            }

            // Verifica se a barra está oculta em auto-hide
            ShellAPI.APPBARDATA abd = new ShellAPI.APPBARDATA();
            abd.cbSize = new DWORD(abd.size());
            UINT_PTR stateRes = Shell32.INSTANCE.SHAppBarMessage(new DWORD(ShellAPI.ABM_GETSTATE), abd);
            boolean autoHide = (stateRes != null && (stateRes.longValue() & ABS_AUTOHIDE) != 0);

            GraphicsConfiguration gc = findMatchingScreenConfig(tbRect.left, tbRect.top);
            double scaleX = 1.0;
            double scaleY = 1.0;
            if (gc != null) {
                AffineTransform tx = gc.getDefaultTransform();
                if (tx != null && tx.getScaleX() > 0 && tx.getScaleY() > 0) {
                    scaleX = tx.getScaleX();
                    scaleY = tx.getScaleY();
                }
                Rectangle physicalBounds = computePhysicalBounds(gc);
                if (shouldYieldToForeground(taskbar, physicalBounds)) {
                    return Optional.empty();
                }
                if (autoHide) {
                    int physicalScreenBottom = physicalBounds.y + physicalBounds.height;
                    if (tbRect.top >= physicalScreenBottom - 4) {
                        return Optional.empty();
                    }
                }
            }

            int rightEdgePhys = trayRect.left;

            // Windows 10: Notícias e Interesses fica imediatamente à esquerda da bandeja
            HWND feeds = User32.INSTANCE.FindWindowEx(taskbar, null, "ShellFeedsTaskbarViewWindow", null);
            if (feeds != null && User32.INSTANCE.IsWindowVisible(feeds)) {
                RECT feedsRect = new RECT();
                if (User32.INSTANCE.GetWindowRect(feeds, feedsRect)
                        && feedsRect.right > feedsRect.left
                        && feedsRect.left < rightEdgePhys
                        && Math.abs(feedsRect.right - trayRect.left) <= 48) {
                    rightEdgePhys = feedsRect.left;
                }
            }

            int logicalRightEdge = (int) Math.round(rightEdgePhys / scaleX);
            int logicalTop = (int) Math.round(tbRect.top / scaleY);
            int logicalHeight = (int) Math.round(tbH / scaleY);
            int logicalLeft = (int) Math.round(tbRect.left / scaleX);
            long peer = Pointer.nativeValue(taskbar.getPointer());

            return Optional.of(new TaskbarAnchor(
                    logicalRightEdge,
                    logicalTop,
                    logicalHeight,
                    logicalLeft,
                    isWindowsTaskbarLight(),
                    peer,
                    scaleX,
                    scaleY
            ));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    private static GraphicsConfiguration findMatchingScreenConfig(int pxX, int pxY) {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (GraphicsDevice gd : ge.getScreenDevices()) {
            GraphicsConfiguration gc = gd.getDefaultConfiguration();
            if (gc != null) {
                Rectangle physicalBounds = computePhysicalBounds(gc);
                if (physicalBounds.contains(pxX + 4, pxY + 4)) {
                    return gc;
                }
            }
        }
        return ge.getDefaultScreenDevice().getDefaultConfiguration();
    }

    private static Rectangle computePhysicalBounds(GraphicsConfiguration gc) {
        Rectangle b = gc.getBounds();
        AffineTransform tx = gc.getDefaultTransform();
        double sx = tx != null && tx.getScaleX() > 0 ? tx.getScaleX() : 1.0;
        double sy = tx != null && tx.getScaleY() > 0 ? tx.getScaleY() : 1.0;
        return new Rectangle(
                (int) Math.round(b.x * sx),
                (int) Math.round(b.y * sy),
                (int) Math.round(b.width * sx),
                (int) Math.round(b.height * sy)
        );
    }
}
