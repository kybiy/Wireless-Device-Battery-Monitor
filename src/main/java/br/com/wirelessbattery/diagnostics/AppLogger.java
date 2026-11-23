package br.com.wirelessbattery.diagnostics;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Logger central de diagnóstico para exibir eventos na interface e no console Java Logging.
 */
public final class AppLogger {
    private static final Logger JUL = Logger.getLogger("br.com.wirelessbattery");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_ENTRIES = 400;

    private final List<String> history = new ArrayList<>();
    private final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    public void info(String message) {
        log(Level.INFO, "INFO", message, null);
    }

    public void warn(String message) {
        log(Level.WARNING, "AVISO", message, null);
    }

    public void error(String message, Throwable error) {
        log(Level.SEVERE, "ERRO", message, error);
    }

    private void log(Level level, String tag, String message, Throwable error) {
        String suffix = (error != null && error.getMessage() != null)
                ? " (" + error.getClass().getSimpleName() + ": " + error.getMessage() + ")"
                : "";
        String line = LocalTime.now().format(TIME_FMT) + " [" + tag + "] " + message + suffix;

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

        for (Consumer<String> listener : listeners) {
            listener.accept(line);
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
