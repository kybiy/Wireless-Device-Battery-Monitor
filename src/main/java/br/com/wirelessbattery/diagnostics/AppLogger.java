package br.com.wirelessbattery.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Logger central de diagnóstico para exibir eventos na interface, no console Java Logging
 * e em arquivo persistente ({@code ~/.wireless-battery-java/wireless-battery.log}).
 */
public final class AppLogger {
    private static final Logger JUL = Logger.getLogger("br.com.wirelessbattery");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter FILE_DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final int MAX_ENTRIES = 400;
    public static final long MAX_LOG_FILE_BYTES = 256 * 1024L;
    public static final int DEFAULT_MAX_BACKUPS = 3;
    public static final String RAW_HID_DEBUG_PROPERTY = "wirelessbattery.debug.rawHid";

    private static final Pattern SPACED_RAW_HEX_PATTERN =
            Pattern.compile("(?<![0-9A-Fa-f])(?:[0-9A-Fa-f]{2}\\s+){15,}[0-9A-Fa-f]{2}(?![0-9A-Fa-f])");
    private static final Pattern DELIMITED_RAW_HEX_PATTERN =
            Pattern.compile("(?<![0-9A-Fa-f:-])(?:[0-9A-Fa-f]{2}[:-]){15,}[0-9A-Fa-f]{2}(?![0-9A-Fa-f:-])");
    private static final Pattern CONTINUOUS_RAW_HEX_PATTERN =
            Pattern.compile("(?<![0-9A-Fa-f])[0-9A-Fa-f]{40,}(?![0-9A-Fa-f])");

    private final List<String> history = new ArrayList<>();
    private final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private final Path logFilePath;
    private final long maxFileBytes;
    private final int maxBackups;

    public AppLogger() {
        this(DEFAULT_LOG_FILE, MAX_LOG_FILE_BYTES, DEFAULT_MAX_BACKUPS);
    }

    public AppLogger(Path logFilePath) {
        this(logFilePath, MAX_LOG_FILE_BYTES, DEFAULT_MAX_BACKUPS);
    }

    public AppLogger(Path logFilePath, long maxFileBytes, int maxBackups) {
        this.logFilePath = logFilePath;
        this.maxFileBytes = Math.max(1L, maxFileBytes);
        this.maxBackups = Math.max(0, maxBackups);
    }

    private static final Path DEFAULT_LOG_FILE = Path.of(
            System.getProperty("user.home"), ".wireless-battery-java", "wireless-battery.log"
    );

    public static boolean isRawHidDebugEnabled() {
        return Boolean.getBoolean(RAW_HID_DEBUG_PROPERTY);
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
     * como número de série ou caminhos do usuário, e sem despejar payloads HID brutos completos fora
     * do modo {@code -Dwirelessbattery.debug.rawHid=true}).
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
        sb.append(" | Op=").append(operation != null ? redactRawPayloadsIfDisabled(operation) : "-");
        sb.append(" | Resultado=").append(result != null ? redactRawPayloadsIfDisabled(result) : "-");
        if (errorDetail != null && !errorDetail.isBlank()) {
            sb.append(" | Erro=").append(sanitizeError(redactRawPayloadsIfDisabled(errorDetail)));
        }
        if (isWarning) {
            warn(sb.toString());
        } else {
            info(sb.toString());
        }
    }

    /**
     * Registra dump hexadecimal bruto de um payload HID apenas quando {@code -Dwirelessbattery.debug.rawHid=true}
     * estiver explicitamente habilitado. Em modo normal, não emite nenhum log contínuo de payload bruto.
     */
    public void hidRawPayload(String deviceName, int vid, int pid, String direction, byte[] payload, int length) {
        if (!isRawHidDebugEnabled() || payload == null || length <= 0) {
            return;
        }
        int len = Math.min(length, payload.length);
        StringBuilder hex = new StringBuilder(len * 3);
        for (int i = 0; i < len; i++) {
            if (i > 0) {
                hex.append(' ');
            }
            hex.append(String.format("%02X", payload[i] & 0xFF));
        }
        info(String.format("[HID-RAW] %s (0x%04X:0x%04X) %s (%dB): %s",
                deviceName != null ? deviceName : "Desconhecido",
                vid & 0xFFFF,
                pid & 0xFFFF,
                direction != null ? direction : "IO",
                len,
                hex));
    }

    private static String redactRawPayloadsIfDisabled(String text) {
        if (text == null || text.isEmpty() || isRawHidDebugEnabled()) {
            return text;
        }
        String sanitized = SPACED_RAW_HEX_PATTERN.matcher(text).replaceAll("<RAW_HID_PAYLOAD_REDACTED>");
        sanitized = DELIMITED_RAW_HEX_PATTERN.matcher(sanitized).replaceAll("<RAW_HID_PAYLOAD_REDACTED>");
        return CONTINUOUS_RAW_HEX_PATTERN.matcher(sanitized).replaceAll("<RAW_HID_PAYLOAD_REDACTED>");
    }

    private static String sanitizeError(String raw) {
        String compact = raw.replace('\r', ' ').replace('\n', ' ').trim();
        return compact.length() > 180 ? compact.substring(0, 180) + "..." : compact;
    }

    private void log(Level level, String tag, String message, Throwable error) {
        String safeMessage = redactRawPayloadsIfDisabled(message);
        String suffix = (error != null && error.getMessage() != null)
                ? " (" + error.getClass().getSimpleName() + ": " + redactRawPayloadsIfDisabled(error.getMessage()) + ")"
                : "";
        LocalDateTime now = LocalDateTime.now();
        String line = now.toLocalTime().format(TIME_FMT) + " [" + tag + "] " + safeMessage + suffix;
        String fileLine = now.format(FILE_DATE_TIME_FMT) + " [" + tag + "] " + safeMessage + suffix;

        if (error != null) {
            JUL.log(level, safeMessage, error);
        } else {
            JUL.log(level, safeMessage);
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
            byte[] lineBytes = (fileLine + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
            if (Files.isRegularFile(logFilePath)) {
                long currentSize = Files.size(logFilePath);
                if (currentSize > maxFileBytes || (currentSize > 0 && currentSize + lineBytes.length > maxFileBytes)) {
                    rotateLogFiles();
                }
            }
            Files.write(
                    logFilePath,
                    lineBytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException ignored) {
            // Melhor esforço — não interrompe o funcionamento caso o disco não permita escrita
        }
    }

    private void rotateLogFiles() throws IOException {
        if (maxBackups <= 0) {
            Files.deleteIfExists(logFilePath);
            return;
        }
        Path fn = logFilePath.getFileName();
        String baseName = fn != null ? fn.toString() : "wireless-battery.log";
        Path oldest = logFilePath.resolveSibling(baseName + "." + maxBackups);
        Files.deleteIfExists(oldest);
        for (int i = maxBackups - 1; i >= 1; i--) {
            Path source = logFilePath.resolveSibling(baseName + "." + i);
            if (Files.exists(source)) {
                Path target = logFilePath.resolveSibling(baseName + "." + (i + 1));
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.move(logFilePath, logFilePath.resolveSibling(baseName + ".1"), StandardCopyOption.REPLACE_EXISTING);
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
