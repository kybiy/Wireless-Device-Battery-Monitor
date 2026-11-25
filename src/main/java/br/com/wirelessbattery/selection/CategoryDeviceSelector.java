package br.com.wirelessbattery.selection;

import br.com.wirelessbattery.audio.AudioEndpointInfo;
import br.com.wirelessbattery.audio.DefaultAudioOutputProvider;
import br.com.wirelessbattery.audio.WindowsAudioOutputInspector;
import br.com.wirelessbattery.config.AppSettings;
import br.com.wirelessbattery.diagnostics.AppLogger;
import br.com.wirelessbattery.model.ConnectionStatus;
import br.com.wirelessbattery.model.DataSourceType;
import br.com.wirelessbattery.model.DeviceReading;
import br.com.wirelessbattery.model.DeviceType;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Seletor centralizado e thread-safe de dispositivos por categoria ({@link DeviceType}),
 * compartilhando exatamente a mesma lógica entre os modos REAL e SIMULADO.
 *
 * Responsabilidades:
 * - Estabilizar falhas transitórias de leitura (ex.: {@code TIMEOUT}, {@code READ_FAILED},
 *   {@code NO_RESPONSE}) por até {@value #MAX_CONSECUTIVE_TRANSIENT_FAILURES} ciclos sem oscilar.
 * - Diferenciar receptor USB presente com headset desligado ({@code ASLEEP} / {@code NO_RESPONSE})
 *   de headset efetivamente conectado.
 * - Desempatar múltiplos headsets conectados (ex.: Akko Verge S9 Ultra vs. QCY T13) usando a
 *   saída de áudio padrão do Windows (ContainerId primeiro, depois nome amigável/interface),
 *   preservando a seleção anterior ou prioridade determinística quando não houver correspondência.
 * - Garantir exibição em 3 slots ({@code Mouse}, {@code Fone}, {@code Controle}) quando há algum
 *   hardware presente, {@code showDisconnectedDevices} ativo ou modo simulação ativo, sem vazar
 *   {@code lastKnownPercent} entre dispositivos distintos.
 */
public final class CategoryDeviceSelector {

    public static final int MAX_CONSECUTIVE_TRANSIENT_FAILURES = 2;

    public static final List<DeviceType> DISPLAY_CATEGORIES = List.of(
            DeviceType.MOUSE,
            DeviceType.HEADSET,
            DeviceType.GAMEPAD
    );

    public static final String AKKO_DEVICE_ID = "akko-verge-s9";
    public static final String SIM_AKKO_DEVICE_ID = "sim-akko-verge-s9";
    public static final String QCY_DEVICE_ID = "qcy-t13";
    public static final String SIM_QCY_DEVICE_ID = "sim-qcy-t13";

    public static final String AKKO_DISPLAY_NAME = "Akko Verge S9 Ultra";

    public static final String SLOT_MOUSE_ID = "slot-mouse-empty";
    public static final String SLOT_HEADSET_ID = "slot-headset-empty";
    public static final String SLOT_GAMEPAD_ID = "slot-gamepad-empty";
    public static final String SIM_SLOT_MOUSE_ID = "sim-slot-mouse-empty";
    public static final String SIM_SLOT_HEADSET_ID = "sim-slot-headset-empty";
    public static final String SIM_SLOT_GAMEPAD_ID = "sim-slot-gamepad-empty";

    private static final String ZERO_GUID = "00000000-0000-0000-0000-000000000000";

    private final DefaultAudioOutputProvider audioOutputProvider;
    private final AppLogger logger;
    private final ModeState realState = new ModeState();
    private final ModeState simState = new ModeState();

    public CategoryDeviceSelector() {
        this(new WindowsAudioOutputInspector(), null);
    }

    public CategoryDeviceSelector(AppLogger logger) {
        this(new WindowsAudioOutputInspector(), logger);
    }

    public CategoryDeviceSelector(DefaultAudioOutputProvider audioOutputProvider, AppLogger logger) {
        this.audioOutputProvider = audioOutputProvider;
        this.logger = logger;
    }

    public synchronized List<DeviceReading> selectForDisplay(
            List<DeviceReading> readings,
            AppSettings settings,
            boolean simMode
    ) {
        return selectForDisplay(readings, settings, simMode, true, Collections.emptyMap());
    }

    public synchronized List<DeviceReading> selectForDisplay(
            List<DeviceReading> readings,
            AppSettings settings,
            boolean simMode,
            Map<String, String> containerIdsByDeviceId
    ) {
        return selectForDisplay(readings, settings, simMode, true, containerIdsByDeviceId);
    }

    public synchronized List<DeviceReading> selectForDisplay(
            List<DeviceReading> readings,
            AppSettings settings,
            boolean simMode,
            boolean bluetoothAdapterAvailable,
            Map<String, String> containerIdsByDeviceId
    ) {
        return selectForDisplay(
                readings,
                settings,
                simMode,
                bluetoothAdapterAvailable,
                containerIdsByDeviceId,
                this.audioOutputProvider
        );
    }

    public synchronized List<DeviceReading> selectForDisplay(
            List<DeviceReading> readings,
            AppSettings settings,
            boolean simMode,
            boolean bluetoothAdapterAvailable,
            Map<String, String> containerIdsByDeviceId,
            DefaultAudioOutputProvider effectiveAudioOutputProvider
    ) {
        ModeState state = modeState(simMode);
        List<DeviceReading> safeReadings = readings != null ? readings : List.of();
        Map<String, String> safeContainerIds = containerIdsByDeviceId != null
                ? containerIdsByDeviceId
                : Collections.emptyMap();
        boolean showDisconnected = settings != null && settings.isShowDisconnectedDevices();

        Map<DeviceType, List<EvaluatedCandidate>> byCategory = new EnumMap<>(DeviceType.class);
        for (DeviceType category : DISPLAY_CATEGORIES) {
            byCategory.put(category, new ArrayList<>());
        }

        Set<String> activeIds = new HashSet<>();
        for (DeviceReading raw : safeReadings) {
            if (raw == null || raw.deviceType() == null || !byCategory.containsKey(raw.deviceType())) {
                continue;
            }
            if (settings != null && settings.isDeviceHidden(raw.deviceId())) {
                continue;
            }
            activeIds.add(raw.deviceId());
            EvaluatedCandidate evaluated = evaluateCandidate(state, raw);
            byCategory.get(raw.deviceType()).add(evaluated);
        }

        state.deviceStates.keySet().retainAll(activeIds);

        if (!simMode && !showDisconnected && !anyCategoryHasPresentHardware(byCategory)) {
            for (DeviceType category : DISPLAY_CATEGORIES) {
                state.lastSelectedByCategory.remove(category);
            }
            state.lastLoggedHeadsetDeviceId = null;
            return List.of();
        }

        List<DeviceReading> result = new ArrayList<>(DISPLAY_CATEGORIES.size());
        for (DeviceType category : DISPLAY_CATEGORIES) {
            List<EvaluatedCandidate> candidates = byCategory.get(category);
            SelectionResult selected = selectCategorySlot(
                    state,
                    category,
                    candidates,
                    simMode,
                    showDisconnected,
                    bluetoothAdapterAvailable,
                    safeContainerIds,
                    effectiveAudioOutputProvider
            );
            result.add(selected.reading());

            if (category == DeviceType.HEADSET) {
                logHeadsetSelectionIfChanged(state, selected, simMode);
            }
        }
        return Collections.unmodifiableList(result);
    }

    public synchronized Optional<String> getLastSelectedDeviceId(DeviceType category, boolean simMode) {
        if (category == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(modeState(simMode).lastSelectedByCategory.get(category));
    }

    public synchronized Optional<EffectiveConnectivity> getLastEffectiveConnectivity(String deviceId, boolean simMode) {
        if (deviceId == null) {
            return Optional.empty();
        }
        DeviceTransientState ts = modeState(simMode).deviceStates.get(deviceId);
        return ts != null ? Optional.ofNullable(ts.lastConnectivity) : Optional.empty();
    }

    public synchronized void reset() {
        realState.clear();
        simState.clear();
    }

    public synchronized void reset(boolean simMode) {
        modeState(simMode).clear();
    }

    private ModeState modeState(boolean simMode) {
        return simMode ? simState : realState;
    }

    private EvaluatedCandidate evaluateCandidate(ModeState state, DeviceReading raw) {
        DeviceReading normalized = normalizeAkkoReading(raw);
        DeviceTransientState ts = state.deviceStates.computeIfAbsent(
                normalized.deviceId(), id -> new DeviceTransientState()
        );

        ConnectionStatus status = normalized.status();
        EffectiveConnectivity connectivity;
        DeviceReading effectiveReading;

        if (status.isOnline() && normalized.hasBatteryPercent()) {
            connectivity = EffectiveConnectivity.CONNECTED_WITH_BATTERY;
            ts.consecutiveTransientFailures = 0;
            Integer knownPct = normalized.lastKnownPercent() != null
                    ? normalized.lastKnownPercent()
                    : normalized.batteryPercent();
            LocalTime knownAt = normalized.lastKnownAt() != null
                    ? normalized.lastKnownAt()
                    : normalized.updatedAt();
            effectiveReading = normalized.withLastKnown(knownPct, knownAt);
            ts.lastConnectedReading = effectiveReading;
        } else if (status == ConnectionStatus.BATTERY_UNAVAILABLE
                && "Bluetooth".equalsIgnoreCase(normalized.connectionLabel())) {
            connectivity = EffectiveConnectivity.CONNECTED_BATTERY_UNAVAILABLE;
            ts.consecutiveTransientFailures = 0;
            effectiveReading = normalized;
            ts.lastConnectedReading = effectiveReading;
        } else if (ts.lastConnectedReading != null && isTransientFailureStatus(status)) {
            ts.consecutiveTransientFailures++;
            if (ts.consecutiveTransientFailures <= MAX_CONSECUTIVE_TRANSIENT_FAILURES) {
                connectivity = EffectiveConnectivity.TRANSIENT_FAILURE_HOLD;
                Integer preservedPct = normalized.lastKnownPercent() != null
                        ? normalized.lastKnownPercent()
                        : ts.lastConnectedReading.lastKnownPercent();
                LocalTime preservedAt = normalized.lastKnownAt() != null
                        ? normalized.lastKnownAt()
                        : (ts.lastConnectedReading.lastKnownAt() != null
                                ? ts.lastConnectedReading.lastKnownAt()
                                : ts.lastConnectedReading.updatedAt());
                effectiveReading = normalized.withLastKnown(preservedPct, preservedAt);
            } else {
                ts.lastConnectedReading = null;
                connectivity = EffectiveConnectivity.RECEIVER_ONLY_OFF;
                effectiveReading = normalized;
            }
        } else {
            ts.consecutiveTransientFailures = 0;
            ts.lastConnectedReading = null;
            if (isReceiverOnlyOffStatus(status)) {
                connectivity = EffectiveConnectivity.RECEIVER_ONLY_OFF;
            } else {
                connectivity = EffectiveConnectivity.DISCONNECTED;
            }
            effectiveReading = normalized;
        }

        ts.lastConnectivity = connectivity;
        return new EvaluatedCandidate(effectiveReading, connectivity);
    }

    private static boolean isTransientFailureStatus(ConnectionStatus status) {
        return status == ConnectionStatus.TIMEOUT
                || status == ConnectionStatus.READ_FAILED
                || status == ConnectionStatus.NO_RESPONSE;
    }

    private static boolean isReceiverOnlyOffStatus(ConnectionStatus status) {
        return status == ConnectionStatus.ASLEEP
                || status == ConnectionStatus.NO_RESPONSE
                || status == ConnectionStatus.OPEN_FAILED
                || status == ConnectionStatus.READ_FAILED
                || status == ConnectionStatus.TIMEOUT
                || status == ConnectionStatus.PENDING_PROTOCOL
                || status == ConnectionStatus.BATTERY_UNAVAILABLE;
    }

    private static boolean anyCategoryHasPresentHardware(Map<DeviceType, List<EvaluatedCandidate>> byCategory) {
        for (List<EvaluatedCandidate> candidates : byCategory.values()) {
            for (EvaluatedCandidate candidate : candidates) {
                if (candidate.connectivity().isEffectivelyConnected()
                        || candidate.reading().status().isReceiverDetectedState()) {
                    return true;
                }
            }
        }
        return false;
    }

    private SelectionResult selectCategorySlot(
            ModeState state,
            DeviceType category,
            List<EvaluatedCandidate> candidates,
            boolean simMode,
            boolean showDisconnected,
            boolean bluetoothAdapterAvailable,
            Map<String, String> containerIdsByDeviceId,
            DefaultAudioOutputProvider effectiveAudioOutputProvider
    ) {
        List<EvaluatedCandidate> connected = new ArrayList<>();
        for (EvaluatedCandidate c : candidates) {
            if (c.connectivity().isEffectivelyConnected()) {
                connected.add(c);
            }
        }

        if (connected.size() == 1) {
            EvaluatedCandidate chosen = connected.get(0);
            state.lastSelectedByCategory.put(category, chosen.reading().deviceId());
            String reason = chosen.connectivity() == EffectiveConnectivity.TRANSIENT_FAILURE_HOLD
                    ? "retenção após falha transitória"
                    : "único dispositivo conectado";
            return new SelectionResult(chosen.reading(), reason);
        }

        if (connected.size() >= 2) {
            SelectionResult disambiguated = disambiguateConnected(
                    state, category, connected, containerIdsByDeviceId, effectiveAudioOutputProvider
            );
            state.lastSelectedByCategory.put(category, disambiguated.reading().deviceId());
            return disambiguated;
        }

        // 0 candidatos efetivamente conectados na categoria
        state.lastSelectedByCategory.remove(category);

        if (category == DeviceType.HEADSET) {
            DeviceReading emptyHeadset = buildEmptyHeadsetSlot(
                    candidates, simMode, bluetoothAdapterAvailable
            );
            return new SelectionResult(emptyHeadset, emptyHeadset.detailMessage());
        }

        // MOUSE e GAMEPAD: mantém o dispositivo único se o receptor estiver presente (ex.: ASLEEP),
        // ou se showDisconnected estiver ativo em modo REAL; caso contrário usa slot genérico.
        EvaluatedCandidate receiverPresentCandidate = findFirstReceiverDetectedCandidate(candidates);
        if (receiverPresentCandidate != null) {
            return new SelectionResult(receiverPresentCandidate.reading(), "receptor presente");
        }
        if (!simMode && showDisconnected && !candidates.isEmpty()) {
            return new SelectionResult(candidates.get(0).reading(), "exibir desconectados");
        }

        return new SelectionResult(buildEmptyGenericSlot(category, simMode), "nenhum dispositivo conectado");
    }

    private SelectionResult disambiguateConnected(
            ModeState state,
            DeviceType category,
            List<EvaluatedCandidate> connected,
            Map<String, String> containerIdsByDeviceId,
            DefaultAudioOutputProvider effectiveAudioOutputProvider
    ) {
        Optional<AudioEndpointInfo> endpointOpt = queryDefaultAudioEndpoint(effectiveAudioOutputProvider);
        if (endpointOpt.isPresent()) {
            AudioEndpointInfo endpoint = endpointOpt.get();

            // 1. Tentativa por ContainerId (GUID case-insensitive)
            String endpointContainer = normalizeGuid(endpoint.containerId());
            if (!endpointContainer.isEmpty() && !ZERO_GUID.equals(endpointContainer)) {
                for (EvaluatedCandidate candidate : connected) {
                    String candidateGuid = normalizeGuid(
                            containerIdsByDeviceId.get(candidate.reading().deviceId())
                    );
                    if (!candidateGuid.isEmpty() && endpointContainer.equals(candidateGuid)) {
                        return new SelectionResult(
                                candidate.reading(),
                                "saída de áudio padrão do Windows (ContainerId)"
                        );
                    }
                }
            }

            // 2. Tentativa por tokens normalizados em friendlyName + interfaceName
            String combinedAudioName = combineAudioNames(endpoint);
            if (!combinedAudioName.isEmpty()) {
                for (EvaluatedCandidate candidate : sortByDeterministicPriority(connected)) {
                    if (matchesAudioEndpointTokens(candidate.reading().deviceId(), combinedAudioName)) {
                        return new SelectionResult(
                                candidate.reading(),
                                "saída de áudio padrão do Windows (" + describeEndpoint(endpoint) + ")"
                        );
                    }
                }
            }
        }

        // 3. Preserva seleção anterior se ainda estiver conectada
        String prevId = state.lastSelectedByCategory.get(category);
        if (prevId != null) {
            for (EvaluatedCandidate candidate : connected) {
                if (prevId.equals(candidate.reading().deviceId())) {
                    return new SelectionResult(
                            candidate.reading(),
                            "mantida seleção anterior (saída de áudio não corresponde aos headsets)"
                    );
                }
            }
        }

        // 4. Prioridade determinística (Akko antes de QCY, mantendo ordem da lista em empate)
        List<EvaluatedCandidate> prioritized = sortByDeterministicPriority(connected);
        return new SelectionResult(prioritized.get(0).reading(), "prioridade determinística");
    }

    private static Optional<AudioEndpointInfo> queryDefaultAudioEndpoint(DefaultAudioOutputProvider provider) {
        if (provider == null) {
            return Optional.empty();
        }
        try {
            Optional<AudioEndpointInfo> result = provider.getDefaultRenderEndpoint();
            return result != null ? result : Optional.empty();
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    private static List<EvaluatedCandidate> sortByDeterministicPriority(List<EvaluatedCandidate> candidates) {
        List<EvaluatedCandidate> copy = new ArrayList<>(candidates);
        copy.sort((a, b) -> Integer.compare(
                priorityRank(a.reading().deviceId()),
                priorityRank(b.reading().deviceId())
        ));
        return copy;
    }

    private static int priorityRank(String deviceId) {
        if (isAkkoDevice(deviceId)) {
            return 0;
        }
        if (isQcyDevice(deviceId)) {
            return 1;
        }
        return 2;
    }

    private static boolean matchesAudioEndpointTokens(String deviceId, String normalizedAudioText) {
        if (isQcyDevice(deviceId)) {
            return normalizedAudioText.contains("qcy") || normalizedAudioText.contains("t13");
        }
        if (isAkkoDevice(deviceId)) {
            return normalizedAudioText.contains("akko")
                    || normalizedAudioText.contains("verge")
                    || normalizedAudioText.contains("s9");
        }
        return false;
    }

    private static String combineAudioNames(AudioEndpointInfo endpoint) {
        String friendly = endpoint.friendlyName() != null ? endpoint.friendlyName() : "";
        String iface = endpoint.interfaceName() != null ? endpoint.interfaceName() : "";
        return (friendly + " " + iface).trim().toLowerCase(Locale.ROOT);
    }

    private static String describeEndpoint(AudioEndpointInfo endpoint) {
        if (endpoint.friendlyName() != null && !endpoint.friendlyName().isBlank()) {
            return endpoint.friendlyName().trim();
        }
        if (endpoint.interfaceName() != null && !endpoint.interfaceName().isBlank()) {
            return endpoint.interfaceName().trim();
        }
        return "endpoint padrão";
    }

    private static EvaluatedCandidate findFirstReceiverDetectedCandidate(List<EvaluatedCandidate> candidates) {
        for (EvaluatedCandidate candidate : candidates) {
            if (candidate.reading().status().isReceiverDetectedState()) {
                return candidate;
            }
        }
        return null;
    }

    private static DeviceReading buildEmptyHeadsetSlot(
            List<EvaluatedCandidate> headsetCandidates,
            boolean simMode,
            boolean bluetoothAdapterAvailable
    ) {
        boolean akkoReceiverOnlyOff = false;
        for (EvaluatedCandidate candidate : headsetCandidates) {
            if (isAkkoDevice(candidate.reading().deviceId())
                    && candidate.connectivity() == EffectiveConnectivity.RECEIVER_ONLY_OFF) {
                akkoReceiverOnlyOff = true;
                break;
            }
        }

        StringBuilder detail = new StringBuilder();
        if (akkoReceiverOnlyOff) {
            detail.append("Receptor 2.4 GHz do Akko presente; headset desligado.");
        }
        if (!bluetoothAdapterAvailable) {
            if (detail.length() > 0) {
                detail.append(' ');
            }
            detail.append("Adaptador Bluetooth ausente ou desativado.");
        }
        if (detail.length() == 0) {
            detail.append("Nenhum fone conectado.");
        }

        return new DeviceReading(
                simMode ? SIM_SLOT_HEADSET_ID : SLOT_HEADSET_ID,
                "Fone",
                DeviceType.HEADSET,
                null,
                false,
                ConnectionStatus.DISCONNECTED,
                "Sem conexão",
                simMode ? DataSourceType.SIMULATED : DataSourceType.REAL,
                detail.toString(),
                LocalTime.now().withNano(0),
                null,
                null
        );
    }

    private static DeviceReading buildEmptyGenericSlot(DeviceType category, boolean simMode) {
        String id;
        String name;
        String detail;
        if (category == DeviceType.MOUSE) {
            id = simMode ? SIM_SLOT_MOUSE_ID : SLOT_MOUSE_ID;
            name = "Mouse";
            detail = "Nenhum mouse conectado.";
        } else if (category == DeviceType.GAMEPAD) {
            id = simMode ? SIM_SLOT_GAMEPAD_ID : SLOT_GAMEPAD_ID;
            name = "Controle";
            detail = "Nenhum controle conectado.";
        } else {
            id = simMode ? SIM_SLOT_HEADSET_ID : SLOT_HEADSET_ID;
            name = "Fone";
            detail = "Nenhum fone conectado.";
        }

        return new DeviceReading(
                id,
                name,
                category,
                null,
                false,
                ConnectionStatus.DISCONNECTED,
                "Sem conexão",
                simMode ? DataSourceType.SIMULATED : DataSourceType.REAL,
                detail,
                LocalTime.now().withNano(0),
                null,
                null
        );
    }

    private static DeviceReading normalizeAkkoReading(DeviceReading r) {
        if (isAkkoDevice(r.deviceId()) && !AKKO_DISPLAY_NAME.equals(r.displayName())) {
            return new DeviceReading(
                    r.deviceId(),
                    AKKO_DISPLAY_NAME,
                    r.deviceType(),
                    r.batteryPercent(),
                    r.charging(),
                    r.status(),
                    r.connectionLabel(),
                    r.sourceType(),
                    r.detailMessage(),
                    r.updatedAt(),
                    r.lastKnownPercent(),
                    r.lastKnownAt()
            );
        }
        return r;
    }

    private static boolean isAkkoDevice(String deviceId) {
        return AKKO_DEVICE_ID.equalsIgnoreCase(deviceId)
                || SIM_AKKO_DEVICE_ID.equalsIgnoreCase(deviceId);
    }

    private static boolean isQcyDevice(String deviceId) {
        return QCY_DEVICE_ID.equalsIgnoreCase(deviceId)
                || SIM_QCY_DEVICE_ID.equalsIgnoreCase(deviceId);
    }

    private static String normalizeGuid(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("{") && normalized.endsWith("}") && normalized.length() > 2) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private void logHeadsetSelectionIfChanged(ModeState state, SelectionResult selected, boolean simMode) {
        DeviceReading r = selected.reading();
        String newKey = r.deviceId();
        if (newKey.equals(state.lastLoggedHeadsetDeviceId)) {
            return;
        }
        state.lastLoggedHeadsetDeviceId = newKey;
        if (logger != null) {
            String prefix = simMode ? "[SELECAO][SIM] " : "[SELECAO] ";
            logger.info(prefix + "Categoria Fone selecionou: "
                    + r.displayName()
                    + " (" + r.connectionLabel() + ") — motivo: "
                    + selected.reason());
        }
    }

    private record EvaluatedCandidate(DeviceReading reading, EffectiveConnectivity connectivity) {}

    private record SelectionResult(DeviceReading reading, String reason) {}

    private static final class DeviceTransientState {
        private DeviceReading lastConnectedReading;
        private int consecutiveTransientFailures;
        private EffectiveConnectivity lastConnectivity;
    }

    private static final class ModeState {
        private final Map<String, DeviceTransientState> deviceStates = new HashMap<>();
        private final Map<DeviceType, String> lastSelectedByCategory = new EnumMap<>(DeviceType.class);
        private String lastLoggedHeadsetDeviceId;

        private void clear() {
            deviceStates.clear();
            lastSelectedByCategory.clear();
            lastLoggedHeadsetDeviceId = null;
        }
    }
}
