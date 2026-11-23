package br.com.wirelessbattery.diagnostics;

/**
 * Metadados somente-leitura de um dispositivo HID conectado ao sistema.
 */
public record HidDeviceInfo(
        int vendorId,
        int productId,
        int interfaceNumber,
        int usagePage,
        int usage,
        String manufacturer,
        String product,
        String serialNumber,
        String path,
        String identificationStatus
) {
    public String formatVid() {
        return String.format("0x%04X", vendorId & 0xFFFF);
    }

    public String formatPid() {
        return String.format("0x%04X", productId & 0xFFFF);
    }

    public String formatVidPid() {
        return formatVid() + ":" + formatPid();
    }

    public String formatUsagePage() {
        return String.format("0x%04X", usagePage & 0xFFFF);
    }

    public String formatUsage() {
        return String.format("0x%04X", usage & 0xFFFF);
    }

    public String safeManufacturer() {
        return manufacturer != null && !manufacturer.isBlank() ? manufacturer : "-";
    }

    public String safeProduct() {
        return product != null && !product.isBlank() ? product : "-";
    }

    public String safeSerial() {
        return serialNumber != null && !serialNumber.isBlank() ? serialNumber : "-";
    }

    public String safePath() {
        return path != null && !path.isBlank() ? path : "-";
    }

    public String safeIdentificationStatus() {
        return identificationStatus != null && !identificationStatus.isBlank()
                ? identificationStatus
                : "Não identificado";
    }
}
