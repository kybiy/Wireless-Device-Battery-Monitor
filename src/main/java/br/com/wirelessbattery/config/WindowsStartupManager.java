package br.com.wirelessbattery.config;

import br.com.wirelessbattery.diagnostics.AppLogger;
import java.io.File;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Gerencia a opção "Inicializar com o Windows" via chave de usuário atual
 * ({@code HKCU\Software\Microsoft\Windows\CurrentVersion\Run}), sem privilégios de administrador.
 */
public final class WindowsStartupManager {
    private static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String VALUE_NAME = "WirelessBatteryJavaMonitor";

    private WindowsStartupManager() {}

    public static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    public static boolean applyStartupSetting(boolean enable, AppLogger logger) {
        if (!isWindows()) {
            return false;
        }
        try {
            ProcessBuilder pb;
            if (enable) {
                String command = buildLaunchCommand();
                pb = new ProcessBuilder(
                        "reg", "add", RUN_KEY,
                        "/v", VALUE_NAME,
                        "/t", "REG_SZ",
                        "/d", command,
                        "/f"
                );
            } else {
                pb = new ProcessBuilder(
                        "reg", "delete", RUN_KEY,
                        "/v", VALUE_NAME,
                        "/f"
                );
            }
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exit = process.waitFor();
            if (exit == 0 || !enable) {
                if (logger != null) {
                    logger.info(enable
                            ? "Inicialização automática com o Windows ativada (HKCU\\...\\Run)."
                            : "Inicialização automática com o Windows desativada.");
                }
                return true;
            }
            if (logger != null) {
                logger.warn("Não foi possível atualizar a inicialização automática (código " + exit + ").");
            }
        } catch (Exception e) {
            if (logger != null) {
                logger.error("Erro ao configurar inicialização com o Windows", e);
            }
        }
        return false;
    }

    private static String buildLaunchCommand() {
        String javaw = Path.of(System.getProperty("java.home"), "bin", "javaw.exe").toString();
        String cp = System.getProperty("java.class.path", "");
        if (cp.endsWith(".jar") && !cp.contains(File.pathSeparator)) {
            return "\"" + javaw + "\" -jar \"" + cp + "\" --minimized";
        }
        return "\"" + javaw + "\" -cp \"" + cp + "\" br.com.wirelessbattery.Main --minimized";
    }
}
