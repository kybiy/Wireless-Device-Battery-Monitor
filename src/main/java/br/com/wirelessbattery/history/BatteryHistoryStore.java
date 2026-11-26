package br.com.wirelessbattery.history;

import br.com.wirelessbattery.config.AppSettings;
import br.com.wirelessbattery.diagnostics.AppLogger;
import br.com.wirelessbattery.model.DeviceReading;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Armazena histórico local de bateria em arquivos TSV separados para leituras reais e simuladas,
 * com downsampling inteligente, retenção configurável e escrita em lote em disco.
 */
public final class BatteryHistoryStore implements AutoCloseable {

    public static final Path DEFAULT_HISTORY_DIR = Path.of(
            System.getProperty("user.home"), ".wireless-battery-java", "history"
    );
    public static final String REAL_FILE_NAME = "history-real.tsv";
    public static final String SIM_FILE_NAME = "history-sim.tsv";
    public static final long DOWNSAMPLE_INTERVAL_MILLIS = 15L * 60L * 1000L;
    public static final long FLUSH_INTERVAL_MILLIS = 60L * 1000L;

    private static final String TSV_HEADER = "# epochMillis\tdeviceId\tdisplayName\tpercent\tcharging\tconnectionLabel\tsimulated";

    private final Path historyDir;
    private final AppSettings settings;
    private final AppLogger logger;
    private final Clock clock;

    private final Map<String, List<BatteryHistorySample>> realSamplesByDevice = new LinkedHashMap<>();
    private final Map<String, List<BatteryHistorySample>> simSamplesByDevice = new LinkedHashMap<>();
    private boolean realDirty = false;
    private boolean simDirty = false;
    private long lastFlushEpochMillis;

    public BatteryHistoryStore() {
        this(DEFAULT_HISTORY_DIR, new AppSettings(), null, Clock.systemUTC());
    }

    public BatteryHistoryStore(AppSettings settings) {
        this(DEFAULT_HISTORY_DIR, settings, null, Clock.systemUTC());
    }

    public BatteryHistoryStore(AppSettings settings, AppLogger logger) {
        this(DEFAULT_HISTORY_DIR, settings, logger, Clock.systemUTC());
    }

    public BatteryHistoryStore(Path historyDir, AppSettings settings, AppLogger logger) {
        this(historyDir, settings, logger, Clock.systemUTC());
    }

    public BatteryHistoryStore(Path historyDir, AppSettings settings, AppLogger logger, Clock clock) {
        this.historyDir = Objects.requireNonNull(historyDir, "historyDir não pode ser nulo");
        this.settings = settings != null ? settings : new AppSettings();
        this.logger = logger;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.lastFlushEpochMillis = this.clock.millis();
        loadFromDisk(false);
        loadFromDisk(true);
    }

    public Path getHistoryDir() {
        return historyDir;
    }

    public Path getStorageFile(boolean simulated) {
        return historyDir.resolve(simulated ? SIM_FILE_NAME : REAL_FILE_NAME);
    }

    /**
     * Registra leituras atuais aplicando downsampling e isolamento estrito por {@code deviceId}
     * e origem (real vs simulado).
     */
    public synchronized void recordReadings(List<DeviceReading> readings) {
        if (readings == null || readings.isEmpty() || !settings.isHistoryEnabled()) {
            return;
        }
        long now = clock.millis();

        for (DeviceReading reading : readings) {
            if (reading == null || !reading.hasBatteryPercent()) {
                continue;
            }
            String deviceId = reading.deviceId();
            if (deviceId.contains("-slot-")) {
                continue;
            }

            boolean simulated = reading.isSimulated();
            Map<String, List<BatteryHistorySample>> store = storeFor(simulated);
            List<BatteryHistorySample> list = store.computeIfAbsent(deviceId, k -> new ArrayList<>());

            BatteryHistorySample prev = list.isEmpty() ? null : list.get(list.size() - 1);
            int percent = reading.batteryPercent();
            boolean charging = reading.charging();

            boolean shouldRecord = prev == null
                    || percent != prev.percent()
                    || charging != prev.charging()
                    || (now - prev.epochMillis()) >= DOWNSAMPLE_INTERVAL_MILLIS;

            if (shouldRecord) {
                BatteryHistorySample sample = new BatteryHistorySample(
                        now,
                        deviceId,
                        reading.displayName(),
                        percent,
                        charging,
                        reading.connectionLabel(),
                        simulated
                );
                list.add(sample);
                markDirty(simulated, true);
            }
        }

        if ((realDirty || simDirty) && (now - lastFlushEpochMillis) >= FLUSH_INTERVAL_MILLIS) {
            flush();
        }
    }

    public synchronized List<BatteryHistorySample> getSamples(String deviceId, boolean simulated) {
        if (deviceId == null || deviceId.isBlank()) {
            return List.of();
        }
        pruneExpired(simulated, clock.millis());
        List<BatteryHistorySample> list = storeFor(simulated).get(deviceId);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        return List.copyOf(list);
    }

    public synchronized List<String> getTrackedDeviceIds(boolean simulated) {
        pruneExpired(simulated, clock.millis());
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, List<BatteryHistorySample>> entry : storeFor(simulated).entrySet()) {
            if (!entry.getValue().isEmpty()) {
                ids.add(entry.getKey());
            }
        }
        ids.sort(String::compareTo);
        return List.copyOf(ids);
    }

    public synchronized BatteryHistoryStats computeStats(String deviceId, boolean simulated) {
        List<BatteryHistorySample> samples = getSamples(deviceId, simulated);
        if (samples.isEmpty()) {
            return BatteryHistoryStats.empty();
        }

        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        long lastFullCharge = 0L;
        List<Instant> chargingStarts = new ArrayList<>();

        BatteryHistorySample prev = null;
        for (BatteryHistorySample sample : samples) {
            int p = sample.percent();
            if (p < min) min = p;
            if (p > max) max = p;
            if (p == 100 && sample.epochMillis() > lastFullCharge) {
                lastFullCharge = sample.epochMillis();
            }
            if (sample.charging() && (prev == null || !prev.charging())) {
                chargingStarts.add(sample.timestamp());
            }
            prev = sample;
        }

        return new BatteryHistoryStats(samples.size(), min, max, lastFullCharge, chargingStarts);
    }

    public synchronized void clearHistory(boolean simulated) {
        storeFor(simulated).clear();
        markDirty(simulated, false);
        try {
            Files.deleteIfExists(getStorageFile(simulated));
        } catch (IOException e) {
            logWarn("Falha ao remover arquivo de histórico: " + e.getMessage());
        }
    }

    public synchronized void clearDeviceHistory(String deviceId, boolean simulated) {
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        if (storeFor(simulated).remove(deviceId) != null) {
            markDirty(simulated, true);
            flush();
        }
    }

    public synchronized void exportToCsv(Path targetCsv, String deviceIdOrNull, boolean simulated) throws IOException {
        Objects.requireNonNull(targetCsv, "targetCsv não pode ser nulo");
        pruneExpired(simulated, clock.millis());

        List<BatteryHistorySample> toExport = new ArrayList<>();
        Map<String, List<BatteryHistorySample>> store = storeFor(simulated);
        if (deviceIdOrNull != null && !deviceIdOrNull.isBlank()) {
            List<BatteryHistorySample> list = store.get(deviceIdOrNull);
            if (list != null) {
                toExport.addAll(list);
            }
        } else {
            for (List<BatteryHistorySample> list : store.values()) {
                toExport.addAll(list);
            }
            toExport.sort(Comparator.comparingLong(BatteryHistorySample::epochMillis)
                    .thenComparing(BatteryHistorySample::deviceId));
        }

        Path parent = targetCsv.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("timestamp_iso,device_id,display_name,percent,charging,connection,source\n");
        for (BatteryHistorySample s : toExport) {
            sb.append(csvEscape(Instant.ofEpochMilli(s.epochMillis()).toString())).append(',')
                    .append(csvEscape(s.deviceId())).append(',')
                    .append(csvEscape(s.displayName())).append(',')
                    .append(s.percent()).append(',')
                    .append(s.charging()).append(',')
                    .append(csvEscape(s.connectionLabel())).append(',')
                    .append(s.simulated() ? "simulated" : "real").append('\n');
        }
        Files.writeString(targetCsv, sb.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Persiste imediatamente quaisquer alterações pendentes em disco após aplicar retenção.
     */
    public synchronized void flush() {
        long now = clock.millis();
        if (pruneExpired(false, now)) {
            realDirty = true;
        }
        if (pruneExpired(true, now)) {
            simDirty = true;
        }

        if (realDirty) {
            writeToDisk(false);
            realDirty = false;
        }
        if (simDirty) {
            writeToDisk(true);
            simDirty = false;
        }
        lastFlushEpochMillis = now;
    }

    @Override
    public synchronized void close() {
        flush();
    }

    private Map<String, List<BatteryHistorySample>> storeFor(boolean simulated) {
        return simulated ? simSamplesByDevice : realSamplesByDevice;
    }

    private void markDirty(boolean simulated, boolean dirty) {
        if (simulated) {
            simDirty = dirty;
        } else {
            realDirty = dirty;
        }
    }

    private boolean pruneExpired(boolean simulated, long nowMillis) {
        int retentionDays = settings.getHistoryRetentionDays();
        long cutoff = nowMillis - TimeUnit.DAYS.toMillis(retentionDays);
        boolean removedAny = false;

        Map<String, List<BatteryHistorySample>> store = storeFor(simulated);
        Iterator<Map.Entry<String, List<BatteryHistorySample>>> mapIt = store.entrySet().iterator();
        while (mapIt.hasNext()) {
            Map.Entry<String, List<BatteryHistorySample>> entry = mapIt.next();
            List<BatteryHistorySample> list = entry.getValue();
            if (list.removeIf(s -> s.epochMillis() < cutoff)) {
                removedAny = true;
            }
            if (list.isEmpty()) {
                mapIt.remove();
            }
        }
        return removedAny;
    }

    private void loadFromDisk(boolean simulated) {
        Path file = getStorageFile(simulated);
        if (!Files.isRegularFile(file)) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logWarn("Não foi possível ler histórico " + file.getFileName() + ": " + e.getMessage());
            return;
        }

        Map<String, List<BatteryHistorySample>> store = storeFor(simulated);
        int invalidCount = 0;
        for (String line : lines) {
            if (line == null || line.isBlank() || line.startsWith("#")) {
                continue;
            }
            BatteryHistorySample sample = parseTsvLine(line, simulated);
            if (sample == null) {
                invalidCount++;
                continue;
            }
            store.computeIfAbsent(sample.deviceId(), k -> new ArrayList<>()).add(sample);
        }
        if (invalidCount > 0) {
            logWarn("Ignoradas " + invalidCount + " linha(s) inválida(s) em " + file.getFileName());
        }

        for (List<BatteryHistorySample> list : store.values()) {
            list.sort(Comparator.comparingLong(BatteryHistorySample::epochMillis));
        }
        if (pruneExpired(simulated, clock.millis())) {
            markDirty(simulated, true);
        }
    }

    private void writeToDisk(boolean simulated) {
        Path target = getStorageFile(simulated);
        try {
            Files.createDirectories(historyDir);
            StringBuilder sb = new StringBuilder();
            sb.append(TSV_HEADER).append('\n');

            List<BatteryHistorySample> all = new ArrayList<>();
            for (List<BatteryHistorySample> list : storeFor(simulated).values()) {
                all.addAll(list);
            }
            all.sort(Comparator.comparingLong(BatteryHistorySample::epochMillis)
                    .thenComparing(BatteryHistorySample::deviceId));

            for (BatteryHistorySample s : all) {
                sb.append(s.epochMillis()).append('\t')
                        .append(tsvSanitize(s.deviceId())).append('\t')
                        .append(tsvSanitize(s.displayName())).append('\t')
                        .append(s.percent()).append('\t')
                        .append(s.charging()).append('\t')
                        .append(tsvSanitize(s.connectionLabel())).append('\t')
                        .append(s.simulated()).append('\n');
            }

            Path fn = target.getFileName();
            String baseName = fn != null ? fn.toString() : "history.tsv";
            Path tempFile = historyDir.resolve(baseName + ".tmp");
            Files.writeString(tempFile, sb.toString(), StandardCharsets.UTF_8);
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logWarn("Falha ao salvar histórico " + target.getFileName() + ": " + e.getMessage());
        }
    }

    private static BatteryHistorySample parseTsvLine(String line, boolean expectedSimulated) {
        String[] parts = line.split("\t", -1);
        if (parts.length < 7) {
            return null;
        }
        try {
            long epochMillis = Long.parseLong(parts[0].trim());
            String deviceId = parts[1].trim();
            String displayName = parts[2].trim();
            int percent = Integer.parseInt(parts[3].trim());
            boolean charging = Boolean.parseBoolean(parts[4].trim());
            String connection = parts[5].trim();
            boolean simulated = Boolean.parseBoolean(parts[6].trim());
            if (simulated != expectedSimulated) {
                return null;
            }
            return new BatteryHistorySample(epochMillis, deviceId, displayName, percent, charging, connection, simulated);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String tsvSanitize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuotes = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!needsQuotes) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private void logWarn(String message) {
        if (logger != null) {
            logger.warn("[History] " + message);
        }
    }
}
