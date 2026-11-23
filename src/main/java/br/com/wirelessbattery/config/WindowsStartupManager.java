package br.com.wirelessbattery.config;

import br.com.wirelessbattery.diagnostics.AppLogger;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Gerencia a opção "Inicializar com o Windows" via chave de registro do usuário atual
 * ({@code HKCU\Software\Microsoft\Windows\CurrentVersion\Run}), sem exigir privilégios de administrador.
 *
 * Funcionalidades:
 * <ul>
 *   <li>Grava, lê e remove o valor diretamente via API Win32 ({@link Advapi32Util}), evitando
 *       falhas de escape de aspas do {@code reg.exe} via {@code ProcessBuilder}.</li>
 *   <li>Prioriza o executável instalado ({@code %LOCALAPPDATA%\WirelessBattery\WirelessBattery.exe}
 *       ou {@code %ProgramFiles%\WirelessBattery\WirelessBattery.exe}) em vez de pastas temporárias
 *       de build ({@code dist\app-image} ou {@code target}).</li>
 *   <li>Verifica e corrige o estado de aprovação do Windows 11 em
 *       {@code HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run}.</li>
 *   <li>Sincroniza automaticamente o registro a cada inicialização do aplicativo.</li>
 * </ul>
 */
public final class WindowsStartupManager {
    public static final String RUN_KEY_PATH = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    public static final String STARTUP_APPROVED_RUN_KEY_PATH =
            "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run";
    public static final String VALUE_NAME = "WirelessBatteryMonitor";
    static final String LEGACY_VALUE_NAME = "WirelessBatteryJavaMonitor";

    private WindowsStartupManager() {}

    public record StartupDiagnostic(
            boolean preferenceEnabled,
            String expectedCommand,
            String registeredCommand,
            boolean registryEntryPresent,
            boolean registryMatchesExpected,
            boolean disabledByWindowsStartupApproved
    ) {}

    public static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    /**
     * Sincroniza o estado do registro {@code HKCU\...\Run} com a preferência salva em {@link AppSettings}
     * durante a inicialização do aplicativo e registra logs detalhados de diagnóstico.
     */
    public static boolean reconcileStartupSetting(AppSettings settings, AppLogger logger) {
        boolean enabled = settings != null && settings.isStartWithWindows();
        if (!isWindows()) {
            return false;
        }
        StartupDiagnostic before = inspectCurrentState(enabled);
        if (logger != null) {
            logger.info("[STARTUP] Preferência 'Iniciar com o Windows': " + (enabled ? "ATIVADA" : "DESATIVADA")
                    + " | Comando esperado: " + before.expectedCommand());
        }

        if (enabled) {
            if (before.disabledByWindowsStartupApproved() && logger != null) {
                logger.warn("[STARTUP] O Windows (Gerenciador de Tarefas / StartupApproved\\Run) havia marcado a inicialização como desabilitada — reabilitando...");
            }
            if (!before.registryEntryPresent()
                    || !before.registryMatchesExpected()
                    || before.disabledByWindowsStartupApproved()) {
                return applyStartupSetting(true, logger);
            }
            removeLegacyValueIfPresent();
            if (logger != null) {
                logger.info("[STARTUP] Entrada no registro HKCU\\" + RUN_KEY_PATH + "\\" + VALUE_NAME
                        + " já está ativa e atualizada: " + before.registeredCommand());
            }
            return true;
        } else {
            if (before.registryEntryPresent() || isLegacyValuePresent()) {
                return applyStartupSetting(false, logger);
            }
            if (logger != null) {
                logger.info("[STARTUP] Inicialização automática desativada (nenhuma entrada ativa em HKCU\\...\\Run).");
            }
            return true;
        }
    }

    /**
     * Aplica imediatamente a configuração de inicialização com o Windows no registro {@code HKCU}
     * e confirma a criação/remoção lendo o valor de volta.
     */
    public static boolean applyStartupSetting(boolean enable, AppLogger logger) {
        if (!isWindows()) {
            return false;
        }
        try {
            if (enable) {
                String command = buildLaunchCommand();
                if (logger != null) {
                    logger.info("[STARTUP] Registrando inicialização automática em HKCU\\" + RUN_KEY_PATH
                            + " [" + VALUE_NAME + "] -> " + command);
                }
                if (!Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, RUN_KEY_PATH)) {
                    Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, RUN_KEY_PATH);
                }
                Advapi32Util.registrySetStringValue(
                        WinReg.HKEY_CURRENT_USER,
                        RUN_KEY_PATH,
                        VALUE_NAME,
                        command
                );
                removeLegacyValueIfPresent();
                ensureStartupApprovedEnabled(logger);

                String readBack = readRegisteredCommand();
                boolean created = command.equals(readBack);
                if (created) {
                    if (logger != null) {
                        logger.info("[STARTUP] Entrada criada e confirmada no registro HKCU\\" + RUN_KEY_PATH
                                + "\\" + VALUE_NAME + " = " + readBack);
                        if (isTemporaryBuildPath(command)) {
                            logger.warn("[STARTUP] Atenção: o caminho registrado aponta para uma pasta de build/portátil ("
                                    + command + "). Instale o WirelessBattery-1.0.0.exe para registrar o caminho definitivo em %LOCALAPPDATA%\\WirelessBattery.");
                        }
                    }
                    return true;
                }
                if (logger != null) {
                    logger.warn("[STARTUP] Falha ao confirmar entrada no registro HKCU\\" + RUN_KEY_PATH
                            + " (lido: " + readBack + ").");
                }
                return false;
            } else {
                boolean removedAny = false;
                if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY_PATH, VALUE_NAME)) {
                    Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY_PATH, VALUE_NAME);
                    removedAny = true;
                }
                if (removeLegacyValueIfPresent()) {
                    removedAny = true;
                }
                boolean stillExists = Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY_PATH, VALUE_NAME);
                if (!stillExists) {
                    if (logger != null) {
                        logger.info("[STARTUP] Inicialização automática com o Windows desativada"
                                + (removedAny ? " (entrada removida de HKCU\\...\\Run)." : "."));
                    }
                    return true;
                }
                if (logger != null) {
                    logger.warn("[STARTUP] Não foi possível remover a entrada de inicialização de HKCU\\...\\Run.");
                }
                return false;
            }
        } catch (Exception e) {
            if (logger != null) {
                logger.error("[STARTUP] Erro ao configurar inicialização com o Windows no registro HKCU", e);
            }
            return false;
        }
    }

    public static StartupDiagnostic inspectCurrentState(boolean preferenceEnabled) {
        String expected = buildLaunchCommand();
        if (!isWindows()) {
            return new StartupDiagnostic(preferenceEnabled, expected, null, false, false, false);
        }
        String registered = readRegisteredCommand();
        boolean present = registered != null && !registered.isBlank();
        boolean matches = present && expected.equals(registered.trim());
        boolean disabledByWindows = isDisabledInStartupApproved();
        return new StartupDiagnostic(preferenceEnabled, expected, registered, present, matches, disabledByWindows);
    }

    public static String readRegisteredCommand() {
        if (!isWindows()) {
            return null;
        }
        try {
            if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY_PATH, VALUE_NAME)) {
                return Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY_PATH, VALUE_NAME);
            }
        } catch (Exception ignored) {
            // Ignorado
        }
        return null;
    }

    public static boolean isDisabledInStartupApproved() {
        if (!isWindows()) {
            return false;
        }
        try {
            for (String name : List.of(VALUE_NAME, LEGACY_VALUE_NAME)) {
                if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, STARTUP_APPROVED_RUN_KEY_PATH, name)) {
                    byte[] data = Advapi32Util.registryGetBinaryValue(
                            WinReg.HKEY_CURRENT_USER, STARTUP_APPROVED_RUN_KEY_PATH, name
                    );
                    // No Windows 10/11, byte[0] == 0x02 ou 0x06 significa habilitado;
                    // valores ímpares (0x01, 0x03, 0x07) indicam desabilitado pelo Gerenciador de Tarefas.
                    if (data != null && data.length >= 1 && (data[0] & 0x01) != 0) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
            // Ignorado
        }
        return false;
    }

    private static void ensureStartupApprovedEnabled(AppLogger logger) {
        try {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, STARTUP_APPROVED_RUN_KEY_PATH)) {
                if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, STARTUP_APPROVED_RUN_KEY_PATH, LEGACY_VALUE_NAME)) {
                    Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, STARTUP_APPROVED_RUN_KEY_PATH, LEGACY_VALUE_NAME);
                }
                byte[] enabledBytes = new byte[]{0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
                Advapi32Util.registrySetBinaryValue(
                        WinReg.HKEY_CURRENT_USER,
                        STARTUP_APPROVED_RUN_KEY_PATH,
                        VALUE_NAME,
                        enabledBytes
                );
            }
        } catch (Exception e) {
            if (logger != null) {
                logger.warn("[STARTUP] Não foi possível atualizar StartupApproved\\Run: " + e.getMessage());
            }
        }
    }

    private static boolean isLegacyValuePresent() {
        try {
            return Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY_PATH, LEGACY_VALUE_NAME);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean removeLegacyValueIfPresent() {
        try {
            if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY_PATH, LEGACY_VALUE_NAME)) {
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY_PATH, LEGACY_VALUE_NAME);
                return true;
            }
        } catch (Exception ignored) {
            // Ignorado
        }
        return false;
    }

    static String buildLaunchCommand() {
        return buildLaunchCommand(findStandardInstalledExecutables());
    }

    static String buildLaunchCommand(List<Path> candidateInstalledPaths) {
        Path installedExe = findFirstExistingFile(candidateInstalledPaths);

        String jpackageAppPath = System.getProperty("jpackage.app-path");
        if (jpackageAppPath != null && !jpackageAppPath.isBlank()) {
            Path exePath = Path.of(jpackageAppPath.trim()).toAbsolutePath().normalize();
            if (Files.isRegularFile(exePath)) {
                if (installedExe != null && isTemporaryBuildPath(exePath.toString())) {
                    return formatExeCommand(installedExe);
                }
                return formatExeCommand(exePath);
            }
        }

        String currentCmd = ProcessHandle.current().info().command().orElse("");
        if (!currentCmd.isBlank()) {
            String lower = currentCmd.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".exe") && !lower.endsWith("\\java.exe") && !lower.endsWith("\\javaw.exe")) {
                Path exePath = Path.of(currentCmd).toAbsolutePath().normalize();
                if (Files.isRegularFile(exePath)) {
                    if (installedExe != null && isTemporaryBuildPath(exePath.toString())) {
                        return formatExeCommand(installedExe);
                    }
                    return formatExeCommand(exePath);
                }
            }
        }

        if (installedExe != null) {
            return formatExeCommand(installedExe);
        }

        String javaw = Path.of(System.getProperty("java.home"), "bin", "javaw.exe").toString();
        String cp = System.getProperty("java.class.path", "");
        if (cp.endsWith(".jar") && !cp.contains(File.pathSeparator)) {
            return "\"" + javaw + "\" -jar \"" + Path.of(cp).toAbsolutePath().normalize() + "\" --minimized";
        }
        return "\"" + javaw + "\" -cp \"" + cp + "\" br.com.wirelessbattery.Main --minimized";
    }

    private static String formatExeCommand(Path exePath) {
        return "\"" + exePath.toAbsolutePath().normalize() + "\" --minimized";
    }

    static boolean isTemporaryBuildPath(String pathOrCommand) {
        if (pathOrCommand == null) {
            return false;
        }
        String norm = pathOrCommand.toLowerCase(Locale.ROOT).replace('/', '\\');
        return norm.contains("\\dist\\app-image\\")
                || norm.contains("\\target\\");
    }

    private static List<Path> findStandardInstalledExecutables() {
        List<Path> list = new ArrayList<>();
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            list.add(Path.of(localAppData, "WirelessBattery", "WirelessBattery.exe"));
            list.add(Path.of(localAppData, "Programs", "WirelessBattery", "WirelessBattery.exe"));
        }
        String userHome = System.getProperty("user.home");
        if (userHome != null && !userHome.isBlank()) {
            list.add(Path.of(userHome, "AppData", "Local", "WirelessBattery", "WirelessBattery.exe"));
        }
        String progFiles = System.getenv("ProgramFiles");
        if (progFiles != null && !progFiles.isBlank()) {
            list.add(Path.of(progFiles, "WirelessBattery", "WirelessBattery.exe"));
        }
        return list;
    }

    private static Path findFirstExistingFile(List<Path> paths) {
        if (paths == null) {
            return null;
        }
        for (Path p : paths) {
            if (p != null && Files.isRegularFile(p)) {
                return p.toAbsolutePath().normalize();
            }
        }
        return null;
    }
}
