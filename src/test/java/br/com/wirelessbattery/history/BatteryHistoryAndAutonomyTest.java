package br.com.wirelessbattery.history;

import static org.junit.jupiter.api.Assertions.*;

import br.com.wirelessbattery.config.AppSettings;
import br.com.wirelessbattery.model.ConnectionStatus;
import br.com.wirelessbattery.model.DataSourceType;
import br.com.wirelessbattery.model.DeviceReading;
import br.com.wirelessbattery.model.DeviceType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BatteryHistoryAndAutonomyTest {

    private static final class MutableClock extends Clock {
        private Instant current;

        MutableClock(Instant initial) {
            this.current = initial;
        }

        void advanceMillis(long millis) {
            this.current = this.current.plusMillis(millis);
        }

        void advanceMinutes(long minutes) {
            advanceMillis(minutes * 60_000L);
        }

        void advanceDays(long days) {
            advanceMillis(TimeUnit.DAYS.toMillis(days));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    @Test
    void rejectsInvalidBatterySamplePercentages() {
        assertThrows(IllegalArgumentException.class, () ->
                new BatteryHistorySample(1000L, "akko-verge-s9", "Akko", -1, false, "2.4 GHz", false));
        assertThrows(IllegalArgumentException.class, () ->
                new BatteryHistorySample(1000L, "akko-verge-s9", "Akko", 101, false, "2.4 GHz", false));
    }

    @Test
    void isolatesRealVsSimulatedAndDevicesAndSkipsSlotsOrInvalidReadings(@TempDir Path tempDir) {
        AppSettings settings = new AppSettings(tempDir.resolve("settings.properties"));
        MutableClock clock = new MutableClock(Instant.parse("2026-11-26T10:00:00Z"));

        try (BatteryHistoryStore store = new BatteryHistoryStore(tempDir.resolve("history"), settings, null, clock)) {
            DeviceReading realAkko = DeviceReading.realConnected(
                    "akko-verge-s9", "Akko Verge S9", DeviceType.MOUSE, 82, false, "2.4 GHz", "OK");
            DeviceReading realQcy = DeviceReading.realConnected(
                    "qcy-t13", "QCY T13", DeviceType.HEADSET, 65, false, "Bluetooth", "OK");
            DeviceReading simAkko = DeviceReading.simulated(
                    "akko-verge-s9", "Akko Verge S9", DeviceType.MOUSE, 30, true, true, "Sim", "Sim", 30, LocalTime.now());
            DeviceReading slotReading = DeviceReading.realDisconnected(
                    "mouse-slot-1", "Slot 1", DeviceType.MOUSE, "2.4 GHz", "Empty");
            DeviceReading unavailableReading = new DeviceReading(
                    "gamesir-cyclone-2", "GameSir Cyclone 2", DeviceType.GAMEPAD,
                    null, false, ConnectionStatus.NO_RESPONSE, "2.4 GHz", DataSourceType.REAL,
                    "No response", LocalTime.now(), 50, LocalTime.now());

            store.recordReadings(List.of(realAkko, realQcy, simAkko, slotReading, unavailableReading));
            store.flush();

            assertEquals(List.of("akko-verge-s9", "qcy-t13"), store.getTrackedDeviceIds(false));
            assertEquals(List.of("akko-verge-s9"), store.getTrackedDeviceIds(true));

            List<BatteryHistorySample> realAkkoSamples = store.getSamples("akko-verge-s9", false);
            List<BatteryHistorySample> simAkkoSamples = store.getSamples("akko-verge-s9", true);
            assertEquals(1, realAkkoSamples.size());
            assertEquals(82, realAkkoSamples.get(0).percent());
            assertFalse(realAkkoSamples.get(0).simulated());

            assertEquals(1, simAkkoSamples.size());
            assertEquals(30, simAkkoSamples.get(0).percent());
            assertTrue(simAkkoSamples.get(0).simulated());

            assertTrue(Files.isRegularFile(store.getStorageFile(false)));
            assertTrue(Files.isRegularFile(store.getStorageFile(true)));
        }
    }

    @Test
    void appliesSmartDownsamplingAndBatchesDiskWrites(@TempDir Path tempDir) {
        Path historyDir = tempDir.resolve("history");
        AppSettings settings = new AppSettings(tempDir.resolve("settings.properties"));
        MutableClock clock = new MutableClock(Instant.parse("2026-11-26T10:00:00Z"));

        try (BatteryHistoryStore store = new BatteryHistoryStore(historyDir, settings, null, clock)) {
            DeviceReading akko80 = DeviceReading.realConnected(
                    "akko-verge-s9", "Akko Verge S9", DeviceType.MOUSE, 80, false, "2.4 GHz", "OK");

            // Primeira leitura: entra em memória, mas NÃO escreve no disco imediatamente
            store.recordReadings(List.of(akko80));
            assertFalse(Files.exists(store.getStorageFile(false)));
            assertEquals(1, store.getSamples("akko-verge-s9", false).size());

            // 15s depois com mesmo percentual e estado -> ignorada pelo downsampling
            clock.advanceMillis(15_000L);
            store.recordReadings(List.of(akko80));
            assertEquals(1, store.getSamples("akko-verge-s9", false).size());
            assertFalse(Files.exists(store.getStorageFile(false)));

            // 30s depois com percentual alterado (79%) -> registrada em memória, ainda sem flush (< 60s)
            clock.advanceMillis(15_000L);
            DeviceReading akko79 = DeviceReading.realConnected(
                    "akko-verge-s9", "Akko Verge S9", DeviceType.MOUSE, 79, false, "2.4 GHz", "OK");
            store.recordReadings(List.of(akko79));
            assertEquals(2, store.getSamples("akko-verge-s9", false).size());
            assertFalse(Files.exists(store.getStorageFile(false)));

            // Passa de 60s desde o último flush e muda estado de carga -> dispara auto-flush
            clock.advanceMillis(35_000L);
            DeviceReading akko79Charging = DeviceReading.realConnected(
                    "akko-verge-s9", "Akko Verge S9", DeviceType.MOUSE, 79, true, "Cabo USB", "OK");
            store.recordReadings(List.of(akko79Charging));
            assertEquals(3, store.getSamples("akko-verge-s9", false).size());
            assertTrue(Files.isRegularFile(store.getStorageFile(false)));

            // Após 15 minutos com os mesmos dados -> registra heartbeat
            clock.advanceMinutes(15);
            store.recordReadings(List.of(akko79Charging));
            assertEquals(4, store.getSamples("akko-verge-s9", false).size());
        }

        // Recarrega do disco
        try (BatteryHistoryStore reloaded = new BatteryHistoryStore(historyDir, settings, null, clock)) {
            assertEquals(4, reloaded.getSamples("akko-verge-s9", false).size());
        }
    }

    @Test
    void prunesExpiredSamplesAndComputesStatsAndExportsCsv(@TempDir Path tempDir) throws Exception {
        Path historyDir = tempDir.resolve("history");
        AppSettings settings = new AppSettings(tempDir.resolve("settings.properties"));
        settings.setHistoryRetentionDays(7);
        MutableClock clock = new MutableClock(Instant.parse("2026-11-01T10:00:00Z"));

        try (BatteryHistoryStore store = new BatteryHistoryStore(historyDir, settings, null, clock)) {
            store.recordReadings(List.of(
                    DeviceReading.realConnected("akko-verge-s9", "Akko, \"Pro\"", DeviceType.MOUSE, 100, true, "USB", "OK")
            ));

            clock.advanceDays(9); // Agora amostra anterior tem 9 dias (> 7 dias de retenção)
            store.recordReadings(List.of(
                    DeviceReading.realConnected("akko-verge-s9", "Akko, \"Pro\"", DeviceType.MOUSE, 85, false, "2.4 GHz", "OK")
            ));
            clock.advanceMinutes(20);
            store.recordReadings(List.of(
                    DeviceReading.realConnected("akko-verge-s9", "Akko, \"Pro\"", DeviceType.MOUSE, 100, true, "USB", "OK")
            ));
            store.flush();

            List<BatteryHistorySample> remaining = store.getSamples("akko-verge-s9", false);
            assertEquals(2, remaining.size());

            BatteryHistoryStats stats = store.computeStats("akko-verge-s9", false);
            assertEquals(2, stats.sampleCount());
            assertEquals(85, stats.minPercent());
            assertEquals(100, stats.maxPercent());
            assertTrue(stats.hasLastFullCharge());
            assertEquals(1, stats.chargingStarts().size());

            Path csvFile = tempDir.resolve("export/history.csv");
            store.exportToCsv(csvFile, "akko-verge-s9", false);
            String csv = Files.readString(csvFile, StandardCharsets.UTF_8);
            assertTrue(csv.startsWith("timestamp_iso,device_id,display_name,percent,charging,connection,source\n"));
            assertTrue(csv.contains("\"Akko, \"\"Pro\"\"\""));
            assertTrue(csv.contains(",real\n"));

            store.clearDeviceHistory("akko-verge-s9", false);
            assertTrue(store.getSamples("akko-verge-s9", false).isEmpty());
        }
    }

    @Test
    void autonomyEstimatorCalculatesValidSegmentAndBreaksOnChargingJumpOrGap() {
        long t0 = Instant.parse("2026-11-26T08:00:00Z").toEpochMilli();
        long min = 60_000L;

        // Segmento válido: 90% -> 80% -> 70% -> 60% em 120 min (15.0 %/h, restante 240 min)
        List<BatteryHistorySample> samples = List.of(
                new BatteryHistorySample(t0, "akko-verge-s9", "Akko", 90, false, "2.4 GHz", false),
                new BatteryHistorySample(t0 + 40 * min, "akko-verge-s9", "Akko", 80, false, "2.4 GHz", false),
                new BatteryHistorySample(t0 + 80 * min, "akko-verge-s9", "Akko", 70, false, "2.4 GHz", false),
                new BatteryHistorySample(t0 + 120 * min, "akko-verge-s9", "Akko", 60, false, "2.4 GHz", false)
        );
        AutonomyEstimate est = AutonomyEstimator.estimate(samples);
        assertTrue(est.sufficientData());
        assertEquals(15.0, est.drainPercentPerHour(), 0.01);
        assertEquals(240L, est.estimatedRemainingMinutes());
        assertEquals(60, est.currentPercent());
        assertEquals(4, est.sampleCountUsed());

        // Segmento quebrado por recarga no meio deixa apenas 2 amostras recentes -> insuficiente
        List<BatteryHistorySample> brokenByCharging = List.of(
                new BatteryHistorySample(t0, "akko-verge-s9", "Akko", 90, false, "2.4 GHz", false),
                new BatteryHistorySample(t0 + 30 * min, "akko-verge-s9", "Akko", 80, false, "2.4 GHz", false),
                new BatteryHistorySample(t0 + 60 * min, "akko-verge-s9", "Akko", 95, true, "USB", false),
                new BatteryHistorySample(t0 + 90 * min, "akko-verge-s9", "Akko", 92, false, "2.4 GHz", false),
                new BatteryHistorySample(t0 + 120 * min, "akko-verge-s9", "Akko", 88, false, "2.4 GHz", false)
        );
        assertFalse(AutonomyEstimator.estimate(brokenByCharging).sufficientData());

        // Salto positivo > +3% quebra segmento
        List<BatteryHistorySample> brokenByJump = List.of(
                new BatteryHistorySample(t0, "akko-verge-s9", "Akko", 50, false, "2.4 GHz", false),
                new BatteryHistorySample(t0 + 30 * min, "akko-verge-s9", "Akko", 45, false, "2.4 GHz", false),
                new BatteryHistorySample(t0 + 60 * min, "akko-verge-s9", "Akko", 80, false, "2.4 GHz", false),
                new BatteryHistorySample(t0 + 90 * min, "akko-verge-s9", "Akko", 75, false, "2.4 GHz", false)
        );
        assertFalse(AutonomyEstimator.estimate(brokenByJump).sufficientData());

        // Gap > 2 horas quebra segmento
        List<BatteryHistorySample> brokenByGap = List.of(
                new BatteryHistorySample(t0, "akko-verge-s9", "Akko", 90, false, "2.4 GHz", false),
                new BatteryHistorySample(t0 + 150 * min, "akko-verge-s9", "Akko", 70, false, "2.4 GHz", false),
                new BatteryHistorySample(t0 + 180 * min, "akko-verge-s9", "Akko", 65, false, "2.4 GHz", false)
        );
        assertFalse(AutonomyEstimator.estimate(brokenByGap).sufficientData());

        // Amostras simuladas são ignoradas
        List<BatteryHistorySample> simOnly = new ArrayList<>();
        for (BatteryHistorySample s : samples) {
            simOnly.add(new BatteryHistorySample(
                    s.epochMillis(), s.deviceId(), s.displayName(), s.percent(), s.charging(), s.connectionLabel(), true));
        }
        assertFalse(AutonomyEstimator.estimate(simOnly).sufficientData());
    }
}
