package br.com.wirelessbattery.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Logger central de diagnóstico para exibir eventos na interface, no console Java Logging
 * e em arquivo persistente ({@code ~/.wireless-battery-java/wireless-battery.log}).
 */
public final class AppLogger {
    private static final Logger JUL = Logger.getLogger("br.com.wirelessbattery");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter FILE_DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_ENTRIES = 400;
    private static final long MAX_LOG_FILE_BYTES = 256 * 1024L;
    private static final Path DEFAULT_LOG_FILE = Path.of(
            System.getProperty("user.home"), ".wireless-battery-java", "wireless-battery.log"
    );

    private final List<String> history = new ArrayList<>();
    private final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private final Path logFilePath;

    public AppLogger() {
        this(DEFAULT_LOG_FILE);
    }

    public AppLogger(Path logFilePath) {
        this.logFilePath = logFilePath;
    }

    public Path getLogFilePath() {
        return logFilePath;
    }

    public void info(String message) {
        log(Level.INFO, "INFO", message, null);
    }

    public void warn(String message) {
        log(Level.WARNING, "AVISO", message, null);
    }

    public void error(String message, Throwable error) {
        log(Level.SEVERE, "ERRO", message, error);
    }

    /**
     * Registra uma operação HID estruturada para diagnóstico técnico, incluindo nome do dispositivo,
     * VID/PID, interface, Usage Page/Usage, Report ID, operação, resultado e erro (sem dados sensíveis
     * como número de série ou caminhos do usuário).
     */
    public void hidOperation(
            String deviceName,
            int vid,
            int pid,
            int interfaceNumber,
            int usagePage,
            int usage,
            Integer reportId,
            String operation,
            String result,
            String errorDetail,
            boolean isWarning
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("[HID] ")
                .append(deviceName != null ? deviceName : "Desconhecido")
                .append(String.format(" | VID:PID=0x%04X:0x%04X", vid & 0xFFFF, pid & 0xFFFF))
                .append(" | IF=").append(interfaceNumber)
                .append(String.format(" | Usage=0x%04X:0x%04X", usagePage & 0xFFFF, usage & 0xFFFF));
        if (reportId != null) {
            sb.append(String.format(" | ReportID=0x%02X", reportId & 0xFF));
        } else {
            sb.append(" | ReportID=N/A");
        }
        sb.append(" | Op=").append(operation != null ? operation : "-");
        sb.append(" | Resultado=").append(result != null ? result : "-");
        if (errorDetail != null && !errorDetail.isBlank()) {
            sb.append(" | Erro=").append(sanitizeError(errorDetail));
        }
        if (isWarning) {
            warn(sb.toString());
        } else {
            info(sb.toString());
        }
    }

    private static String sanitizeError(String raw) {
        String compact = raw.replace('\r', ' ').replace('\n', ' ').trim();
        return compact.length() > 180 ? compact.substring(0, 180) + "..." : compact;
    }

    private void log(Level level, String tag, String message, Throwable error) {
        String suffix = (error != null && error.getMessage() != null)
                ? " (" + error.getClass().getSimpleName() + ": " + error.getMessage() + ")"
                : "";
        LocalDateTime now = LocalDateTime.now();
        String line = now.toLocalTime().format(TIME_FMT) + " [" + tag + "] " + message + suffix;
        String fileLine = now.format(FILE_DATE_TIME_FMT) + " [" + tag + "] " + message + suffix;

        if (error != null) {
            JUL.log(level, message, error);
        } else {
            JUL.log(level, message);
        }

        synchronized (history) {
            if (history.size() >= MAX_ENTRIES) {
                history.remove(0);
            }
            history.add(line);
        }
        appendToFileQuietly(fileLine);

        for (Consumer<String> listener : listeners) {
            listener.accept(line);
        }
    }

    private synchronized void appendToFileQuietly(String fileLine) {
        if (logFilePath == null) {
            return;
        }
        try {
            Path parent = logFilePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (Files.isRegularFile(logFilePath) && Files.size(logFilePath) > MAX_LOG_FILE_BYTES) {
                Path backup = logFilePath.resolveSibling(logFilePath.getFileName().toString() + ".1");
                Files.move(logFilePath, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(
                    logFilePath,
                    fileLine + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException ignored) {
            // Melhor esforço — não interrompe o funcionamento caso o disco não permita escrita
        }
    }

    public List<String> getHistory() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    public void clear() {
        synchronized (history) {
            history.clear();
        }
    }

    public void addListener(Consumer<String> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<String> listener) {
        listeners.remove(listener);
    }
}
