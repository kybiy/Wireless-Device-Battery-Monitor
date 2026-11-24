package br.com.wirelessbattery.ui;

import br.com.wirelessbattery.config.AppSettings;
import br.com.wirelessbattery.diagnostics.AppLogger;
import br.com.wirelessbattery.model.DeviceReading;
import com.sun.jna.Callback;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.BaseTSD.LONG_PTR;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.ATOM;
import com.sun.jna.platform.win32.WinDef.BOOL;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HBITMAP;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinDef.HICON;
import com.sun.jna.platform.win32.WinDef.HINSTANCE;
import com.sun.jna.platform.win32.WinDef.HMENU;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinDef.UINT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinGDI.BITMAPINFO;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.platform.win32.WinUser.MSG;
import com.sun.jna.platform.win32.WinUser.WNDCLASSEX;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;

/**
 * Backend nativo Win32 para os indicadores horizontais de bateria na barra de tarefas do Windows.
 *
 * <p>Arquitetura baseada na referência técnica {@code src/widget.cpp} de
 * {@code joaoarebalo/Wireless-Device-Battery-Monitor-Fallen} (GPL-3.0):
 * cria uma janela nativa {@code WS_CHILD | WS_CLIPSIBLINGS} com estilos estendidos
 * {@code WS_EX_LAYERED | WS_EX_NOACTIVATE}, anexada diretamente como filha de {@code Shell_TrayWnd},
 * e renderizada com alpha por pixel via {@code UpdateLayeredWindow} ({@code ULW_ALPHA}).
 *
 * <p>Por ser uma janela filha ({@code WS_CHILD}) da própria barra de tarefas, ela acompanha a
 * hierarquia da {@code Shell_TrayWnd} e não desaparece durante {@code Win+D} (Show Desktop)
 * ou {@code Alt+Tab}, não aparece no {@code Alt+Tab}, não rouba foco ({@code MA_NOACTIVATE})
 * e não sobrepõe aplicativos em tela cheia.
 *
 * <p>Não utiliza injeção de DLL, não altera memória do Explorer e não modifica a geometria de
 * componentes internos da barra de tarefas ({@code ReBarWindow32}).
 */
public final class NativeTaskbarWidget {
    private static final String WINDOW_CLASS_NAME = "WirelessBatteryNativeWidget";

    // Estilos de janela Win32
    static final int WS_CHILD = 0x40000000;
    static final int WS_CLIPSIBLINGS = 0x04000000;
    static final int WS_EX_LAYERED = 0x00080000;
    static final int WS_EX_NOACTIVATE = 0x08000000;
    static final int WS_EX_LAYOUTRTL = 0x00400000;

    // Mensagens Win32
    private static final int WM_DESTROY = 0x0002;
    private static final int WM_PAINT = 0x000F;
    private static final int WM_SETCURSOR = 0x0020;
    private static final int WM_MOUSEACTIVATE = 0x0021;
    private static final int WM_MOUSEMOVE = 0x0200;
    private static final int WM_LBUTTONUP = 0x0202;
    private static final int WM_RBUTTONUP = 0x0205;
    private static final int WM_MOUSELEAVE = 0x02A3;
    private static final int WM_NULL = 0x0000;
    private static final int WM_APP_SYNC = 0x8001;
    private static final int WM_APP_SHUTDOWN = 0x8002;

    private static final int MA_NOACTIVATE = 3;
    private static final int IDC_HAND = 32649;
    private static final int TME_LEAVE = 0x00000002;
    private static final int GW_HWNDPREV = 3;
    private static final int GA_PARENT = 1;

    private static final int SW_HIDE = 0;
    private static final int SW_SHOWNOACTIVATE = 4;
    private static final int SWP_NOSIZE = 0x0001;
    private static final int SWP_NOMOVE = 0x0002;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final int SWP_SHOWWINDOW = 0x0040;
    private static final HWND HWND_TOP = new HWND(Pointer.createConstant(0L));

    private static final byte AC_SRC_OVER = 0x00;
    private static final byte AC_SRC_ALPHA = 0x01;
    private static final int ULW_ALPHA = 0x00000002;

    // Menu de contexto nativo
    private static final int MF_STRING = 0x00000000;
    private static final int MF_SEPARATOR = 0x00000800;
    private static final int TPM_RIGHTBUTTON = 0x0002;
    private static final int TPM_BOTTOMALIGN = 0x0020;
    private static final int TPM_NONOTIFY = 0x0080;
    private static final int TPM_RETURNCMD = 0x0100;
    private static final int CMD_OPEN = 1;
    private static final int CMD_REFRESH = 2;
    private static final int CMD_SETTINGS = 3;
    private static final int CMD_EXIT = 4;

    private static final int BOLT_BOX = 11;
    private static final int BOLT_VISIBLE_END = 9;
    private static final int GAP_AFTER_BOLT = 9;
    private static final Font SIM_BADGE_FONT = new Font("Segoe UI", Font.BOLD, 9);
    private static volatile int cachedTextFontSize = -1;
    private static volatile Font cachedTextFont;

    private static Font getTextFont(int size) {
        Font f = cachedTextFont;
        if (f == null || cachedTextFontSize != size) {
            f = new Font("Segoe UI", Font.BOLD, size);
            cachedTextFont = f;
            cachedTextFontSize = size;
        }
        return f;
    }

    public interface WinProcCallback extends Callback {
        LRESULT callback(HWND hWnd, int uMsg, WPARAM wParam, LPARAM lParam);
    }

    @Structure.FieldOrder({"cbSize", "dwFlags", "hwndTrack", "dwHoverTime"})
    public static class TRACKMOUSEEVENT extends Structure {
        public int cbSize = size();
        public int dwFlags;
        public HWND hwndTrack;
        public int dwHoverTime;
    }

    @Structure.FieldOrder({"cx", "cy"})
    public static class SIZE extends Structure {
        public int cx;
        public int cy;

        public SIZE() {}

        public SIZE(int cx, int cy) {
            this.cx = cx;
            this.cy = cy;
        }
    }

    @Structure.FieldOrder({"BlendOp", "BlendFlags", "SourceConstantAlpha", "AlphaFormat"})
    public static class BLENDFUNCTION extends Structure {
        public byte BlendOp;
        public byte BlendFlags;
        public byte SourceConstantAlpha;
        public byte AlphaFormat;
    }

    private interface User32Ex extends StdCallLibrary {
        User32Ex INSTANCE = Native.load("user32", User32Ex.class, W32APIOptions.DEFAULT_OPTIONS);

        HWND GetParent(HWND hWnd);
        HWND GetAncestor(HWND hWnd, int gaFlags);
        HWND GetWindow(HWND hWnd, int uCmd);
        int GetDpiForWindow(HWND hWnd);
        boolean ScreenToClient(HWND hWnd, POINT lpPoint);
        boolean GetClientRect(HWND hWnd, RECT lpRect);
        boolean TrackMouseEvent(TRACKMOUSEEVENT lpEventTrack);
        HANDLE LoadCursorW(HINSTANCE hInstance, Pointer lpCursorName);
        HANDLE SetCursor(HANDLE hCursor);
        boolean UpdateLayeredWindow(
                HWND hWnd,
                HDC hdcDst,
                POINT pptDst,
                SIZE psize,
                HDC hdcSrc,
                POINT pptSrc,
                int crKey,
                BLENDFUNCTION pblend,
                int dwFlags
        );
        boolean PostThreadMessageW(int idThread, int msg, WPARAM wParam, LPARAM lParam);
        HMENU CreatePopupMenu();
        boolean AppendMenuW(HMENU hMenu, int uFlags, LONG_PTR uIDNewItem, WString lpNewItem);
        int TrackPopupMenu(HMENU hMenu, int uFlags, int x, int y, int nReserved, HWND hWnd, Pointer prcRect);
        boolean DestroyMenu(HMENU hMenu);
        boolean GetCursorPos(POINT lpPoint);
        boolean ValidateRect(HWND hWnd, Pointer lpRect);
        int GetGuiResources(HANDLE hProcess, int uiFlags);
    }

    static int getProcessGuiResources(int uiFlags) {
        return User32Ex.INSTANCE.GetGuiResources(Kernel32.INSTANCE.GetCurrentProcess(), uiFlags);
    }

    private final AppSettings settings;
    private final AppLogger logger;
    private final Runnable onOpenWindow;
    private final Runnable onRefresh;
    private final Runnable onOpenSettings;
    private final Runnable onExit;

    // Referência forte permanente ao callback nativo para impedir coleta pelo GC
    private final WinProcCallback wndProcRef = this::wndProc;

    private final Object stateLock = new Object();
    private List<DeviceReading> currentItems = List.of();
    private volatile boolean wantEnabled;
    private volatile boolean activeAndVisible;
    private volatile boolean permanentFallbackToSwing;
    private volatile String fallbackReason = "nenhum";
    private volatile String lastRenderStatus = "PENDENTE";
    private volatile long attachedTaskbarPeer;
    private volatile long childHwndPeer;

    private Thread win32Thread;
    private volatile int win32ThreadId;
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);

    // Estado exclusivo da thread Win32
    private boolean classRegistered;
    private HWND hwndChild;
    private HWND hwndTaskbar;
    private HANDLE handCursor;
    private boolean hovered;
    private boolean lightTaskbar;
    private int lastDpi = 96;
    private int lastClientX = Integer.MIN_VALUE;
    private int lastClientY = Integer.MIN_VALUE;
    private int lastPhysW = -1;
    private int lastPhysH = -1;
    private String lastPaintSignature = "";
    private boolean loggedFirstRender;
    private boolean loggedNoTaskbarOnce;
    private int consecutiveCreateFailures;
    private int consecutiveRenderFailures;

    public NativeTaskbarWidget(
            AppSettings settings,
            AppLogger logger,
            Runnable onOpenWindow,
            Runnable onRefresh,
            Runnable onOpenSettings,
            Runnable onExit
    ) {
        this.settings = settings;
        this.logger = logger;
        this.onOpenWindow = onOpenWindow;
        this.onRefresh = onRefresh;
        this.onOpenSettings = onOpenSettings;
        this.onExit = onExit;
    }

    public boolean isPermanentFallbackToSwing() {
        return permanentFallbackToSwing;
    }

    public String getFallbackReason() {
        return fallbackReason;
    }

    public String getLastRenderStatus() {
        return lastRenderStatus;
    }

    public boolean isActiveAndVisible() {
        return activeAndVisible && childHwndPeer != 0L;
    }

    public long getChildHwndPeer() {
        return childHwndPeer;
    }

    public long getAttachedTaskbarPeer() {
        return attachedTaskbarPeer;
    }

    public void setEnabled(boolean enabled) {
        this.wantEnabled = enabled;
        if (enabled && !permanentFallbackToSwing) {
            ensureWin32ThreadStarted();
        }
        requestWin32Sync();
    }

    public void updateReadings(List<DeviceReading> items) {
        synchronized (stateLock) {
            this.currentItems = List.copyOf(items);
        }
        if (wantEnabled && !permanentFallbackToSwing) {
            ensureWin32ThreadStarted();
            requestWin32Sync();
        }
    }

    public void refreshLayout() {
        synchronized (stateLock) {
            this.lastPaintSignature = "";
        }
        if (wantEnabled && !permanentFallbackToSwing) {
            requestWin32Sync();
        }
    }

    /**
     * Solicita verificação/reposicionamento não-bloqueante na thread Win32.
     *
     * @return {@code true} se a janela nativa está atualmente criada e visível na barra de tarefas.
     */
    public boolean requestReposition() {
        if (!wantEnabled || permanentFallbackToSwing) {
            return false;
        }
        ensureWin32ThreadStarted();
        requestWin32Sync();
        return isActiveAndVisible();
    }

    public void shutdown() {
        wantEnabled = false;
        shutdownRequested.set(true);
        int tid = win32ThreadId;
        if (tid != 0) {
            User32Ex.INSTANCE.PostThreadMessageW(tid, WM_APP_SHUTDOWN, new WPARAM(0), new LPARAM(0));
        }
        Thread t = win32Thread;
        if (t != null && t.isAlive() && Thread.currentThread() != t) {
            try {
                t.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private synchronized void ensureWin32ThreadStarted() {
        if (shutdownRequested.get() || permanentFallbackToSwing) {
            return;
        }
        if (win32Thread != null && win32Thread.isAlive()) {
            return;
        }
        CountDownLatch readyLatch = new CountDownLatch(1);
        win32Thread = new Thread(() -> runWin32Loop(readyLatch), "NativeTaskbarWidget-Win32");
        win32Thread.setDaemon(true);
        win32Thread.start();
        try {
            readyLatch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void requestWin32Sync() {
        int tid = win32ThreadId;
        if (tid != 0) {
            User32Ex.INSTANCE.PostThreadMessageW(tid, WM_APP_SYNC, new WPARAM(0), new LPARAM(0));
        }
    }

    private void runWin32Loop(CountDownLatch readyLatch) {
        this.win32ThreadId = Kernel32.INSTANCE.GetCurrentThreadId();
        // Força criação da fila de mensagens da thread antes de liberar o latch
        MSG initMsg = new MSG();
        User32.INSTANCE.PeekMessage(initMsg, null, 0, 0, 0);
        logger.info("[WIDGET-NATIVE] Thread Win32 iniciada (tid=" + win32ThreadId + ").");

        try {
            handCursor = User32Ex.INSTANCE.LoadCursorW(null, Pointer.createConstant(IDC_HAND));
            syncNativeStateOnThread();
        } catch (Throwable t) {
            fallbackReason = "Exceção na inicialização da thread Win32: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            logger.error("[WIDGET-NATIVE] Erro crítico na inicialização da thread Win32 — ativando fallback Swing (" + fallbackReason + ").", t);
            permanentFallbackToSwing = true;
            activeAndVisible = false;
        } finally {
            readyLatch.countDown();
        }

        MSG msg = new MSG();
        boolean syncPending = false;
        while (!shutdownRequested.get()) {
            int res = User32.INSTANCE.GetMessage(msg, null, 0, 0);
            if (res <= 0) {
                break;
            }
            if (msg.message == WM_APP_SHUTDOWN) {
                break;
            }
            if (msg.message == WM_APP_SYNC) {
                syncPending = true;
                // Drena mensagens WM_APP_SYNC acumuladas para coalescer atualizações
                while (User32.INSTANCE.PeekMessage(msg, null, WM_APP_SYNC, WM_APP_SYNC, 1)) {
                    // Remove da fila
                }
            } else {
                User32.INSTANCE.TranslateMessage(msg);
                User32.INSTANCE.DispatchMessage(msg);
            }

            if (syncPending) {
                syncPending = false;
                try {
                    syncNativeStateOnThread();
                } catch (Throwable t) {
                    logger.error("[WIDGET-NATIVE] Exceção ao sincronizar estado da janela nativa", t);
                }
            }
        }

        destroyChildWindowOnThread("encerramento da thread Win32");
        win32ThreadId = 0;
    }

    private void syncNativeStateOnThread() {
        if (!wantEnabled || permanentFallbackToSwing) {
            if (hwndChild != null) {
                destroyChildWindowOnThread("widget desativado");
            }
            return;
        }

        if (WindowsTaskbarLocator.isFullscreenOrBusy()) {
            if (hwndChild != null && User32.INSTANCE.IsWindow(hwndChild)) {
                User32.INSTANCE.ShowWindow(hwndChild, SW_HIDE);
                activeAndVisible = false;
            }
            return;
        }

        HWND currentTaskbar = User32.INSTANCE.FindWindow("Shell_TrayWnd", null);
        if (currentTaskbar == null || !User32.INSTANCE.IsWindowVisible(currentTaskbar)) {
            if (hwndChild != null) {
                destroyChildWindowOnThread("Shell_TrayWnd deixou de estar disponível");
            }
            if (!loggedNoTaskbarOnce) {
                loggedNoTaskbarOnce = true;
                int err = Native.getLastError();
                logger.info("[WIDGET-NATIVE] Shell_TrayWnd não encontrado no desktop atual (lastError=" + err + ").");
            }
            activeAndVisible = false;
            return;
        }
        loggedNoTaskbarOnce = false;

        HWND trayNotify = User32.INSTANCE.FindWindowEx(currentTaskbar, null, "TrayNotifyWnd", null);
        if (trayNotify == null) {
            activeAndVisible = false;
            return;
        }

        RECT tbRect = new RECT();
        RECT trayRect = new RECT();
        if (!User32.INSTANCE.GetWindowRect(currentTaskbar, tbRect)
                || !User32.INSTANCE.GetWindowRect(trayNotify, trayRect)) {
            activeAndVisible = false;
            return;
        }

        int tbW = tbRect.right - tbRect.left;
        int tbH = tbRect.bottom - tbRect.top;
        if (tbW <= tbH || tbH <= 16) {
            if (hwndChild != null) {
                destroyChildWindowOnThread("barra de tarefas vertical ou recolhida");
            }
            activeAndVisible = false;
            return;
        }

        long currentTbPeer = Pointer.nativeValue(currentTaskbar.getPointer());
        boolean taskbarChanged = (hwndTaskbar == null)
                || (attachedTaskbarPeer != currentTbPeer)
                || !User32.INSTANCE.IsWindow(hwndTaskbar);
        boolean childInvalid = (hwndChild == null) || !User32.INSTANCE.IsWindow(hwndChild);

        if (taskbarChanged || childInvalid) {
            if (hwndChild != null) {
                destroyChildWindowOnThread(taskbarChanged
                        ? "Explorer / Shell_TrayWnd reiniciado (novo hwnd=0x" + Long.toHexString(currentTbPeer) + ")"
                        : "handle da janela filha tornou-se inválido");
            }
            if (!createChildWindowOnThread(currentTaskbar, trayNotify, tbRect)) {
                activeAndVisible = false;
                return;
            }
        }

        repositionAndRenderOnThread(trayNotify, tbRect, trayRect);
    }

    private boolean ensureWindowClassRegisteredOnThread(HINSTANCE hInst) {
        if (classRegistered) {
            return true;
        }
        WNDCLASSEX wc = new WNDCLASSEX();
        wc.cbSize = wc.size();
        wc.lpfnWndProc = wndProcRef;
        wc.hInstance = hInst;
        wc.lpszClassName = WINDOW_CLASS_NAME;
        ATOM atom = User32.INSTANCE.RegisterClassEx(wc);
        int err = Native.getLastError();
        // ERROR_CLASS_ALREADY_EXISTS = 1410
        if ((atom == null || atom.intValue() == 0) && err != 1410) {
            logger.warn("[WIDGET-NATIVE] RegisterClassExW falhou (lastError=" + err + ").");
            return false;
        }
        classRegistered = true;
        logger.info("[WIDGET-NATIVE] Classe Win32 '" + WINDOW_CLASS_NAME + "' registrada com sucesso.");
        return true;
    }

    private boolean createChildWindowOnThread(HWND taskbar, HWND trayNotify, RECT tbRect) {
        HINSTANCE hInst = Kernel32.INSTANCE.GetModuleHandle(null);
        if (!ensureWindowClassRegisteredOnThread(hInst)) {
            int err = Native.getLastError();
            fallbackReason = "RegisterClassExW falhou (lastError=" + err + ")";
            permanentFallbackToSwing = true;
            logger.warn("[WIDGET-NATIVE] Fallback permanente para widget Swing ativado: " + fallbackReason);
            return false;
        }

        int dpi = resolveTaskbarDpi(taskbar);
        long tbPeer = Pointer.nativeValue(taskbar.getPointer());
        long trayPeer = Pointer.nativeValue(trayNotify.getPointer());
        logger.info(String.format(
                "[WIDGET-NATIVE] Shell_TrayWnd encontrado: hwnd=0x%X (TrayNotifyWnd=0x%X, DPI=%d, rect=[%d,%d,%d,%d]).",
                tbPeer, trayPeer, dpi, tbRect.left, tbRect.top, tbRect.right, tbRect.bottom
        ));

        int style = WS_CHILD | WS_CLIPSIBLINGS;
        int exStyle = WS_EX_LAYERED | WS_EX_NOACTIVATE;

        HWND created = User32.INSTANCE.CreateWindowEx(
                exStyle,
                WINDOW_CLASS_NAME,
                "",
                style,
                0, 0, 1, 1,
                taskbar,
                null,
                hInst,
                null
        );
        int createErr = Native.getLastError();
        if (created == null || !User32.INSTANCE.IsWindow(created)) {
            consecutiveCreateFailures++;
            lastRenderStatus = "FALHA_CREATE_WINDOW (lastError=" + createErr + ")";
            logger.warn(String.format(
                    "[WIDGET-NATIVE] CreateWindowExW(WS_CHILD, parent=0x%X) falhou (tentativa %d, lastError=%d).",
                    tbPeer, consecutiveCreateFailures, createErr
            ));
            if (consecutiveCreateFailures >= 3) {
                fallbackReason = "CreateWindowExW(WS_CHILD) falhou 3 vezes consecutivas (lastError=" + createErr + ")";
                permanentFallbackToSwing = true;
                logger.warn("[WIDGET-NATIVE] Fallback permanente para widget Swing ativado: " + fallbackReason);
            }
            return false;
        }

        consecutiveCreateFailures = 0;
        this.hwndChild = created;
        this.hwndTaskbar = taskbar;
        this.attachedTaskbarPeer = tbPeer;
        this.childHwndPeer = Pointer.nativeValue(created.getPointer());
        this.lastDpi = dpi;
        this.lastClientX = Integer.MIN_VALUE;
        this.lastClientY = Integer.MIN_VALUE;
        this.lastPhysW = -1;
        this.lastPhysH = -1;
        this.lastPaintSignature = "";
        this.loggedFirstRender = false;

        HWND parentCheck = User32Ex.INSTANCE.GetAncestor(created, GA_PARENT);
        long parentPeer = parentCheck != null ? Pointer.nativeValue(parentCheck.getPointer()) : 0L;
        int actualStyle = User32.INSTANCE.GetWindowLong(created, WinUser.GWL_STYLE);
        int actualExStyle = User32.INSTANCE.GetWindowLong(created, WinUser.GWL_EXSTYLE);

        logger.info(String.format(
                "[WIDGET-NATIVE] Janela filha WS_CHILD criada com sucesso: hwnd=0x%X, parent=0x%X (parentMatch=%s, style=0x%08X, exStyle=0x%08X).",
                childHwndPeer, parentPeer, (parentPeer == tbPeer), actualStyle, actualExStyle
        ));
        return true;
    }

    private void destroyChildWindowOnThread(String reason) {
        if (hwndChild != null) {
            long oldChild = childHwndPeer;
            long oldTb = attachedTaskbarPeer;
            try {
                if (User32.INSTANCE.IsWindow(hwndChild)) {
                    User32.INSTANCE.DestroyWindow(hwndChild);
                }
            } catch (Throwable ignored) {
                // ignora
            }
            hwndChild = null;
            hwndTaskbar = null;
            childHwndPeer = 0L;
            attachedTaskbarPeer = 0L;
            activeAndVisible = false;
            hovered = false;
            lastPaintSignature = "";
            logger.info(String.format(
                    "[WIDGET-NATIVE] Janela filha destruída (hwnd=0x%X, oldTaskbar=0x%X, motivo=%s).",
                    oldChild, oldTb, reason
            ));
        }
    }

    private void repositionAndRenderOnThread(HWND trayNotify, RECT tbRect, RECT trayRect) {
        if (hwndChild == null || hwndTaskbar == null) {
            return;
        }
        int dpi = resolveTaskbarDpi(hwndTaskbar);
        double scale = Math.max(1.0, dpi / 96.0);
        boolean currentLight = WindowsTaskbarLocator.isWindowsTaskbarLight();

        List<DeviceReading> itemsSnapshot;
        synchronized (stateLock) {
            itemsSnapshot = this.currentItems;
        }

        RECT clientBar = new RECT();
        if (!User32Ex.INSTANCE.GetClientRect(hwndTaskbar, clientBar)) {
            clientBar.right = tbRect.right - tbRect.left;
            clientBar.bottom = tbRect.bottom - tbRect.top;
        }
        int barClientH = Math.max(24, clientBar.bottom - clientBar.top);

        Dimension logicalPref = computeLogicalSize(itemsSnapshot, settings);
        int logicalH = Math.min(Math.max(24, (int) Math.round(barClientH / scale) - 10),
                Math.max(26, settings.getTaskbarIconSize() + 14));
        int logicalW = logicalPref.width;

        int physW = Math.max(62, (int) Math.round(logicalW * scale));
        int physH = Math.max(24, Math.min(barClientH - 4, (int) Math.round(logicalH * scale)));

        String sig = buildPaintSignature(itemsSnapshot, currentLight, hovered, dpi, physW, physH);
        if (!sig.equals(lastPaintSignature)) {
            this.lightTaskbar = currentLight;
            this.lastDpi = dpi;
            if (!paintLayeredBufferOnThread(itemsSnapshot, currentLight, hovered, scale, logicalW, logicalH, physW, physH)) {
                return;
            }
            this.lastPaintSignature = sig;
        }

        int tbExStyle = User32.INSTANCE.GetWindowLong(hwndTaskbar, WinUser.GWL_EXSTYLE);
        boolean rtl = (tbExStyle & WS_EX_LAYOUTRTL) != 0;
        int edgeScreenX = rtl ? trayRect.right : trayRect.left;

        // Windows 10: Notícias e Interesses fica em ShellFeedsTaskbarViewWindow imediatamente à esquerda da bandeja
        HWND feeds = User32.INSTANCE.FindWindowEx(hwndTaskbar, null, "ShellFeedsTaskbarViewWindow", null);
        if (!rtl && feeds != null && User32.INSTANCE.IsWindowVisible(feeds)) {
            RECT feedsRect = new RECT();
            if (User32.INSTANCE.GetWindowRect(feeds, feedsRect)
                    && feedsRect.right > feedsRect.left
                    && feedsRect.left < edgeScreenX
                    && Math.abs(feedsRect.right - trayRect.left) <= (int) Math.round(48 * scale)) {
                edgeScreenX = feedsRect.left;
            }
        }

        POINT pt = new POINT(edgeScreenX, tbRect.top);
        User32Ex.INSTANCE.ScreenToClient(hwndTaskbar, pt);

        int offsetPhys = (int) Math.round(settings.getTaskbarOffsetX() * scale);
        int clientX = Math.max(12, pt.x - physW - offsetPhys);
        int clientY = computeClientY(barClientH, physH);

        HWND prevSibling = User32Ex.INSTANCE.GetWindow(hwndChild, GW_HWNDPREV);
        boolean coveredBySibling = (prevSibling != null);
        boolean posChanged = (clientX != lastClientX)
                || (clientY != lastClientY)
                || (physW != lastPhysW)
                || (physH != lastPhysH)
                || !activeAndVisible;

        if (posChanged || coveredBySibling) {
            lastClientX = clientX;
            lastClientY = clientY;
            lastPhysW = physW;
            lastPhysH = physH;
            User32.INSTANCE.SetWindowPos(
                    hwndChild,
                    HWND_TOP,
                    clientX,
                    clientY,
                    physW,
                    physH,
                    SWP_NOACTIVATE | SWP_SHOWWINDOW
            );
            activeAndVisible = true;
        }
    }

    static int computeClientY(int taskbarClientHeight, int widgetPhysHeight) {
        return Math.max(0, (taskbarClientHeight - widgetPhysHeight) / 2);
    }

    private boolean paintLayeredBufferOnThread(
            List<DeviceReading> items,
            boolean useLight,
            boolean isHovered,
            double scale,
            int logicalW,
            int logicalH,
            int physW,
            int physH
    ) {
        BufferedImage img = renderWidgetImage(items, settings, useLight, isHovered, scale, logicalW, logicalH, physW, physH);
        int[] argb = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        int[] premulBgra = toPremultipliedBgraWithHitTestAlpha(argb);

        HDC screenDc = User32.INSTANCE.GetDC(null);
        if (screenDc == null) {
            logger.warn("[WIDGET-NATIVE] GetDC(null) retornou null (lastError=" + Native.getLastError() + ").");
            return false;
        }
        HDC memDc = GDI32.INSTANCE.CreateCompatibleDC(screenDc);
        HBITMAP hBmp = null;
        HANDLE oldObj = null;
        try {
            if (memDc == null) {
                logger.warn("[WIDGET-NATIVE] CreateCompatibleDC falhou (lastError=" + Native.getLastError() + ").");
                return false;
            }
            BITMAPINFO bmi = new BITMAPINFO();
            bmi.bmiHeader.biSize = bmi.bmiHeader.size();
            bmi.bmiHeader.biWidth = physW;
            bmi.bmiHeader.biHeight = -physH; // top-down DIB
            bmi.bmiHeader.biPlanes = 1;
            bmi.bmiHeader.biBitCount = 32;
            bmi.bmiHeader.biCompression = WinGDI.BI_RGB;

            PointerByReference ppvBits = new PointerByReference();
            hBmp = GDI32.INSTANCE.CreateDIBSection(memDc, bmi, WinGDI.DIB_RGB_COLORS, ppvBits, null, 0);
            if (hBmp == null || ppvBits.getValue() == null) {
                logger.warn("[WIDGET-NATIVE] CreateDIBSection falhou (lastError=" + Native.getLastError() + ").");
                return false;
            }
            ppvBits.getValue().write(0, premulBgra, 0, premulBgra.length);
            oldObj = GDI32.INSTANCE.SelectObject(memDc, hBmp);

            SIZE sz = new SIZE(physW, physH);
            POINT srcPt = new POINT(0, 0);
            BLENDFUNCTION blend = new BLENDFUNCTION();
            blend.BlendOp = AC_SRC_OVER;
            blend.BlendFlags = 0;
            blend.SourceConstantAlpha = (byte) 255;
            blend.AlphaFormat = AC_SRC_ALPHA;

            boolean ok = User32Ex.INSTANCE.UpdateLayeredWindow(
                    hwndChild,
                    screenDc,
                    null,
                    sz,
                    memDc,
                    srcPt,
                    0,
                    blend,
                    ULW_ALPHA
            );
            int err = Native.getLastError();
            if (!ok) {
                consecutiveRenderFailures++;
                lastRenderStatus = "FALHA_ULW (lastError=" + err + ")";
                logger.warn(String.format(
                        "[WIDGET-NATIVE] UpdateLayeredWindow falhou (hwnd=0x%X, size=%dx%d, tentativa=%d, lastError=%d).",
                        childHwndPeer, physW, physH, consecutiveRenderFailures, err
                ));
                if (consecutiveRenderFailures >= 3) {
                    fallbackReason = "UpdateLayeredWindow falhou 3 vezes consecutivas (lastError=" + err + ")";
                    permanentFallbackToSwing = true;
                    destroyChildWindowOnThread(fallbackReason);
                    logger.warn("[WIDGET-NATIVE] Fallback permanente para widget Swing ativado: " + fallbackReason);
                }
                return false;
            }
            consecutiveRenderFailures = 0;
            boolean hasSim = items.stream().anyMatch(DeviceReading::isSimulated);
            lastRenderStatus = String.format("OK (UpdateLayeredWindow %dx%d, items=%d, modo=%s, scale=%.2f)",
                    physW, physH, items.size(), hasSim ? "SIMULACAO" : "REAL", scale);
            if (!loggedFirstRender) {
                loggedFirstRender = true;
                logger.info(String.format(
                        "[WIDGET-NATIVE] Renderização executada com sucesso via UpdateLayeredWindow (hwnd=0x%X, parent=0x%X, size=%dx%d, items=%d, modo=%s, scale=%.2f).",
                        childHwndPeer, attachedTaskbarPeer, physW, physH, items.size(), hasSim ? "SIMULACAO" : "REAL", scale
                ));
            }
            return true;
        } finally {
            if (oldObj != null && memDc != null) {
                GDI32.INSTANCE.SelectObject(memDc, oldObj);
            }
            if (hBmp != null) {
                GDI32.INSTANCE.DeleteObject(hBmp);
            }
            if (memDc != null) {
                GDI32.INSTANCE.DeleteDC(memDc);
            }
            User32.INSTANCE.ReleaseDC(null, screenDc);
        }
    }

    /**
     * Converte pixels ARGB do Java2D para BGRA com alpha pré-multiplicado (formato exigido por
     * {@code UpdateLayeredWindow} com {@code AC_SRC_ALPHA}). Pixels totalmente transparentes
     * recebem alpha {@code 0x01} ({@code 0x01000000}) para que toda a faixa do widget receba
     * eventos de clique e hover sem alterar a transparência visual.
     */
    static int[] toPremultipliedBgraWithHitTestAlpha(int[] argb) {
        int[] out = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int px = argb[i];
            int a = (px >>> 24) & 0xFF;
            if (a == 0) {
                out[i] = 0x01000000;
                continue;
            }
            int r = (px >>> 16) & 0xFF;
            int g = (px >>> 8) & 0xFF;
            int b = px & 0xFF;
            int pr = (r * a + 127) / 255;
            int pg = (g * a + 127) / 255;
            int pb = (b * a + 127) / 255;
            out[i] = (a << 24) | (pr << 16) | (pg << 8) | pb;
        }
        return out;
    }

    /**
     * Renderiza o conteúdo visual do widget em um {@link BufferedImage} preservando exatamente
     * a mesma aparência, ícones Lucide SVG, tipografia, cores e espaçamento do widget Swing.
     */
    static BufferedImage renderWidgetImage(
            List<DeviceReading> items,
            AppSettings settings,
            boolean useLight,
            boolean hovered,
            double scale,
            int logicalW,
            int logicalH,
            int physW,
            int physH
    ) {
        BufferedImage img = new BufferedImage(Math.max(1, physW), Math.max(1, physH), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            g2.scale(scale, scale);

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
                g2.fillRoundRect(0, 0, logicalW - 1, logicalH - 1, 10, 10);
                g2.setColor(border);
                g2.drawRoundRect(0, 0, logicalW - 2, logicalH - 2, 10, 10);
            }

            int x = 10;
            int midY = logicalH / 2;
            int iconSize = settings.getTaskbarIconSize();
            int spacing = settings.getTaskbarSpacing();

            if (hasSim) {
                g2.setFont(SIM_BADGE_FONT);
                g2.setColor(new Color(0xD5, 0xAA, 0x59));
                g2.fillRoundRect(x, midY - 7, 24, 14, 6, 6);
                g2.setColor(new Color(0x10, 0x11, 0x14));
                g2.drawString("SIM", x + 3, midY + 3);
                x += 30;
            }

            Font textFont = getTextFont(settings.getTaskbarFontSize());
            g2.setFont(textFont);
            FontMetrics fm = g2.getFontMetrics();
            int textY = midY + (fm.getAscent() - fm.getDescent()) / 2;

            Color fgNormal = useLight ? new Color(0x11, 0x18, 0x27) : new Color(0xF1, 0xF3, 0xF5);
            Color fgDim = useLight ? new Color(0x6B, 0x72, 0x80, 175) : new Color(0x9C, 0xA3, 0xAF, 175);

            if (items.isEmpty()) {
                g2.setColor(fgDim);
                g2.drawString("--%", x, textY);
                return img;
            }

            for (int i = 0; i < items.size(); i++) {
                DeviceReading r = items.get(i);
                boolean hasCurrent = r.hasBatteryPercent();
                Color iconColor = hasCurrent ? fgNormal : fgDim;

                var icon = DeviceIconFactory.createDeviceIcon(r.deviceType(), iconSize, iconColor);
                icon.paintIcon(null, g2, x, midY - (iconSize / 2));
                x += iconSize + 6;

                String label;
                Color textColor;
                if (hasCurrent) {
                    int pct = r.batteryPercent();
                    label = pct + "%";
                    textColor = TaskbarWidgetWindow.resolvePercentTextColor(pct, r.charging(), useLight, settings, fgNormal);
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
                    int boltX = x + 1;
                    DeviceIconFactory.drawBoltGlyph(g2, boltX, midY - 6, BOLT_BOX, DeviceIconFactory.COLOR_CHARGING);
                    x = boltX + BOLT_VISIBLE_END;
                }
                if (i < items.size() - 1) {
                    x += r.charging() ? GAP_AFTER_BOLT : spacing;
                }
            }
        } finally {
            g2.dispose();
        }
        return img;
    }

    static Dimension computeLogicalSize(List<DeviceReading> items, AppSettings settings) {
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = scratch.createGraphics();
        try {
            Font font = getTextFont(settings.getTaskbarFontSize());
            FontMetrics fm = g2.getFontMetrics(font);
            int iconSize = settings.getTaskbarIconSize();
            int spacing = settings.getTaskbarSpacing();

            boolean hasSim = items.stream().anyMatch(DeviceReading::isSimulated);
            int width = 20;
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
        } finally {
            g2.dispose();
        }
    }

    private String buildPaintSignature(
            List<DeviceReading> items,
            boolean useLight,
            boolean isHovered,
            int dpi,
            int physW,
            int physH
    ) {
        StringBuilder sb = new StringBuilder(128);
        sb.append(dpi).append('|')
                .append(physW).append('x').append(physH).append('|')
                .append(useLight).append('|')
                .append(isHovered).append('|')
                .append(settings.getTaskbarIconSize()).append('|')
                .append(settings.getTaskbarFontSize()).append('|')
                .append(settings.getTaskbarSpacing()).append('|')
                .append(settings.isTaskbarBackgroundPlate()).append('|')
                .append(settings.getLowBatteryThreshold()).append('|')
                .append(settings.getMediumBatteryThreshold()).append('|');
        for (DeviceReading r : items) {
            sb.append(r.deviceId()).append(':')
                    .append(r.deviceType()).append(':')
                    .append(r.batteryPercent()).append(':')
                    .append(r.lastKnownPercent()).append(':')
                    .append(r.charging()).append(':')
                    .append(r.isSimulated()).append(';');
        }
        return sb.toString();
    }

    private static int resolveTaskbarDpi(HWND taskbar) {
        try {
            int dpi = User32Ex.INSTANCE.GetDpiForWindow(taskbar);
            if (dpi >= 72 && dpi <= 480) {
                return dpi;
            }
        } catch (Throwable ignored) {
            // fallback 96
        }
        return 96;
    }

    private LRESULT wndProc(HWND hWnd, int uMsg, WPARAM wParam, LPARAM lParam) {
        switch (uMsg) {
            case WM_MOUSEACTIVATE:
                return new LRESULT(MA_NOACTIVATE);

            case WM_SETCURSOR:
                if (handCursor != null) {
                    User32Ex.INSTANCE.SetCursor(handCursor);
                    return new LRESULT(1);
                }
                break;

            case WM_MOUSEMOVE:
                if (!hovered) {
                    hovered = true;
                    TRACKMOUSEEVENT tme = new TRACKMOUSEEVENT();
                    tme.dwFlags = TME_LEAVE;
                    tme.hwndTrack = hWnd;
                    User32Ex.INSTANCE.TrackMouseEvent(tme);
                    syncNativeStateOnThread();
                }
                return new LRESULT(0);

            case WM_MOUSELEAVE:
                if (hovered) {
                    hovered = false;
                    syncNativeStateOnThread();
                }
                return new LRESULT(0);

            case WM_LBUTTONUP:
                if (onOpenWindow != null) {
                    SwingUtilities.invokeLater(onOpenWindow);
                }
                return new LRESULT(0);

            case WM_RBUTTONUP:
                showNativeContextMenuOnThread(hWnd);
                return new LRESULT(0);

            case WM_PAINT:
                User32Ex.INSTANCE.ValidateRect(hWnd, null);
                return new LRESULT(0);

            case WM_DESTROY:
                activeAndVisible = false;
                return new LRESULT(0);

            default:
                break;
        }
        return User32.INSTANCE.DefWindowProc(hWnd, uMsg, new WPARAM(wParam != null ? wParam.longValue() : 0),
                new LPARAM(lParam != null ? lParam.longValue() : 0));
    }

    /**
     * Executa um ciclo completo de criação {@code WS_CHILD}, verificação de parent,
     * renderização {@code UpdateLayeredWindow} e destruição contra uma janela pai Win32
     * fornecida (usado em testes automatizados quando {@code Shell_TrayWnd} não está no desktop do teste).
     */
    boolean verifyChildCreationAndRenderOnCurrentThread(HWND parentHwnd, List<DeviceReading> testItems) {
        if (parentHwnd == null || !User32.INSTANCE.IsWindow(parentHwnd)) {
            return false;
        }
        RECT tbRect = new RECT();
        User32.INSTANCE.GetWindowRect(parentHwnd, tbRect);
        if (!createChildWindowOnThread(parentHwnd, parentHwnd, tbRect)) {
            return false;
        }
        try {
            HWND parentCheck = User32Ex.INSTANCE.GetAncestor(hwndChild, GA_PARENT);
            long expectedPeer = Pointer.nativeValue(parentHwnd.getPointer());
            long actualPeer = parentCheck != null ? Pointer.nativeValue(parentCheck.getPointer()) : 0L;
            if (actualPeer != expectedPeer) {
                return false;
            }
            Dimension logical = computeLogicalSize(testItems, settings);
            boolean painted = paintLayeredBufferOnThread(
                    testItems, false, false, 1.0, logical.width, 30, logical.width, 30
            );
            User32.INSTANCE.SetWindowPos(
                    hwndChild, HWND_TOP, 10, 5, logical.width, 30, SWP_NOACTIVATE | SWP_SHOWWINDOW
            );
            return painted && User32.INSTANCE.IsWindow(hwndChild);
        } finally {
            destroyChildWindowOnThread("fim do auto-teste Win32");
        }
    }

    private void showNativeContextMenuOnThread(HWND ownerHwnd) {
        HMENU menu = User32Ex.INSTANCE.CreatePopupMenu();
        if (menu == null) {
            return;
        }
        try {
            User32Ex.INSTANCE.AppendMenuW(menu, MF_STRING, new LONG_PTR(CMD_OPEN), new WString("Abrir Wireless Battery [Nativo Win32]"));
            User32Ex.INSTANCE.AppendMenuW(menu, MF_STRING, new LONG_PTR(CMD_REFRESH), new WString("Atualizar agora"));
            User32Ex.INSTANCE.AppendMenuW(menu, MF_STRING, new LONG_PTR(CMD_SETTINGS), new WString("Configurações..."));
            User32Ex.INSTANCE.AppendMenuW(menu, MF_SEPARATOR, new LONG_PTR(0), null);
            User32Ex.INSTANCE.AppendMenuW(menu, MF_STRING, new LONG_PTR(CMD_EXIT), new WString("Sair"));

            POINT pt = new POINT();
            User32Ex.INSTANCE.GetCursorPos(pt);
            User32.INSTANCE.SetForegroundWindow(ownerHwnd);
            int cmd = User32Ex.INSTANCE.TrackPopupMenu(
                    menu,
                    TPM_RIGHTBUTTON | TPM_BOTTOMALIGN | TPM_NONOTIFY | TPM_RETURNCMD,
                    pt.x,
                    pt.y,
                    0,
                    ownerHwnd,
                    null
            );
            User32.INSTANCE.PostMessage(ownerHwnd, WM_NULL, new WPARAM(0), new LPARAM(0));

            switch (cmd) {
                case CMD_OPEN -> {
                    if (onOpenWindow != null) SwingUtilities.invokeLater(onOpenWindow);
                }
                case CMD_REFRESH -> {
                    if (onRefresh != null) SwingUtilities.invokeLater(onRefresh);
                }
                case CMD_SETTINGS -> {
                    if (onOpenSettings != null) SwingUtilities.invokeLater(onOpenSettings);
                }
                case CMD_EXIT -> {
                    if (onExit != null) SwingUtilities.invokeLater(onExit);
                }
                default -> {}
            }
        } finally {
            User32Ex.INSTANCE.DestroyMenu(menu);
        }
    }
}
