package br.com.wirelessbattery.diagnostics;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.util.Locale;

/**
 * Passive Windows HID capabilities reader using {@code CreateFile(..., 0, ...)},
 * {@code HidD_GetPreparsedData}, and {@code HidP_GetCaps}.
 * Does not claim exclusive access and never sends or receives HID reports.
 */
public final class WindowsHidCapsInspector {

    private static final int HIDP_STATUS_SUCCESS = 0x00110000;
    private static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT)
            .contains("win");

    private WindowsHidCapsInspector() {
    }

    public record HidReportCaps(int inputBytes, int outputBytes, int featureBytes) {
        public static final HidReportCaps UNKNOWN = new HidReportCaps(-1, -1, -1);

        public boolean isKnown() {
            return inputBytes >= 0 && outputBytes >= 0 && featureBytes >= 0;
        }

        public String formatCompact() {
            if (!isKnown()) {
                return "--";
            }
            return "In=" + inputBytes + "/Out=" + outputBytes + "/Feat=" + featureBytes;
        }
    }

    public static HidReportCaps inspect(String devicePath) {
        if (!IS_WINDOWS || devicePath == null || devicePath.isBlank()) {
            return HidReportCaps.UNKNOWN;
        }
        WinNT.HANDLE handle = null;
        try {
            handle = Kernel32.INSTANCE.CreateFile(
                    devicePath,
                    0,
                    WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE,
                    null,
                    WinNT.OPEN_EXISTING,
                    0,
                    null
            );
            if (handle == null || WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
                return HidReportCaps.UNKNOWN;
            }
            PointerByReference preparsedRef = new PointerByReference();
            if (!HidLibrary.INSTANCE.HidD_GetPreparsedData(handle, preparsedRef)) {
                return HidReportCaps.UNKNOWN;
            }
            Pointer preparsed = preparsedRef.getValue();
            if (preparsed == null) {
                return HidReportCaps.UNKNOWN;
            }
            try {
                HIDP_CAPS caps = new HIDP_CAPS();
                int status = HidLibrary.INSTANCE.HidP_GetCaps(preparsed, caps);
                if (status != HIDP_STATUS_SUCCESS) {
                    return HidReportCaps.UNKNOWN;
                }
                return new HidReportCaps(
                        caps.InputReportByteLength & 0xFFFF,
                        caps.OutputReportByteLength & 0xFFFF,
                        caps.FeatureReportByteLength & 0xFFFF
                );
            } finally {
                HidLibrary.INSTANCE.HidD_FreePreparsedData(preparsed);
            }
        } catch (Throwable ex) {
            return HidReportCaps.UNKNOWN;
        } finally {
            if (handle != null && !WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
                try {
                    Kernel32.INSTANCE.CloseHandle(handle);
                } catch (Throwable ignored) {
                    // Best-effort handle cleanup.
                }
            }
        }
    }

    private interface HidLibrary extends StdCallLibrary {
        HidLibrary INSTANCE = Native.load("hid", HidLibrary.class);

        boolean HidD_GetPreparsedData(WinNT.HANDLE hidDeviceObject, PointerByReference preparsedData);

        boolean HidD_FreePreparsedData(Pointer preparsedData);

        int HidP_GetCaps(Pointer preparsedData, HIDP_CAPS capabilities);
    }

    @Structure.FieldOrder({
            "Usage",
            "UsagePage",
            "InputReportByteLength",
            "OutputReportByteLength",
            "FeatureReportByteLength",
            "Reserved",
            "NumberLinkCollectionNodes",
            "NumberInputButtonCaps",
            "NumberInputValueCaps",
            "NumberInputDataIndices",
            "NumberOutputButtonCaps",
            "NumberOutputValueCaps",
            "NumberOutputDataIndices",
            "NumberFeatureButtonCaps",
            "NumberFeatureValueCaps",
            "NumberFeatureDataIndices"
    })
    public static class HIDP_CAPS extends Structure {
        public short Usage;
        public short UsagePage;
        public short InputReportByteLength;
        public short OutputReportByteLength;
        public short FeatureReportByteLength;
        public short[] Reserved = new short[17];
        public short NumberLinkCollectionNodes;
        public short NumberInputButtonCaps;
        public short NumberInputValueCaps;
        public short NumberInputDataIndices;
        public short NumberOutputButtonCaps;
        public short NumberOutputValueCaps;
        public short NumberOutputDataIndices;
        public short NumberFeatureButtonCaps;
        public short NumberFeatureValueCaps;
        public short NumberFeatureDataIndices;
    }
}
