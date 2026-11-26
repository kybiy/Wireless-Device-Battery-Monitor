package br.com.wirelessbattery.bluetooth;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Cfgmgr32;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.SetupApi;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Scanner Bluetooth passivo e somente-leitura para Windows 10/11 via APIs nativas Win32 (JNA).
 *
 * <p>A abordagem de enumeração passiva de nós PnP Bluetooth ({@code BTHENUM\}, {@code BTHLE\},
 * {@code BTHLEDEVICE\}) e consolidação por {@code ContainerId} consultando
 * {@code DEVPKEY_Bluetooth_Battery}, {@code PKEY_Devices_BatteryLife} e
 * {@code DEVPKEY_Bluetooth_IsConnected} foi inspirada na implementação C++ de
 * {@code joaoarebalo/Wireless-Device-Battery-Monitor-Fallen/src/drivers/generic.cpp}
 * (licenciada sob GPL-3.0).
 *
 * <p>Este scanner nunca abre conexões RFCOMM ou GATT e não interfere nos perfis de áudio
 * A2DP/HFP do dispositivo.
 */
public final class WindowsBluetoothScanner implements BluetoothDeviceScanner {

    public static final long DEFAULT_CACHE_TTL_MILLIS = 500L;

    private static final int DEVPROP_TYPE_BYTE = 0x00000003;
    private static final int DEVPROP_TYPE_UINT32 = 0x00000007;
    private static final int DEVPROP_TYPE_GUID = 0x0000000D;
    private static final int DEVPROP_TYPE_BOOLEAN = 0x00000011;
    private static final int DEVPROP_TYPE_STRING = 0x00000012;

    private static final int DN_HAS_PROBLEM = 0x00000400;
    private static final String ZERO_GUID = "{00000000-0000-0000-0000-000000000000}";

    private static final DEVPROPKEY DEVPKEY_DEVICE_CONTAINER_ID =
            new DEVPROPKEY("{8C7ED206-3F8A-4827-B3AB-AE9E1FAEFC6C}", 2);
    private static final DEVPROPKEY DEVPKEY_DEVICE_FRIENDLY_NAME =
            new DEVPROPKEY("{A45C254E-DF1C-4EFD-8020-67D146A850E0}", 14);
    private static final DEVPROPKEY DEVPKEY_DEVICE_DESC =
            new DEVPROPKEY("{A45C254E-DF1C-4EFD-8020-67D146A850E0}", 2);
    private static final DEVPROPKEY DEVPKEY_BLUETOOTH_IS_CONNECTED =
            new DEVPROPKEY("{83DA6326-97A6-4088-9453-A1923F573B29}", 15);
    private static final DEVPROPKEY DEVPKEY_BLUETOOTH_BATTERY =
            new DEVPROPKEY("{104EA319-6EE2-4701-BD47-8DDBF425BBE5}", 2);
    private static final DEVPROPKEY PKEY_DEVICES_BATTERY_LIFE =
            new DEVPROPKEY("{49CD1F76-5626-4B17-A4E8-18B4AA1A2213}", 10);

    private static final List<String> SERVICE_SUFFIXES = List.of(
            " Hands-Free AG",
            " Audio Gateway",
            " Avrcp Transport",
            " Hands-Free",
            " Stereo"
    );

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT)
            .contains("win");

    private final long cacheTtlNanos;
    private final Object scanLock = new Object();
    private ScanResult cachedResult;
    private long cachedAtNanos;

    public WindowsBluetoothScanner() {
        this(DEFAULT_CACHE_TTL_MILLIS);
    }

    public WindowsBluetoothScanner(long cacheTtlMillis) {
        long safeMillis = Math.max(0L, cacheTtlMillis);
        this.cacheTtlNanos = safeMillis * 1_000_000L;
    }

    @Override
    public ScanResult scan() {
        synchronized (scanLock) {
            if (cacheTtlNanos > 0L && cachedResult != null) {
                long elapsed = System.nanoTime() - cachedAtNanos;
                if (elapsed >= 0L && elapsed < cacheTtlNanos) {
                    return cachedResult;
                }
            }
            ScanResult fresh = scanUncached();
            cachedResult = fresh;
            cachedAtNanos = System.nanoTime();
            return fresh;
        }
    }

    private ScanResult scanUncached() {
        if (!IS_WINDOWS) {
            return new ScanResult(false, List.of(), "Scanner Bluetooth suportado apenas no Windows.");
        }

        RadioProbeState radioState = probeBluetoothRadio();
        if (radioState == RadioProbeState.NO_RADIO) {
            return new ScanResult(false, List.of(), "Adaptador Bluetooth ausente ou desativado.");
        }

        WinNT.HANDLE hDevInfo;
        try {
            hDevInfo = SetupApi.INSTANCE.SetupDiGetClassDevs(
                    null,
                    null,
                    null,
                    SetupApi.DIGCF_ALLCLASSES | SetupApi.DIGCF_PRESENT
            );
        } catch (Throwable t) {
            return new ScanResult(
                    radioState == RadioProbeState.RADIO_PRESENT,
                    List.of(),
                    "Falha ao inicializar enumeração SetupAPI: " + t.getMessage()
            );
        }

        if (hDevInfo == null || WinBase.INVALID_HANDLE_VALUE.equals(hDevInfo)) {
            return new ScanResult(
                    radioState == RadioProbeState.RADIO_PRESENT,
                    List.of(),
                    "SetupDiGetClassDevs retornou handle inválido."
            );
        }

        List<RawNode> collectedNodes = new ArrayList<>();
        boolean sawAnyBluetoothRoot = false;
        Memory propBuffer = new Memory(1024);

        try {
            SetupApiExt setupApiExt = SetupApiExtHolder.INSTANCE;
            Cfgmgr32Ext cfgmgr32Ext = Cfgmgr32ExtHolder.INSTANCE;
            if (setupApiExt == null) {
                return new ScanResult(
                        radioState == RadioProbeState.RADIO_PRESENT,
                        List.of(),
                        "Funções SetupAPI avançadas indisponíveis."
                );
            }

            for (int index = 0; ; index++) {
                SetupApi.SP_DEVINFO_DATA devInfoData = new SetupApi.SP_DEVINFO_DATA();
                if (!SetupApi.INSTANCE.SetupDiEnumDeviceInfo(hDevInfo, index, devInfoData)) {
                    break;
                }

                String instanceId = readDeviceInstanceId(setupApiExt, hDevInfo, devInfoData);
                if (instanceId == null || instanceId.isBlank()) {
                    continue;
                }

                String upperId = instanceId.toUpperCase(Locale.ROOT);
                if (upperId.startsWith("BTH\\")) {
                    sawAnyBluetoothRoot = true;
                }
                if (!isBluetoothDeviceNode(upperId)) {
                    continue;
                }

                String containerId = readGuidProperty(
                        setupApiExt, hDevInfo, devInfoData, DEVPKEY_DEVICE_CONTAINER_ID, propBuffer
                );
                if (containerId == null || containerId.isBlank() || ZERO_GUID.equals(containerId)) {
                    containerId = instanceId;
                }

                String friendlyName = readStringProperty(
                        setupApiExt, hDevInfo, devInfoData, DEVPKEY_DEVICE_FRIENDLY_NAME, propBuffer
                );
                if (friendlyName == null || friendlyName.isBlank()) {
                    friendlyName = readStringProperty(
                            setupApiExt, hDevInfo, devInfoData, DEVPKEY_DEVICE_DESC, propBuffer
                    );
                }

                Optional<Boolean> connectedProp = readBooleanProperty(
                        setupApiExt, hDevInfo, devInfoData, DEVPKEY_BLUETOOTH_IS_CONNECTED, propBuffer
                );

                boolean hasProblem = hasDevNodeProblem(cfgmgr32Ext, devInfoData.DevInst);
                Integer bluetoothBattery = null;
                Integer batteryLife = null;
                if (!hasProblem) {
                    bluetoothBattery = readBatteryByteProperty(
                            setupApiExt, hDevInfo, devInfoData, DEVPKEY_BLUETOOTH_BATTERY, propBuffer
                    );
                    batteryLife = readBatteryLifeProperty(
                            setupApiExt, hDevInfo, devInfoData, PKEY_DEVICES_BATTERY_LIFE, propBuffer
                    );
                }

                collectedNodes.add(new RawNode(
                        instanceId,
                        containerId,
                        isPrimaryDevNode(upperId),
                        isBluetoothLeNode(upperId),
                        friendlyName,
                        connectedProp.orElse(null),
                        hasProblem,
                        bluetoothBattery,
                        batteryLife
                ));
            }
        } catch (Throwable t) {
            return new ScanResult(
                    radioState == RadioProbeState.RADIO_PRESENT || sawAnyBluetoothRoot,
                    List.of(),
                    "Erro ao varrer dispositivos Bluetooth via SetupAPI: " + t.getMessage()
            );
        } finally {
            try {
                SetupApi.INSTANCE.SetupDiDestroyDeviceInfoList(hDevInfo);
            } catch (Throwable ignored) {
                // Limpeza de melhor esforço
            }
        }

        List<BluetoothDeviceInfo> devices = consolidateByContainer(collectedNodes);
        boolean adapterAvailable = radioState == RadioProbeState.RADIO_PRESENT
                || (radioState == RadioProbeState.API_UNAVAILABLE && (sawAnyBluetoothRoot || !devices.isEmpty()));

        String diag = adapterAvailable
                ? "Varredura Bluetooth concluída (" + devices.size() + " dispositivo(s))."
                : "Adaptador Bluetooth ausente ou desativado.";

        return new ScanResult(adapterAvailable, List.copyOf(devices), diag);
    }

    static List<BluetoothDeviceInfo> consolidateByContainer(List<RawNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        Map<String, List<RawNode>> grouped = new LinkedHashMap<>();
        for (RawNode node : nodes) {
            grouped.computeIfAbsent(node.containerId(), k -> new ArrayList<>()).add(node);
        }

        List<BluetoothDeviceInfo> result = new ArrayList<>(grouped.size());
        for (Map.Entry<String, List<RawNode>> entry : grouped.entrySet()) {
            result.add(consolidateContainer(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(result);
    }

    private static BluetoothDeviceInfo consolidateContainer(String containerId, List<RawNode> nodes) {
        List<RawNode> primaryNodes = new ArrayList<>();
        boolean anyBle = false;
        for (RawNode node : nodes) {
            if (node.primaryDevNode()) {
                primaryNodes.add(node);
            }
            if (node.bluetoothLe()) {
                anyBle = true;
            }
        }

        boolean connected;
        if (!primaryNodes.isEmpty()) {
            connected = false;
            for (RawNode primary : primaryNodes) {
                if (Boolean.TRUE.equals(primary.connectedProp())) {
                    connected = true;
                    break;
                }
            }
        } else {
            connected = false;
            for (RawNode node : nodes) {
                if (Boolean.TRUE.equals(node.connectedProp())) {
                    connected = true;
                    break;
                }
            }
        }

        Integer batteryPercent = connected ? resolveBatteryPercent(nodes) : null;
        String friendlyName = resolveFriendlyName(primaryNodes, nodes);
        String representativeInstanceId = !primaryNodes.isEmpty()
                ? primaryNodes.get(0).instanceId()
                : nodes.get(0).instanceId();

        return new BluetoothDeviceInfo(
                containerId,
                friendlyName,
                connected,
                batteryPercent,
                anyBle,
                representativeInstanceId
        );
    }

    private static Integer resolveBatteryPercent(List<RawNode> nodes) {
        Integer bestValue = null;
        int bestPriority = Integer.MAX_VALUE;

        for (RawNode node : nodes) {
            if (node.hasProblem()) {
                continue;
            }
            int livenessBucket;
            if (Boolean.TRUE.equals(node.connectedProp())) {
                livenessBucket = 0;
            } else if (node.connectedProp() == null) {
                livenessBucket = 2;
            } else {
                livenessBucket = 4;
            }

            if (node.bluetoothBattery() != null && isValidBatteryPercent(node.bluetoothBattery())) {
                int priority = livenessBucket;
                if (priority < bestPriority) {
                    bestPriority = priority;
                    bestValue = node.bluetoothBattery();
                }
            }
            if (node.batteryLife() != null && isValidBatteryPercent(node.batteryLife())) {
                int priority = livenessBucket + 1;
                if (priority < bestPriority) {
                    bestPriority = priority;
                    bestValue = node.batteryLife();
                }
            }
        }
        return bestValue;
    }

    private static String resolveFriendlyName(List<RawNode> primaryNodes, List<RawNode> allNodes) {
        String fromPrimary = pickCleanName(primaryNodes);
        if (fromPrimary != null) {
            return fromPrimary;
        }
        String fromAny = pickCleanName(allNodes);
        if (fromAny != null) {
            return fromAny;
        }
        for (RawNode node : allNodes) {
            if (node.friendlyName() != null && !node.friendlyName().isBlank()) {
                return node.friendlyName().trim();
            }
        }
        return !primaryNodes.isEmpty()
                ? primaryNodes.get(0).instanceId()
                : allNodes.get(0).instanceId();
    }

    private static String pickCleanName(List<RawNode> nodes) {
        for (RawNode node : nodes) {
            String stripped = stripServiceSuffix(node.friendlyName());
            if (stripped != null && !stripped.isBlank()) {
                return stripped;
            }
        }
        return null;
    }

    /**
     * Remove sufixos comuns de perfil/serviço Bluetooth adicionados pelo Windows ao nome do dispositivo.
     */
    public static String stripServiceSuffix(String rawName) {
        if (rawName == null) {
            return null;
        }
        String name = rawName.trim();
        boolean changed;
        do {
            changed = false;
            String lower = name.toLowerCase(Locale.ROOT);
            for (String suffix : SERVICE_SUFFIXES) {
                if (lower.endsWith(suffix.toLowerCase(Locale.ROOT))) {
                    name = name.substring(0, name.length() - suffix.length()).trim();
                    changed = true;
                    break;
                }
            }
        } while (changed && !name.isEmpty());
        return name;
    }

    private static boolean isBluetoothDeviceNode(String upperId) {
        return upperId.startsWith("BTHENUM\\")
                || upperId.startsWith("BTHLE\\")
                || upperId.startsWith("BTHLEDEVICE\\");
    }

    private static boolean isPrimaryDevNode(String upperId) {
        return upperId.startsWith("BTHENUM\\DEV_")
                || upperId.startsWith("BTHLE\\DEV_")
                || upperId.startsWith("BTHLEDEVICE\\DEV_");
    }

    private static boolean isBluetoothLeNode(String upperId) {
        return upperId.startsWith("BTHLE\\") || upperId.startsWith("BTHLEDEVICE\\");
    }

    private static boolean isValidBatteryPercent(int value) {
        return value >= 0 && value <= 100;
    }

    private static RadioProbeState probeBluetoothRadio() {
        try {
            BluetoothApis apis = BluetoothApisHolder.INSTANCE;
            if (apis == null) {
                return RadioProbeState.API_UNAVAILABLE;
            }
            BLUETOOTH_FIND_RADIO_PARAMS params = new BLUETOOTH_FIND_RADIO_PARAMS();
            WinNT.HANDLEByReference radioRef = new WinNT.HANDLEByReference();
            WinNT.HANDLE hFind = null;
            try {
                hFind = apis.BluetoothFindFirstRadio(params, radioRef);
                if (!isValidHandle(hFind)) {
                    return RadioProbeState.NO_RADIO;
                }
                return RadioProbeState.RADIO_PRESENT;
            } finally {
                WinNT.HANDLE hRadio = radioRef.getValue();
                if (isValidHandle(hRadio)) {
                    try {
                        Kernel32.INSTANCE.CloseHandle(hRadio);
                    } catch (Throwable ignored) {
                        // Melhor esforço
                    }
                }
                if (isValidHandle(hFind)) {
                    try {
                        apis.BluetoothFindRadioClose(hFind);
                    } catch (Throwable ignored) {
                        // Melhor esforço
                    }
                }
            }
        } catch (Throwable t) {
            return RadioProbeState.API_UNAVAILABLE;
        }
    }

    private static boolean isValidHandle(WinNT.HANDLE handle) {
        return handle != null
                && handle.getPointer() != null
                && Pointer.nativeValue(handle.getPointer()) != 0L
                && !WinBase.INVALID_HANDLE_VALUE.equals(handle);
    }

    private static boolean hasDevNodeProblem(Cfgmgr32Ext cfgmgr32Ext, int devInst) {
        if (cfgmgr32Ext == null || devInst == 0) {
            return false;
        }
        try {
            IntByReference status = new IntByReference();
            IntByReference problemNumber = new IntByReference();
            int cr = cfgmgr32Ext.CM_Get_DevNode_Status(status, problemNumber, devInst, 0);
            return cr == Cfgmgr32.CR_SUCCESS && (status.getValue() & DN_HAS_PROBLEM) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String readDeviceInstanceId(
            SetupApiExt setupApiExt,
            WinNT.HANDLE hDevInfo,
            SetupApi.SP_DEVINFO_DATA devInfoData
    ) {
        char[] buffer = new char[256];
        IntByReference requiredSize = new IntByReference();
        boolean ok = setupApiExt.SetupDiGetDeviceInstanceId(
                hDevInfo, devInfoData, buffer, buffer.length, requiredSize
        );
        if (!ok && Kernel32.INSTANCE.GetLastError() == WinError.ERROR_INSUFFICIENT_BUFFER) {
            int needed = requiredSize.getValue();
            if (needed > buffer.length && needed <= 4096) {
                buffer = new char[needed];
                ok = setupApiExt.SetupDiGetDeviceInstanceId(
                        hDevInfo, devInfoData, buffer, buffer.length, requiredSize
                );
            }
        }
        if (!ok) {
            return null;
        }
        String raw = Native.toString(buffer);
        return raw.isBlank() ? null : raw.trim();
    }

    private static PropertyData readRawProperty(
            SetupApiExt setupApiExt,
            WinNT.HANDLE hDevInfo,
            SetupApi.SP_DEVINFO_DATA devInfoData,
            DEVPROPKEY key,
            Memory reusableBuffer
    ) {
        IntByReference propType = new IntByReference();
        IntByReference requiredSize = new IntByReference();
        Memory activeBuf = reusableBuffer;

        boolean ok = setupApiExt.SetupDiGetDeviceProperty(
                hDevInfo,
                devInfoData,
                key,
                propType,
                activeBuf,
                (int) activeBuf.size(),
                requiredSize,
                0
        );
        if (!ok && Kernel32.INSTANCE.GetLastError() == WinError.ERROR_INSUFFICIENT_BUFFER) {
            int needed = requiredSize.getValue();
            if (needed > activeBuf.size() && needed <= 65_536) {
                activeBuf = new Memory(needed);
                ok = setupApiExt.SetupDiGetDeviceProperty(
                        hDevInfo,
                        devInfoData,
                        key,
                        propType,
                        activeBuf,
                        needed,
                        requiredSize,
                        0
                );
            }
        }
        if (!ok) {
            return null;
        }
        int len = Math.min(requiredSize.getValue(), (int) activeBuf.size());
        if (len <= 0) {
            return null;
        }
        return new PropertyData(propType.getValue(), activeBuf, len);
    }

    private static String readGuidProperty(
            SetupApiExt setupApiExt,
            WinNT.HANDLE hDevInfo,
            SetupApi.SP_DEVINFO_DATA devInfoData,
            DEVPROPKEY key,
            Memory reusableBuffer
    ) {
        PropertyData data = readRawProperty(setupApiExt, hDevInfo, devInfoData, key, reusableBuffer);
        if (data == null || data.type() != DEVPROP_TYPE_GUID || data.length() < 16) {
            return null;
        }
        Guid.GUID guid = new Guid.GUID(data.buffer());
        char[] out = new char[64];
        int copied = Ole32.INSTANCE.StringFromGUID2(guid, out, out.length);
        if (copied <= 0) {
            return null;
        }
        int strLen = (copied <= out.length && out[copied - 1] == '\0') ? copied - 1 : copied;
        if (strLen <= 0) {
            return null;
        }
        return new String(out, 0, strLen).trim().toUpperCase(Locale.ROOT);
    }

    private static String readStringProperty(
            SetupApiExt setupApiExt,
            WinNT.HANDLE hDevInfo,
            SetupApi.SP_DEVINFO_DATA devInfoData,
            DEVPROPKEY key,
            Memory reusableBuffer
    ) {
        PropertyData data = readRawProperty(setupApiExt, hDevInfo, devInfoData, key, reusableBuffer);
        if (data == null || data.type() != DEVPROP_TYPE_STRING || data.length() < 2) {
            return null;
        }
        int evenLen = data.length() & ~1;
        byte[] bytes = data.buffer().getByteArray(0, evenLen);
        String decoded = new String(bytes, StandardCharsets.UTF_16LE);
        int nulIdx = decoded.indexOf('\0');
        if (nulIdx >= 0) {
            decoded = decoded.substring(0, nulIdx);
        }
        String trimmed = decoded.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Optional<Boolean> readBooleanProperty(
            SetupApiExt setupApiExt,
            WinNT.HANDLE hDevInfo,
            SetupApi.SP_DEVINFO_DATA devInfoData,
            DEVPROPKEY key,
            Memory reusableBuffer
    ) {
        PropertyData data = readRawProperty(setupApiExt, hDevInfo, devInfoData, key, reusableBuffer);
        if (data == null || data.type() != DEVPROP_TYPE_BOOLEAN || data.length() < 1) {
            return Optional.empty();
        }
        return Optional.of((data.buffer().getByte(0) & 0xFF) != 0);
    }

    private static Integer readBatteryByteProperty(
            SetupApiExt setupApiExt,
            WinNT.HANDLE hDevInfo,
            SetupApi.SP_DEVINFO_DATA devInfoData,
            DEVPROPKEY key,
            Memory reusableBuffer
    ) {
        PropertyData data = readRawProperty(setupApiExt, hDevInfo, devInfoData, key, reusableBuffer);
        if (data == null || data.type() != DEVPROP_TYPE_BYTE || data.length() < 1) {
            return null;
        }
        int value = data.buffer().getByte(0) & 0xFF;
        return isValidBatteryPercent(value) ? value : null;
    }

    private static Integer readBatteryLifeProperty(
            SetupApiExt setupApiExt,
            WinNT.HANDLE hDevInfo,
            SetupApi.SP_DEVINFO_DATA devInfoData,
            DEVPROPKEY key,
            Memory reusableBuffer
    ) {
        PropertyData data = readRawProperty(setupApiExt, hDevInfo, devInfoData, key, reusableBuffer);
        if (data == null) {
            return null;
        }
        if (data.type() == DEVPROP_TYPE_BYTE && data.length() >= 1) {
            int value = data.buffer().getByte(0) & 0xFF;
            return isValidBatteryPercent(value) ? value : null;
        }
        if (data.type() == DEVPROP_TYPE_UINT32 && data.length() >= 4) {
            long unsigned = Integer.toUnsignedLong(data.buffer().getInt(0));
            return unsigned <= 100L ? (int) unsigned : null;
        }
        return null;
    }

    private enum RadioProbeState {
        RADIO_PRESENT,
        NO_RADIO,
        API_UNAVAILABLE
    }

    private record PropertyData(int type, Memory buffer, int length) {}

    record RawNode(
            String instanceId,
            String containerId,
            boolean primaryDevNode,
            boolean bluetoothLe,
            String friendlyName,
            Boolean connectedProp,
            boolean hasProblem,
            Integer bluetoothBattery,
            Integer batteryLife
    ) {}

    @Structure.FieldOrder({"fmtid", "pid"})
    public static class DEVPROPKEY extends Structure {
        public Guid.GUID fmtid;
        public int pid;

        public DEVPROPKEY() {
            this.fmtid = new Guid.GUID();
        }

        public DEVPROPKEY(String guidString, int pid) {
            this.fmtid = new Guid.GUID(guidString);
            this.pid = pid;
        }
    }

    @Structure.FieldOrder({"dwSize"})
    public static class BLUETOOTH_FIND_RADIO_PARAMS extends Structure {
        public int dwSize = 4;
    }

    private interface SetupApiExt extends StdCallLibrary {
        boolean SetupDiGetDeviceInstanceId(
                WinNT.HANDLE deviceInfoSet,
                SetupApi.SP_DEVINFO_DATA deviceInfoData,
                char[] deviceInstanceId,
                int deviceInstanceIdSize,
                IntByReference requiredSize
        );

        boolean SetupDiGetDeviceProperty(
                WinNT.HANDLE deviceInfoSet,
                SetupApi.SP_DEVINFO_DATA deviceInfoData,
                DEVPROPKEY propertyKey,
                IntByReference propertyType,
                Pointer propertyBuffer,
                int propertyBufferSize,
                IntByReference requiredSize,
                int flags
        );
    }

    private interface Cfgmgr32Ext extends StdCallLibrary {
        int CM_Get_DevNode_Status(
                IntByReference pulStatus,
                IntByReference pulProblemNumber,
                int dnDevInst,
                int ulFlags
        );
    }

    private interface BluetoothApis extends StdCallLibrary {
        WinNT.HANDLE BluetoothFindFirstRadio(
                BLUETOOTH_FIND_RADIO_PARAMS pbtfrp,
                WinNT.HANDLEByReference phRadio
        );

        boolean BluetoothFindRadioClose(WinNT.HANDLE hFind);
    }

    private static final class SetupApiExtHolder {
        static final SetupApiExt INSTANCE = loadSetupApiExt();

        private static SetupApiExt loadSetupApiExt() {
            try {
                return Native.load("setupapi", SetupApiExt.class, W32APIOptions.UNICODE_OPTIONS);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    private static final class Cfgmgr32ExtHolder {
        static final Cfgmgr32Ext INSTANCE = loadCfgmgr32Ext();

        private static Cfgmgr32Ext loadCfgmgr32Ext() {
            try {
                return Native.load("cfgmgr32", Cfgmgr32Ext.class, W32APIOptions.UNICODE_OPTIONS);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    private static final class BluetoothApisHolder {
        static final BluetoothApis INSTANCE = loadBluetoothApis();

        private static BluetoothApis loadBluetoothApis() {
            try {
                return Native.load("bluetoothapis", BluetoothApis.class, W32APIOptions.UNICODE_OPTIONS);
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
