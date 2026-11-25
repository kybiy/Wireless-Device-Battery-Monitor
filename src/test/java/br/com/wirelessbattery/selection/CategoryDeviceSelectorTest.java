package br.com.wirelessbattery.selection;

import static org.junit.jupiter.api.Assertions.*;

import br.com.wirelessbattery.audio.AudioEndpointInfo;
import br.com.wirelessbattery.audio.DefaultAudioOutputProvider;
import br.com.wirelessbattery.audio.WindowsAudioOutputInspector;
import br.com.wirelessbattery.config.AppSettings;
import br.com.wirelessbattery.diagnostics.AppLogger;
import br.com.wirelessbattery.model.ConnectionStatus;
import br.com.wirelessbattery.model.DataSourceType;
import br.com.wirelessbattery.model.DeviceReading;
import br.com.wirelessbattery.model.DeviceType;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CategoryDeviceSelectorTest {

    private static final String AKKO_ID = CategoryDeviceSelector.AKKO_DEVICE_ID;
    private static final String QCY_ID = CategoryDeviceSelector.QCY_DEVICE_ID;
    private static final String DELUX_ID = "delux-m800-pro";
    private static final String CYCLONE_ID = "gamesir-cyclone-2";

    private static AppSettings newSettings(Path tempDir, boolean showDisconnected) {
        AppSettings settings = new AppSettings(tempDir.resolve("settings.properties"));
        settings.setShowDisconnectedDevices(showDisconnected);
        return settings;
    }

    private static DeviceReading akkoConnected(int percent) {
        return DeviceReading.realConnected(
                AKKO_ID, "Akko S9 Ultra", DeviceType.HEADSET, percent, false, "2.4 GHz", "OK"
        );
    }

    private static DeviceReading akkoAsleep(Integer lastKnownPercent) {
        DeviceReading base = DeviceReading.realAsleep(
                AKKO_ID, "Akko S9 Ultra", DeviceType.HEADSET, "2.4 GHz", "Headset em repouso"
        );
        return lastKnownPercent != null
                ? base.withLastKnown(lastKnownPercent, LocalTime.of(10, 0))
                : base;
    }

    private static DeviceReading akkoNoResponse(Integer lastKnownPercent) {
        DeviceReading base = DeviceReading.realNoResponse(
                AKKO_ID, "Akko S9 Ultra", DeviceType.HEADSET, "2.4 GHz", "Receptor sem resposta"
        );
        return lastKnownPercent != null
                ? base.withLastKnown(lastKnownPercent, LocalTime.of(10, 0))
                : base;
    }

    private static DeviceReading akkoTimeout() {
        return DeviceReading.realTimeout(
                AKKO_ID, "Akko S9 Ultra", DeviceType.HEADSET, "2.4 GHz", "Timeout 1000ms"
        );
    }

    private static DeviceReading akkoDisconnected(Integer lastKnownPercent) {
        DeviceReading base = DeviceReading.realDisconnected(
                AKKO_ID, "Akko S9 Ultra", DeviceType.HEADSET, "2.4 GHz", "Desconectado"
        );
        return lastKnownPercent != null
                ? base.withLastKnown(lastKnownPercent, LocalTime.of(10, 0))
                : base;
    }

    private static DeviceReading qcyConnected(int percent) {
        return DeviceReading.realConnected(
                QCY_ID, "QCY T13", DeviceType.HEADSET, percent, false, "Bluetooth", "OK"
        );
    }

    private static DeviceReading qcyBatteryUnavailable() {
        return DeviceReading.realBatteryUnavailable(
                QCY_ID, "QCY T13", DeviceType.HEADSET, "Bluetooth", "Bateria Bluetooth indisponível"
        );
    }

    private static DeviceReading qcyDisconnected() {
        return DeviceReading.realDisconnected(
                QCY_ID, "QCY T13", DeviceType.HEADSET, "Bluetooth", "Desconectado"
        );
    }

    private static DeviceReading deluxConnected(int percent) {
        return DeviceReading.realConnected(
                DELUX_ID, "Delux M800 Pro", DeviceType.MOUSE, percent, false, "2.4 GHz", "OK"
        );
    }

    private static DeviceReading deluxAsleep(int lastKnownPercent) {
        return DeviceReading.realAsleep(
                DELUX_ID, "Delux M800 Pro", DeviceType.MOUSE, "2.4 GHz", "Mouse em repouso"
        ).withLastKnown(lastKnownPercent, LocalTime.of(9, 45));
    }

    private static DeviceReading deluxDisconnected() {
        return DeviceReading.realDisconnected(
                DELUX_ID, "Delux M800 Pro", DeviceType.MOUSE, "2.4 GHz", "Desconectado"
        );
    }

    private static DeviceReading cycloneDisconnected() {
        return DeviceReading.realDisconnected(
                CYCLONE_ID, "GameSir Cyclone 2", DeviceType.GAMEPAD, "2.4 GHz / USB", "Desconectado"
        );
    }

    private static DeviceReading findByType(List<DeviceReading> readings, DeviceType type) {
        return readings.stream()
                .filter(r -> r.deviceType() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Categoria ausente: " + type));
    }

    @Test
    void onlyAkkoConnectedDisplaysNormalizedName24GhzAndPercent(@TempDir Path tempDir) {
        CategoryDeviceSelector selector = new CategoryDeviceSelector(null, new AppLogger(tempDir.resolve("app.log")));
        AppSettings settings = newSettings(tempDir, false);

        List<DeviceReading> displayed = selector.selectForDisplay(
                List.of(deluxDisconnected(), akkoConnected(76), qcyDisconnected(), cycloneDisconnected()),
                settings,
                false
        );

        assertEquals(3, displayed.size());
        assertEquals(DeviceType.MOUSE, displayed.get(0).deviceType());
        assertEquals(DeviceType.HEADSET, displayed.get(1).deviceType());
        assertEquals(DeviceType.GAMEPAD, displayed.get(2).deviceType());

        DeviceReading headset = findByType(displayed, DeviceType.HEADSET);
        assertEquals(AKKO_ID, headset.deviceId());
        assertEquals("Akko Verge S9 Ultra", headset.displayName());
        assertEquals("2.4 GHz", headset.connectionLabel());
        assertEquals(76, headset.batteryPercent());
        assertEquals(ConnectionStatus.CONNECTED, headset.status());
    }

    @Test
    void onlyQcyConnectedDisplaysQcyBluetoothAndPercent(@TempDir Path tempDir) {
        CategoryDeviceSelector selector = new CategoryDeviceSelector(null, new AppLogger(tempDir.resolve("app.log")));
        AppSettings settings = newSettings(tempDir, false);

        List<DeviceReading> displayed = selector.selectForDisplay(
                List.of(deluxDisconnected(), akkoDisconnected(80), qcyConnected(54), cycloneDisconnected()),
                settings,
                false
        );

        assertEquals(3, displayed.size());
        DeviceReading headset = findByType(displayed, DeviceType.HEADSET);
        assertEquals(QCY_ID, headset.deviceId());
        assertEquals("QCY T13", headset.displayName());
        assertEquals("Bluetooth", headset.connectionLabel());
        assertEquals(54, headset.batteryPercent());
    }

    @Test
    void bothConnectedWithAkkoAsDefaultAudioSelectsAkko(@TempDir Path tempDir) {
        DefaultAudioOutputProvider audio = () -> Optional.of(
                new AudioEndpointInfo("ep-akko", "Fones de Ouvido (Akko S9 Ultra)", "Akko S9 Ultra", "{11111111-1111-1111-1111-111111111111}")
        );
        CategoryDeviceSelector selector = new CategoryDeviceSelector(audio, new AppLogger(tempDir.resolve("app.log")));
        AppSettings settings = newSettings(tempDir, false);

        List<DeviceReading> displayed = selector.selectForDisplay(
                List.of(qcyConnected(65), akkoConnected(82)),
                settings,
                false
        );

        DeviceReading headset = findByType(displayed, DeviceType.HEADSET);
        assertEquals(AKKO_ID, headset.deviceId());
        assertEquals("Akko Verge S9 Ultra", headset.displayName());
        assertEquals(82, headset.batteryPercent());
    }

    @Test
    void bothConnectedWithQcyDefaultAudioMatchesByNameAndByContainerId(@TempDir Path tempDir) {
        AtomicReference<Optional<AudioEndpointInfo>> endpointRef = new AtomicReference<>();
        CategoryDeviceSelector selector = new CategoryDeviceSelector(
                endpointRef::get, new AppLogger(tempDir.resolve("app.log"))
        );
        AppSettings settings = newSettings(tempDir, false);

        // 1. Correspondência por nome (friendlyName / interfaceName)
        endpointRef.set(Optional.of(
                new AudioEndpointInfo("ep-qcy", "Headphones (QCY-T13 Stereo)", "QCY-T13", null)
        ));
        List<DeviceReading> byName = selector.selectForDisplay(
                List.of(akkoConnected(88), qcyConnected(41)),
                settings,
                false
        );
        assertEquals(QCY_ID, findByType(byName, DeviceType.HEADSET).deviceId());
        assertEquals(41, findByType(byName, DeviceType.HEADSET).batteryPercent());

        // 2. Correspondência por ContainerId case-insensitive mesmo quando friendlyName foi renomeado
        endpointRef.set(Optional.of(
                new AudioEndpointInfo("ep-renamed", "Meu Fone Renomeado", "Bluetooth Audio", "{AABBCCDD-1122-3344-5566-778899001122}")
        ));
        List<DeviceReading> byContainer = selector.selectForDisplay(
                List.of(akkoConnected(88), qcyConnected(43)),
                settings,
                false,
                true,
                Map.of(
                        AKKO_ID, "{99999999-1111-2222-3333-444444444444}",
                        QCY_ID, "aabbccdd-1122-3344-5566-778899001122"
                )
        );
        DeviceReading headset = findByType(byContainer, DeviceType.HEADSET);
        assertEquals(QCY_ID, headset.deviceId());
        assertEquals(43, headset.batteryPercent());
    }

    @Test
    void bothConnectedWithUnrelatedDefaultAudioPreservesPreviousOrUsesDeterministicPriority(@TempDir Path tempDir) {
        AtomicReference<Optional<AudioEndpointInfo>> endpointRef = new AtomicReference<>(
                Optional.of(new AudioEndpointInfo("ep-spk", "Speakers (Realtek High Definition Audio)", "Realtek Audio", "{22222222-2222-2222-2222-222222222222}"))
        );
        CategoryDeviceSelector selector = new CategoryDeviceSelector(
                endpointRef::get, new AppLogger(tempDir.resolve("app.log"))
        );
        AppSettings settings = newSettings(tempDir, false);

        // Sem seleção anterior e áudio não relacionado -> prioridade determinística escolhe Akko (mesmo com QCY antes na lista)
        List<DeviceReading> first = selector.selectForDisplay(
                List.of(qcyConnected(70), akkoConnected(91)),
                settings,
                false
        );
        assertEquals(AKKO_ID, findByType(first, DeviceType.HEADSET).deviceId());

        // Troca saída de áudio para QCY -> seleciona QCY
        endpointRef.set(Optional.of(
                new AudioEndpointInfo("ep-qcy", "Headset (QCY T13)", "QCY T13", null)
        ));
        List<DeviceReading> second = selector.selectForDisplay(
                List.of(qcyConnected(70), akkoConnected(91)),
                settings,
                false
        );
        assertEquals(QCY_ID, findByType(second, DeviceType.HEADSET).deviceId());

        // Volta saída de áudio para Realtek Speakers -> preserva seleção anterior (QCY)
        endpointRef.set(Optional.of(
                new AudioEndpointInfo("ep-spk", "Realtek Speakers", "Realtek", null)
        ));
        List<DeviceReading> third = selector.selectForDisplay(
                List.of(qcyConnected(69), akkoConnected(90)),
                settings,
                false
        );
        assertEquals(QCY_ID, findByType(third, DeviceType.HEADSET).deviceId());
    }

    @Test
    void neitherConnectedDisplaysGenericEmptyFoneAndReturnsEmptyWhenNothingDetected(@TempDir Path tempDir) {
        CategoryDeviceSelector selector = new CategoryDeviceSelector(null, new AppLogger(tempDir.resolve("app.log")));

        // Sem nenhum hardware detectado e showDisconnected=false -> preserva empty state da MainWindow
        AppSettings hideDisconnected = newSettings(tempDir, false);
        List<DeviceReading> emptyResult = selector.selectForDisplay(
                List.of(deluxDisconnected(), akkoDisconnected(80), qcyDisconnected(), cycloneDisconnected()),
                hideDisconnected,
                false
        );
        assertTrue(emptyResult.isEmpty());

        // Com outro dispositivo presente (Delux conectado), o slot Fone aparece genérico desconectado sem vazar lastKnownPercent
        List<DeviceReading> withMouse = selector.selectForDisplay(
                List.of(deluxConnected(85), akkoDisconnected(80), qcyDisconnected(), cycloneDisconnected()),
                hideDisconnected,
                false
        );
        assertEquals(3, withMouse.size());
        DeviceReading headset = findByType(withMouse, DeviceType.HEADSET);
        assertEquals(CategoryDeviceSelector.SLOT_HEADSET_ID, headset.deviceId());
        assertEquals("Fone", headset.displayName());
        assertEquals(ConnectionStatus.DISCONNECTED, headset.status());
        assertEquals("Sem conexão", headset.connectionLabel());
        assertNull(headset.batteryPercent());
        assertNull(headset.lastKnownPercent());
        assertNull(headset.lastKnownAt());

        // Com showDisconnected=true e nenhum fone conectado -> também exibe "Fone" genérico sem bateria/lastKnown
        AppSettings showDisconnected = newSettings(tempDir, true);
        List<DeviceReading> shownAll = selector.selectForDisplay(
                List.of(deluxDisconnected(), akkoDisconnected(80), qcyDisconnected(), cycloneDisconnected()),
                showDisconnected,
                false
        );
        assertEquals(3, shownAll.size());
        DeviceReading headsetShown = findByType(shownAll, DeviceType.HEADSET);
        assertEquals("Fone", headsetShown.displayName());
        assertEquals(ConnectionStatus.DISCONNECTED, headsetShown.status());
        assertNull(headsetShown.batteryPercent());
        assertNull(headsetShown.lastKnownPercent());
    }

    @Test
    void akkoDonglePresentAsleepOrNoResponseWithHeadsetOffDisplaysFoneAndSwitchesImmediatelyToQcy(@TempDir Path tempDir) {
        CategoryDeviceSelector selector = new CategoryDeviceSelector(null, new AppLogger(tempDir.resolve("app.log")));
        AppSettings settings = newSettings(tempDir, false);

        // Receptor Akko em ASLEEP + QCY desconectado -> exibe Fone desconectado (sem lastKnownPercent do Akko)
        List<DeviceReading> asleepCycle = selector.selectForDisplay(
                List.of(deluxDisconnected(), akkoAsleep(77), qcyDisconnected(), cycloneDisconnected()),
                settings,
                false
        );
        assertEquals(3, asleepCycle.size());
        DeviceReading foneAsleep = findByType(asleepCycle, DeviceType.HEADSET);
        assertEquals("Fone", foneAsleep.displayName());
        assertEquals(ConnectionStatus.DISCONNECTED, foneAsleep.status());
        assertNull(foneAsleep.batteryPercent());
        assertNull(foneAsleep.lastKnownPercent());
        assertTrue(foneAsleep.detailMessage().contains("Akko"));
        assertEquals(
                Optional.of(EffectiveConnectivity.RECEIVER_ONLY_OFF),
                selector.getLastEffectiveConnectivity(AKKO_ID, false)
        );

        // Receptor Akko em NO_RESPONSE sem conexão anterior -> também é RECEIVER_ONLY_OFF e mostra Fone
        List<DeviceReading> noRespCycle = selector.selectForDisplay(
                List.of(deluxDisconnected(), akkoNoResponse(77), qcyDisconnected(), cycloneDisconnected()),
                settings,
                false
        );
        DeviceReading foneNoResp = findByType(noRespCycle, DeviceType.HEADSET);
        assertEquals("Fone", foneNoResp.displayName());
        assertEquals(ConnectionStatus.DISCONNECTED, foneNoResp.status());
        assertNull(foneNoResp.lastKnownPercent());

        // QCY conecta -> troca imediatamente para QCY T13
        List<DeviceReading> qcyConnectedCycle = selector.selectForDisplay(
                List.of(deluxDisconnected(), akkoAsleep(77), qcyConnected(64), cycloneDisconnected()),
                settings,
                false
        );
        DeviceReading qcySelected = findByType(qcyConnectedCycle, DeviceType.HEADSET);
        assertEquals(QCY_ID, qcySelected.deviceId());
        assertEquals("QCY T13", qcySelected.displayName());
        assertEquals("Bluetooth", qcySelected.connectionLabel());
        assertEquals(64, qcySelected.batteryPercent());
    }

    @Test
    void qcyConnectedWithBatteryUnavailableIsStillSelected(@TempDir Path tempDir) {
        CategoryDeviceSelector selector = new CategoryDeviceSelector(null, new AppLogger(tempDir.resolve("app.log")));
        AppSettings settings = newSettings(tempDir, false);

        List<DeviceReading> displayed = selector.selectForDisplay(
                List.of(akkoAsleep(80), qcyBatteryUnavailable()),
                settings,
                false
        );

        assertEquals(3, displayed.size());
        DeviceReading headset = findByType(displayed, DeviceType.HEADSET);
        assertEquals(QCY_ID, headset.deviceId());
        assertEquals("QCY T13", headset.displayName());
        assertEquals("Bluetooth", headset.connectionLabel());
        assertEquals(ConnectionStatus.BATTERY_UNAVAILABLE, headset.status());
        assertNull(headset.batteryPercent());
        assertEquals(
                Optional.of(EffectiveConnectivity.CONNECTED_BATTERY_UNAVAILABLE),
                selector.getLastEffectiveConnectivity(QCY_ID, false)
        );

        // BATTERY_UNAVAILABLE não-Bluetooth (ex.: receptor 2.4 GHz sem coleção HID) não é conectado
        DeviceReading nonBtUnavailable = DeviceReading.realBatteryUnavailable(
                AKKO_ID, "Akko S9 Ultra", DeviceType.HEADSET, "2.4 GHz", "Sem coleção"
        );
        List<DeviceReading> onlyNonBt = selector.selectForDisplay(
                List.of(nonBtUnavailable, qcyDisconnected()),
                settings,
                false
        );
        assertEquals("Fone", findByType(onlyNonBt, DeviceType.HEADSET).displayName());
        assertEquals(
                Optional.of(EffectiveConnectivity.RECEIVER_ONLY_OFF),
                selector.getLastEffectiveConnectivity(AKKO_ID, false)
        );
    }

    @Test
    void transientReadFailureHoldsForTwoCyclesAndDropsOnThirdConsecutiveFailure(@TempDir Path tempDir) {
        DefaultAudioOutputProvider audio = () -> Optional.of(
                new AudioEndpointInfo("ep-spk", "Realtek Speakers", "Realtek", null)
        );
        CategoryDeviceSelector selector = new CategoryDeviceSelector(audio, new AppLogger(tempDir.resolve("app.log")));
        AppSettings settings = newSettings(tempDir, false);

        // Ciclo 0: Akko conectado a 79% (QCY conectado a 50% como backup, mas Akko selecionado)
        List<DeviceReading> c0 = selector.selectForDisplay(
                List.of(akkoConnected(79), qcyConnected(50)),
                settings,
                false
        );
        assertEquals(AKKO_ID, findByType(c0, DeviceType.HEADSET).deviceId());
        assertEquals("Akko Verge S9 Ultra", findByType(c0, DeviceType.HEADSET).displayName());

        // Ciclo 1: 1º TIMEOUT no Akko -> mantém Akko em TRANSIENT_FAILURE_HOLD (não oscila para QCY nem Fone)
        List<DeviceReading> c1 = selector.selectForDisplay(
                List.of(akkoTimeout(), qcyConnected(50)),
                settings,
                false
        );
        DeviceReading h1 = findByType(c1, DeviceType.HEADSET);
        assertEquals(AKKO_ID, h1.deviceId());
        assertEquals("Akko Verge S9 Ultra", h1.displayName());
        assertNull(h1.batteryPercent());
        assertEquals(79, h1.lastKnownPercent());
        assertNotNull(h1.lastKnownAt());
        assertEquals(
                Optional.of(EffectiveConnectivity.TRANSIENT_FAILURE_HOLD),
                selector.getLastEffectiveConnectivity(AKKO_ID, false)
        );

        // Ciclo 2: 2º TIMEOUT consecutivo no Akko -> ainda mantém Akko (MAX = 2)
        List<DeviceReading> c2 = selector.selectForDisplay(
                List.of(akkoTimeout(), qcyConnected(50)),
                settings,
                false
        );
        DeviceReading h2 = findByType(c2, DeviceType.HEADSET);
        assertEquals(AKKO_ID, h2.deviceId());
        assertEquals(79, h2.lastKnownPercent());
        assertEquals(
                Optional.of(EffectiveConnectivity.TRANSIENT_FAILURE_HOLD),
                selector.getLastEffectiveConnectivity(AKKO_ID, false)
        );

        // Ciclo 3: 3º TIMEOUT consecutivo no Akko -> descarta retenção e alterna limpo para QCY
        List<DeviceReading> c3 = selector.selectForDisplay(
                List.of(akkoTimeout(), qcyConnected(50)),
                settings,
                false
        );
        DeviceReading h3 = findByType(c3, DeviceType.HEADSET);
        assertEquals(QCY_ID, h3.deviceId());
        assertEquals("QCY T13", h3.displayName());
        assertEquals(50, h3.batteryPercent());
        assertEquals(
                Optional.of(EffectiveConnectivity.RECEIVER_ONLY_OFF),
                selector.getLastEffectiveConnectivity(AKKO_ID, false)
        );

        // Agora sem QCY conectado: Akko reconecta -> 1 ASLEEP deve descartar retenção imediatamente para "Fone"
        selector.selectForDisplay(List.of(akkoConnected(75), qcyDisconnected()), settings, false);
        List<DeviceReading> immediateAsleep = selector.selectForDisplay(
                List.of(akkoAsleep(75), qcyDisconnected()),
                settings,
                false
        );
        DeviceReading hAsleep = findByType(immediateAsleep, DeviceType.HEADSET);
        assertEquals("Fone", hAsleep.displayName());
        assertEquals(ConnectionStatus.DISCONNECTED, hAsleep.status());
        assertNull(hAsleep.lastKnownPercent());

        // Recuperação zera contador: CONNECTED(74) -> 2x READ_FAILED (segura) -> CONNECTED(73) -> 1x NO_RESPONSE (segura) -> DISCONNECTED (cai imediato)
        selector.selectForDisplay(List.of(akkoConnected(74), qcyDisconnected()), settings, false);
        DeviceReading rf1 = findByType(selector.selectForDisplay(
                List.of(DeviceReading.realReadFailed(AKKO_ID, "Akko S9 Ultra", DeviceType.HEADSET, "2.4 GHz", "Err1"), qcyDisconnected()),
                settings, false), DeviceType.HEADSET);
        assertEquals(AKKO_ID, rf1.deviceId());
        assertEquals(74, rf1.lastKnownPercent());

        DeviceReading rf2 = findByType(selector.selectForDisplay(
                List.of(DeviceReading.realReadFailed(AKKO_ID, "Akko S9 Ultra", DeviceType.HEADSET, "2.4 GHz", "Err2"), qcyDisconnected()),
                settings, false), DeviceType.HEADSET);
        assertEquals(AKKO_ID, rf2.deviceId());

        selector.selectForDisplay(List.of(akkoConnected(73), qcyDisconnected()), settings, false);
        DeviceReading nr1 = findByType(selector.selectForDisplay(
                List.of(akkoNoResponse(null), qcyDisconnected()),
                settings, false), DeviceType.HEADSET);
        assertEquals(AKKO_ID, nr1.deviceId());
        assertEquals(73, nr1.lastKnownPercent());
        assertEquals(
                Optional.of(EffectiveConnectivity.TRANSIENT_FAILURE_HOLD),
                selector.getLastEffectiveConnectivity(AKKO_ID, false)
        );

        DeviceReading immediateDisc = findByType(selector.selectForDisplay(
                List.of(deluxConnected(80), akkoDisconnected(73), qcyDisconnected()),
                settings, false), DeviceType.HEADSET);
        assertEquals("Fone", immediateDisc.displayName());
        assertNull(immediateDisc.lastKnownPercent());
        assertEquals(
                Optional.of(EffectiveConnectivity.DISCONNECTED),
                selector.getLastEffectiveConnectivity(AKKO_ID, false)
        );
    }

    @Test
    void switchingAkkoToQcyToFoneToQcyNeverLeaksAkkoLastKnownPercent(@TempDir Path tempDir) {
        CategoryDeviceSelector selector = new CategoryDeviceSelector(null, new AppLogger(tempDir.resolve("app.log")));
        AppSettings settings = newSettings(tempDir, false);

        // 1. Akko conectado em 92%
        List<DeviceReading> step1 = selector.selectForDisplay(
                List.of(deluxConnected(80), akkoConnected(92), qcyDisconnected()),
                settings,
                false
        );
        DeviceReading h1 = findByType(step1, DeviceType.HEADSET);
        assertEquals(AKKO_ID, h1.deviceId());
        assertEquals(92, h1.batteryPercent());

        // 2. Akko entra em repouso (lastKnown=92), QCY conecta sem bateria (BATTERY_UNAVAILABLE)
        List<DeviceReading> step2 = selector.selectForDisplay(
                List.of(deluxConnected(80), akkoAsleep(92), qcyBatteryUnavailable()),
                settings,
                false
        );
        DeviceReading h2 = findByType(step2, DeviceType.HEADSET);
        assertEquals(QCY_ID, h2.deviceId());
        assertNull(h2.batteryPercent());
        assertNull(h2.lastKnownPercent(), "Não deve vazar lastKnownPercent do Akko para o QCY");

        // 3. QCY desconecta -> slot genérico "Fone" sem lastKnownPercent
        List<DeviceReading> step3 = selector.selectForDisplay(
                List.of(deluxConnected(80), akkoAsleep(92), qcyDisconnected()),
                settings,
                false
        );
        DeviceReading h3 = findByType(step3, DeviceType.HEADSET);
        assertEquals("Fone", h3.displayName());
        assertEquals(ConnectionStatus.DISCONNECTED, h3.status());
        assertNull(h3.batteryPercent());
        assertNull(h3.lastKnownPercent(), "Não deve vazar lastKnownPercent do Akko para o slot Fone");

        // 4. QCY reconecta com BATTERY_UNAVAILABLE -> continua sem vazar lastKnownPercent do Akko
        List<DeviceReading> step4 = selector.selectForDisplay(
                List.of(deluxConnected(80), akkoAsleep(92), qcyBatteryUnavailable()),
                settings,
                false
        );
        DeviceReading h4 = findByType(step4, DeviceType.HEADSET);
        assertEquals(QCY_ID, h4.deviceId());
        assertNull(h4.batteryPercent());
        assertNull(h4.lastKnownPercent(), "Não deve vazar lastKnownPercent do Akko ao reconectar QCY");
    }

    @Test
    void mouseAsleepKeepsItsOwnLastKnownWhileHeadsetShowsGenericFone(@TempDir Path tempDir) {
        CategoryDeviceSelector selector = new CategoryDeviceSelector(null, new AppLogger(tempDir.resolve("app.log")));
        AppSettings settings = newSettings(tempDir, false);

        List<DeviceReading> displayed = selector.selectForDisplay(
                List.of(deluxAsleep(67), akkoAsleep(84), qcyDisconnected(), cycloneDisconnected()),
                settings,
                false
        );

        assertEquals(3, displayed.size());
        DeviceReading mouse = findByType(displayed, DeviceType.MOUSE);
        assertEquals(DELUX_ID, mouse.deviceId());
        assertEquals(ConnectionStatus.ASLEEP, mouse.status());
        assertEquals(67, mouse.lastKnownPercent());

        DeviceReading headset = findByType(displayed, DeviceType.HEADSET);
        assertEquals("Fone", headset.displayName());
        assertEquals(ConnectionStatus.DISCONNECTED, headset.status());
        assertNull(headset.lastKnownPercent());

        DeviceReading gamepad = findByType(displayed, DeviceType.GAMEPAD);
        assertEquals("Controle", gamepad.displayName());
        assertEquals(ConnectionStatus.DISCONNECTED, gamepad.status());
        assertNull(gamepad.lastKnownPercent());
    }

    @Test
    void simulationModeAlwaysReturnsThreeSlotsAndSharesSelectionAndHiddenRules(@TempDir Path tempDir) {
        DefaultAudioOutputProvider audio = () -> Optional.of(
                new AudioEndpointInfo("ep-qcy", "Fones (QCY T13)", "QCY T13", null)
        );
        AppLogger logger = new AppLogger(tempDir.resolve("sim.log"));
        CategoryDeviceSelector selector = new CategoryDeviceSelector(audio, logger);
        AppSettings settings = newSettings(tempDir, false);

        DeviceReading simDeluxOff = DeviceReading.simulated(
                "sim-delux-m800-pro", "Delux M800 Pro", DeviceType.MOUSE,
                85, false, false, "2.4 GHz", "Desconectado", 85, LocalTime.of(10, 0)
        );
        DeviceReading simAkkoOn = DeviceReading.simulated(
                "sim-akko-verge-s9", "Akko S9 Ultra", DeviceType.HEADSET,
                62, false, true, "2.4 GHz", "Simulação ativa", 62, LocalTime.of(10, 0)
        );
        DeviceReading simQcyOn = DeviceReading.simulated(
                "sim-qcy-t13", "QCY T13", DeviceType.HEADSET,
                45, false, true, "Bluetooth", "Simulação ativa", 45, LocalTime.of(10, 0)
        );
        DeviceReading simCycloneOff = DeviceReading.simulated(
                "sim-gamesir-cyclone-2", "GameSir Cyclone 2", DeviceType.GAMEPAD,
                90, false, false, "2.4 GHz / USB", "Desconectado", 90, LocalTime.of(10, 0)
        );

        // Com ambos headsets simulados ligados e áudio padrão em QCY -> seleciona sim-qcy-t13 e slots Mouse/Controle genéricos
        List<DeviceReading> sim1 = selector.selectForDisplay(
                List.of(simDeluxOff, simAkkoOn, simQcyOn, simCycloneOff),
                settings,
                true
        );
        assertEquals(3, sim1.size());
        assertEquals("Mouse", sim1.get(0).displayName());
        assertEquals(DataSourceType.SIMULATED, sim1.get(0).sourceType());
        assertEquals("sim-qcy-t13", sim1.get(1).deviceId());
        assertEquals(45, sim1.get(1).batteryPercent());
        assertEquals("Controle", sim1.get(2).displayName());

        // Ocultando QCY via settings -> seleciona sim-akko-verge-s9 normalizado como "Akko Verge S9 Ultra"
        settings.setDeviceHidden("sim-qcy-t13", true);
        List<DeviceReading> sim2 = selector.selectForDisplay(
                List.of(simDeluxOff, simAkkoOn, simQcyOn, simCycloneOff),
                settings,
                true
        );
        assertEquals("sim-akko-verge-s9", sim2.get(1).deviceId());
        assertEquals("Akko Verge S9 Ultra", sim2.get(1).displayName());
    }

    @Test
    void logsSelectionTransitionsOnlyWhenHeadsetChangesAndReportsDisabledBluetooth(@TempDir Path tempDir) {
        AppLogger logger = new AppLogger(tempDir.resolve("sel.log"));
        CategoryDeviceSelector selector = new CategoryDeviceSelector(null, logger);
        AppSettings settings = newSettings(tempDir, false);

        List<DeviceReading> btDown = selector.selectForDisplay(
                List.of(deluxConnected(70), akkoAsleep(80), qcyDisconnected()),
                settings,
                false,
                false,
                Map.of()
        );
        DeviceReading emptyHeadset = findByType(btDown, DeviceType.HEADSET);
        assertTrue(emptyHeadset.detailMessage().contains("Adaptador Bluetooth ausente ou desativado."));

        // Repete o mesmo estado -> não deve duplicar log [SELECAO]
        selector.selectForDisplay(
                List.of(deluxConnected(69), akkoAsleep(80), qcyDisconnected()),
                settings,
                false,
                false,
                Map.of()
        );

        long selectionLogsFirst = logger.getHistory().stream()
                .filter(line -> line.contains("[SELECAO]"))
                .count();
        assertEquals(1, selectionLogsFirst);

        // Muda para QCY -> gera novo log [SELECAO]
        selector.selectForDisplay(
                List.of(deluxConnected(69), akkoAsleep(80), qcyConnected(55)),
                settings,
                false,
                true,
                Map.of()
        );
        long selectionLogsAfterSwitch = logger.getHistory().stream()
                .filter(line -> line.contains("[SELECAO]"))
                .count();
        assertEquals(2, selectionLogsAfterSwitch);
    }

    @Test
    void windowsAudioOutputInspectorSmokeTestDoesNotThrowOrLeakCom() {
        WindowsAudioOutputInspector inspector = new WindowsAudioOutputInspector();
        Optional<AudioEndpointInfo> first = assertDoesNotThrow(inspector::getDefaultRenderEndpoint);
        Optional<AudioEndpointInfo> second = assertDoesNotThrow(inspector::getDefaultRenderEndpoint);
        assertEquals(first, second);
        first.ifPresent(ep -> {
            // Se houver endpoint de saída padrão no host Windows, deve ter pelo menos ID ou nome
            boolean hasAnyData = (ep.endpointId() != null && !ep.endpointId().isBlank())
                    || (ep.friendlyName() != null && !ep.friendlyName().isBlank())
                    || (ep.interfaceName() != null && !ep.interfaceName().isBlank());
            assertTrue(hasAnyData, "AudioEndpointInfo não deve estar completamente vazio quando presente");
            if (ep.containerId() != null) {
                assertTrue(ep.containerId().startsWith("{") && ep.containerId().endsWith("}"),
                        "ContainerId deve estar formatado como GUID: " + ep.containerId());
            }
        });
    }
}
