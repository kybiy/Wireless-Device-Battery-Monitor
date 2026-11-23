package br.com.wirelessbattery.device;

import br.com.wirelessbattery.diagnostics.AppLogger;
import br.com.wirelessbattery.model.DataSourceType;
import br.com.wirelessbattery.model.DeviceReading;
import br.com.wirelessbattery.model.DeviceType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.hid4java.HidDevice;

/**
 * Driver experimental para o controle GameSir Cyclone 2 via hid4java no Windows.
 *
 * Baseado diretamente em {@code PollGameSirCyclone} ({@code src/drivers/gamesir.cpp} do
 * repositório de referência). Opera em modo XInput via receptor 2.4 GHz ou cabo USB
 * na coleção HID {@code FFF0:0040} usando apenas a consulta segura de estado {@code 0x0F 0x03}.
 *
 * Permanece marcado como experimental até validação com o controle físico no Windows.
 */
public final class GameSirCyclone2 implements BatteryDevice {
    public static final String DEVICE_ID = "gamesir-cyclone-2";
    public static final String DISPLAY_NAME = "GameSir Cyclone 2";
    public static final String CONNECTION = "2.4 GHz / USB";

    private static final int SINGLE_READ_TIMEOUT_MS = 200;

    private final HidServiceProvider hidProvider;
    private final GameSirCyclone2Config config;
    private final AppLogger logger;

    public GameSirCyclone2(HidServiceProvider hidProvider, GameSirCyclone2Config config, AppLogger logger) {
        this.hidProvider = Objects.requireNonNull(hidProvider, "hidProvider não pode ser nulo");
        this.config = Objects.requireNonNull(config, "config não pode ser nulo");
        this.logger = Objects.requireNonNull(logger, "logger não pode ser nulo");
    }

    public GameSirCyclone2Config getConfig() {
        return config;
    }

    @Override
    public String getId() {
        return DEVICE_ID;
    }

    @Override
    public String getDisplayName() {
        return DISPLAY_NAME;
    }

    @Override
    public DeviceType getDeviceType() {
        return DeviceType.GAMEPAD;
    }

    @Override
    public String getConnectionDescription() {
        return CONNECTION;
    }

    @Override
    public DataSourceType getSourceType() {
        return DataSourceType.REAL;
    }

    @Override
    public boolean isOptional() {
        return false;
    }

    @Override
    public boolean supportsBatteryQuery() {
        return true;
    }

    @Override
    public boolean matchesVidPid(int vid, int pid) {
        return config.matchesAny(vid & 0xFFFF, pid & 0xFFFF);
    }

    @Override
    public DeviceReading readBattery() {
        try {
            return readBattery(hidProvider.getServices().getAttachedHidDevices());
        } catch (Exception e) {
            logger.error("Falha ao enumerar HID para GameSir Cyclone 2", e);
            return DeviceReading.realDisconnected(
                    DEVICE_ID,
                    DISPLAY_NAME,
                    DeviceType.GAMEPAD,
                    CONNECTION,
                    "Não foi possível enumerar dispositivos HID."
            );
        }
    }

    @Override
    public DeviceReading readBattery(List<HidDevice> attachedDevices) {
        if (attachedDevices == null || attachedDevices.isEmpty()) {
            return DeviceReading.realDisconnected(
                    DEVICE_ID,
                    DISPLAY_NAME,
                    DeviceType.GAMEPAD,
                    CONNECTION,
                    "Controle desconectado."
            );
        }

        List<HidDevice> activeCollections = new ArrayList<>();
        boolean activePidSeen = false;
        boolean idleReceiverSeen = false;

        for (HidDevice d : attachedDevices) {
            int vid = d.getVendorId() & 0xFFFF;
            int pid = d.getProductId() & 0xFFFF;
            if (config.matchesActive(vid, pid)) {
                activePidSeen = true;
                if (config.matchesCollection(vid, pid, d.getUsagePage(), d.getUsage())) {
                    activeCollections.add(d);
                }
            } else if (config.matchesIdleReceiver(vid, pid)) {
                idleReceiverSeen = true;
            }
        }

        if (activeCollections.isEmpty()) {
            if (idleReceiverSeen) {
                return DeviceReading.realAsleep(
                        DEVICE_ID,
                        DISPLAY_NAME,
                        DeviceType.GAMEPAD,
                        "2.4 GHz",
                        "Receptor 2.4 GHz conectado; controle desligado ou em repouso."
                );
            }
            if (activePidSeen) {
                return DeviceReading.realBatteryUnavailable(
                        DEVICE_ID,
                        DISPLAY_NAME,
                        DeviceType.GAMEPAD,
                        CONNECTION,
                        "Controle detectado sem coleção HID FFF0:0040 (verifique se está em modo XInput)."
                );
            }
            return DeviceReading.realDisconnected(
                    DEVICE_ID,
                    DISPLAY_NAME,
                    DeviceType.GAMEPAD,
                    CONNECTION,
                    "Controle / receptor não detectado."
            );
        }

        for (HidDevice candidate : activeCollections) {
            GameSirCyclone2Protocol.ParsedBattery parsed = queryCollection(candidate);
            if (parsed != null) {
                String connLabel = parsed.charging() ? "USB Cabo / Carregando" : "2.4 GHz / USB";
                String detail = "Leitura experimental via HID FFF0:0040 (aguardando confirmação em hardware real).";
                return DeviceReading.realConnected(
                        DEVICE_ID,
                        DISPLAY_NAME,
                        DeviceType.GAMEPAD,
                        parsed.percent(),
                        parsed.charging(),
                        connLabel,
                        detail
                );
            }
        }

        return DeviceReading.realAsleep(
                DEVICE_ID,
                DISPLAY_NAME,
                DeviceType.GAMEPAD,
                CONNECTION,
                "Coleção FFF0:0040 detectada, sem resposta de bateria no momento (em repouso ou acesso reservado)."
        );
    }

    private GameSirCyclone2Protocol.ParsedBattery queryCollection(HidDevice device) {
        boolean opened = false;
        try {
            opened = device.open();
            if (!opened) {
                return null;
            }

            // Descarta relatórios pendentes antigos na fila antes da consulta
            device.setNonBlocking(true);
            byte[] discard = new byte[GameSirCyclone2Protocol.FULL_REPORT_LENGTH];
            for (int k = 0; k < 16 && device.read(discard, 0) > 0; k++) {
                // flush
            }
            device.setNonBlocking(false);

            byte[] payload = GameSirCyclone2Protocol.buildWritePayload();
            int written = device.write(
                    payload,
                    GameSirCyclone2Protocol.WRITE_PAYLOAD_LENGTH,
                    GameSirCyclone2Protocol.OUT_REPORT_ID
            );
            if (written < 0) {
                return null;
            }

            long deadline = System.currentTimeMillis() + config.getReadTimeoutMs();
            byte[] buffer = new byte[GameSirCyclone2Protocol.FULL_REPORT_LENGTH];
            while (System.currentTimeMillis() < deadline) {
                int remaining = (int) Math.max(1L, Math.min(SINGLE_READ_TIMEOUT_MS, deadline - System.currentTimeMillis()));
                int read = device.read(buffer, remaining);
                if (read > 0) {
                    GameSirCyclone2Protocol.ParsedBattery parsed =
                            GameSirCyclone2Protocol.parseInputReport(buffer, read);
                    if (parsed != null) {
                        return parsed;
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Tentativa HID no GameSir Cyclone 2 (" + device.getPath() + ") falhou: " + e.getMessage());
        } finally {
            if (opened) {
                device.close();
            }
        }
        return null;
    }
}
