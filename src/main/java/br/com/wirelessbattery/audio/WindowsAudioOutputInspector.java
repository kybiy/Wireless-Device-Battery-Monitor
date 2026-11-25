package br.com.wirelessbattery.audio;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.COM.COMUtils;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.util.Locale;
import java.util.Optional;

/**
 * Inspetor somente-leitura do endpoint padrão de saída de áudio do Windows via
 * Core Audio / MMDevice API ({@code ole32.dll} + VTables COM via JNA).
 *
 * Nunca altera o dispositivo de áudio padrão nem inicia processos externos.
 */
public final class WindowsAudioOutputInspector implements DefaultAudioOutputProvider {

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT)
            .contains("win");

    private static final long DEFAULT_CACHE_TTL_MS = 1_000L;

    private static final int S_OK = 0;
    private static final int S_FALSE = 1;
    private static final int RPC_E_CHANGED_MODE = 0x80010106;
    private static final int CLSCTX_INPROC_SERVER = 1;

    private static final int E_RENDER = 0;
    private static final int E_CONSOLE = 0;
    private static final int E_MULTIMEDIA = 1;
    private static final int STGM_READ = 0;

    private static final int VTABLE_UNKNOWN_RELEASE = 2;
    private static final int VTABLE_ENUM_GET_DEFAULT_AUDIO_ENDPOINT = 4;
    private static final int VTABLE_DEVICE_OPEN_PROPERTY_STORE = 4;
    private static final int VTABLE_DEVICE_GET_ID = 5;
    private static final int VTABLE_PROPERTY_STORE_GET_VALUE = 5;

    private static final int PROPVARIANT_SIZE = 24;
    private static final int PROPVARIANT_VALUE_OFFSET = 8;
    private static final short VT_LPWSTR = 31;
    private static final short VT_CLSID = 72;

    private static final String CLSID_MM_DEVICE_ENUMERATOR = "{BCDE0395-E52F-467C-8E3D-C4579291692E}";
    private static final String IID_IMM_DEVICE_ENUMERATOR = "{A95664D2-9614-4F35-A746-DE8DB63617E6}";

    private static final String PKEY_DEVICE_FRIENDLY_NAME_FMTID = "{A45C254E-DF1C-4EFD-8020-67D146A850E0}";
    private static final int PKEY_DEVICE_FRIENDLY_NAME_PID = 14;

    private static final String PKEY_DEVICE_INTERFACE_FRIENDLY_NAME_FMTID = "{026E516E-B814-414B-83CD-856D6FEF4822}";
    private static final int PKEY_DEVICE_INTERFACE_FRIENDLY_NAME_PID = 2;

    private static final String PKEY_DEVICE_CONTAINER_ID_FMTID = "{8C7ED206-3F8A-4827-B3AB-AE9E1FAEFC6C}";
    private static final int PKEY_DEVICE_CONTAINER_ID_PID = 2;

    @Structure.FieldOrder({"fmtid", "pid"})
    public static class PROPERTYKEY extends Structure {
        public Guid.GUID fmtid;
        public int pid;

        public PROPERTYKEY() {
            this.fmtid = new Guid.GUID();
        }

        public PROPERTYKEY(String fmtidGuid, int pid) {
            this.fmtid = new Guid.GUID(fmtidGuid);
            this.pid = pid;
        }
    }

    private interface Ole32Props extends StdCallLibrary {
        Ole32Props INSTANCE = Native.load("ole32", Ole32Props.class);

        int PropVariantClear(Pointer pvar);
    }

    private final long cacheTtlNanos;
    private Optional<AudioEndpointInfo> cachedResult = Optional.empty();
    private long cacheExpiresAtNanos;
    private boolean hasCachedResult;

    public WindowsAudioOutputInspector() {
        this(DEFAULT_CACHE_TTL_MS);
    }

    public WindowsAudioOutputInspector(long cacheTtlMillis) {
        this.cacheTtlNanos = Math.max(0L, cacheTtlMillis) * 1_000_000L;
    }

    @Override
    public synchronized Optional<AudioEndpointInfo> getDefaultRenderEndpoint() {
        long now = System.nanoTime();
        if (hasCachedResult && cacheTtlNanos > 0L && (now - cacheExpiresAtNanos) < 0L) {
            return cachedResult;
        }
        Optional<AudioEndpointInfo> fresh = queryDefaultRenderEndpointSafe();
        cachedResult = fresh;
        cacheExpiresAtNanos = System.nanoTime() + cacheTtlNanos;
        hasCachedResult = true;
        return fresh;
    }

    private Optional<AudioEndpointInfo> queryDefaultRenderEndpointSafe() {
        if (!IS_WINDOWS) {
            return Optional.empty();
        }

        boolean shouldUninitialize = false;
        try {
            WinNT.HRESULT initHr = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED);
            int initCode = initHr != null ? initHr.intValue() : -1;
            if (initCode == S_OK || initCode == S_FALSE) {
                shouldUninitialize = true;
            } else if (initCode != RPC_E_CHANGED_MODE) {
                return Optional.empty();
            }

            return queryWithInitializedCom();
        } catch (Throwable ignored) {
            return Optional.empty();
        } finally {
            if (shouldUninitialize) {
                try {
                    Ole32.INSTANCE.CoUninitialize();
                } catch (Throwable ignored) {
                    // Melhor esforço para liberar apartamento COM.
                }
            }
        }
    }

    private Optional<AudioEndpointInfo> queryWithInitializedCom() {
        Pointer enumerator = null;
        Pointer device = null;
        Pointer propertyStore = null;
        try {
            PointerByReference enumRef = new PointerByReference();
            WinNT.HRESULT createHr = Ole32.INSTANCE.CoCreateInstance(
                    new Guid.GUID(CLSID_MM_DEVICE_ENUMERATOR),
                    null,
                    CLSCTX_INPROC_SERVER,
                    new Guid.GUID(IID_IMM_DEVICE_ENUMERATOR),
                    enumRef
            );
            if (!COMUtils.SUCCEEDED(createHr) || enumRef.getValue() == null) {
                return Optional.empty();
            }
            enumerator = enumRef.getValue();

            device = getDefaultEndpointDevice(enumerator);
            if (device == null) {
                return Optional.empty();
            }

            String endpointId = readEndpointId(device);

            PointerByReference storeRef = new PointerByReference();
            Function openStore = getVTableFunction(device, VTABLE_DEVICE_OPEN_PROPERTY_STORE);
            if (openStore != null) {
                int storeHr = openStore.invokeInt(new Object[]{device, STGM_READ, storeRef});
                if (storeHr >= 0 && storeRef.getValue() != null) {
                    propertyStore = storeRef.getValue();
                }
            }

            String friendlyName = null;
            String interfaceName = null;
            String containerId = null;
            if (propertyStore != null) {
                friendlyName = readPropertyValue(
                        propertyStore, PKEY_DEVICE_FRIENDLY_NAME_FMTID, PKEY_DEVICE_FRIENDLY_NAME_PID
                );
                interfaceName = readPropertyValue(
                        propertyStore,
                        PKEY_DEVICE_INTERFACE_FRIENDLY_NAME_FMTID,
                        PKEY_DEVICE_INTERFACE_FRIENDLY_NAME_PID
                );
                containerId = readPropertyValue(
                        propertyStore, PKEY_DEVICE_CONTAINER_ID_FMTID, PKEY_DEVICE_CONTAINER_ID_PID
                );
            }

            if (endpointId == null && friendlyName == null && interfaceName == null && containerId == null) {
                return Optional.empty();
            }
            return Optional.of(new AudioEndpointInfo(endpointId, friendlyName, interfaceName, containerId));
        } finally {
            releaseCom(propertyStore);
            releaseCom(device);
            releaseCom(enumerator);
        }
    }

    private static Pointer getDefaultEndpointDevice(Pointer enumerator) {
        Function getDefaultFn = getVTableFunction(enumerator, VTABLE_ENUM_GET_DEFAULT_AUDIO_ENDPOINT);
        if (getDefaultFn == null) {
            return null;
        }
        PointerByReference deviceRef = new PointerByReference();
        int hr = getDefaultFn.invokeInt(new Object[]{enumerator, E_RENDER, E_MULTIMEDIA, deviceRef});
        if (hr >= 0 && deviceRef.getValue() != null) {
            return deviceRef.getValue();
        }

        deviceRef = new PointerByReference();
        hr = getDefaultFn.invokeInt(new Object[]{enumerator, E_RENDER, E_CONSOLE, deviceRef});
        if (hr >= 0 && deviceRef.getValue() != null) {
            return deviceRef.getValue();
        }
        return null;
    }

    private static String readEndpointId(Pointer device) {
        Function getIdFn = getVTableFunction(device, VTABLE_DEVICE_GET_ID);
        if (getIdFn == null) {
            return null;
        }
        PointerByReference idRef = new PointerByReference();
        int hr = getIdFn.invokeInt(new Object[]{device, idRef});
        if (hr < 0 || idRef.getValue() == null) {
            return null;
        }
        Pointer strPtr = idRef.getValue();
        try {
            return trimToNull(strPtr.getWideString(0));
        } finally {
            try {
                Ole32.INSTANCE.CoTaskMemFree(strPtr);
            } catch (Throwable ignored) {
                // Melhor esforço para liberar memória alocada pelo COM.
            }
        }
    }

    private static String readPropertyValue(Pointer propertyStore, String fmtidGuid, int pid) {
        PROPERTYKEY key = new PROPERTYKEY(fmtidGuid, pid);
        Memory pv = new Memory(PROPVARIANT_SIZE);
        pv.clear();
        try {
            Function getValueFn = getVTableFunction(propertyStore, VTABLE_PROPERTY_STORE_GET_VALUE);
            if (getValueFn == null) {
                return null;
            }
            int hr = getValueFn.invokeInt(new Object[]{propertyStore, key, pv});
            if (hr < 0) {
                return null;
            }
            short vt = pv.getShort(0);
            if (vt == VT_LPWSTR) {
                Pointer strPtr = pv.getPointer(PROPVARIANT_VALUE_OFFSET);
                return strPtr != null ? trimToNull(strPtr.getWideString(0)) : null;
            }
            if (vt == VT_CLSID) {
                Pointer guidPtr = pv.getPointer(PROPVARIANT_VALUE_OFFSET);
                if (guidPtr == null) {
                    return null;
                }
                Guid.GUID guid = new Guid.GUID(guidPtr);
                char[] buf = new char[64];
                int written = Ole32.INSTANCE.StringFromGUID2(guid, buf, buf.length);
                if (written <= 1) {
                    return null;
                }
                int len = written;
                while (len > 0 && buf[len - 1] == '\0') {
                    len--;
                }
                return len > 0 ? trimToNull(new String(buf, 0, len)) : null;
            }
            return null;
        } finally {
            try {
                Ole32Props.INSTANCE.PropVariantClear(pv);
            } catch (Throwable ignored) {
                // Melhor esforço para limpar PROPVARIANT.
            }
        }
    }

    private static Function getVTableFunction(Pointer comObject, int slot) {
        if (comObject == null) {
            return null;
        }
        Pointer vtable = comObject.getPointer(0L);
        if (vtable == null) {
            return null;
        }
        Pointer methodPtr = vtable.getPointer((long) slot * Native.POINTER_SIZE);
        if (methodPtr == null) {
            return null;
        }
        return Function.getFunction(methodPtr, Function.ALT_CONVENTION);
    }

    private static void releaseCom(Pointer comObject) {
        if (comObject == null) {
            return;
        }
        try {
            Function releaseFn = getVTableFunction(comObject, VTABLE_UNKNOWN_RELEASE);
            if (releaseFn != null) {
                releaseFn.invokeInt(new Object[]{comObject});
            }
        } catch (Throwable ignored) {
            // Melhor esforço para Release() COM.
        }
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
