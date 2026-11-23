package br.com.wirelessbattery.diagnostics;

import br.com.wirelessbattery.device.AkkoS9UltraProtocol;
import br.com.wirelessbattery.device.AkkoVergeS9Config;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ferramenta auxiliar somente-leitura para analisar automaticamente arquivos {@code .pcapng}
 * gerados pelo Wireshark / USBPcap contendo tráfego USB/HID do headset Akko S9 Ultra
 * ({@code VID 0x36EB}, {@code PID 0x1087} / {@code 0x0087}).
 *
 * Funcionalidades:
 * <ul>
 *   <li>Descobre dinamicamente o endereço USB do dispositivo Akko a partir de descritores USB
 *       ({@code idVendor = 0x36EB, idProduct = 0x1087/0x0087}) presentes na captura ou pela
 *       assinatura de protocolo HID {@code ReportID 0x02} + CRC-16 ({@code poly = 0x8005}),
 *       sem assumir um endereço USB fixo.</li>
 *   <li>Separa solicitações (Host → Device) e respostas (Device → Host).</li>
 *   <li>Exibe Report IDs, comandos ({@code 0x10} conexão, {@code 0x12} bateria), payloads em hexadecimal
 *       e validação de CRC-16.</li>
 *   <li>Agrupa e contabiliza padrões repetidos.</li>
 *   <li>Compara duas ou mais capturas realizadas em porcentagens diferentes para destacar
 *       automaticamente os offsets candidatos a nível de bateria.</li>
 * </ul>
 */
public final class AkkoPcapngAnalyzer {
    private static final int PCAPNG_SHB_TYPE = 0x0A0D0D0A;
    private static final int PCAPNG_EPB_TYPE = 0x00000006;
    private static final int PCAPNG_SPB_TYPE = 0x00000003;
    private static final int BYTE_ORDER_MAGIC_LE = 0x1A2B3C4D;

    private static final int URB_CONTROL = 0x02;
    private static final int URB_INTERRUPT = 0x01;

    public enum Direction {
        HOST_TO_DEVICE("Host -> Dongle (Solicitação)"),
        DEVICE_TO_HOST("Dongle -> Host (Resposta)");

        private final String label;

        Direction(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public record HidTransferRecord(
            int packetIndex,
            int usbDeviceAddress,
            int endpoint,
            int transferType,
            Direction direction,
            int reportId,
            byte[] payload,
            boolean crc16Valid
    ) {
        public String formatHexPayload() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < payload.length; i++) {
                if (i > 0) sb.append(' ');
                sb.append(String.format("%02X", payload[i] & 0xFF));
            }
            return sb.toString();
        }

        public String formatCompactHex(int headBytes) {
            StringBuilder sb = new StringBuilder();
            int head = Math.min(headBytes, payload.length);
            for (int i = 0; i < head; i++) {
                if (i > 0) sb.append(' ');
                sb.append(String.format("%02X", payload[i] & 0xFF));
            }
            if (payload.length >= 64) {
                sb.append(String.format(" ... %02X %02X", payload[62] & 0xFF, payload[63] & 0xFF));
            }
            return sb.toString();
        }
    }

    public record CaptureAnalysis(
            String sourceName,
            Set<Integer> matchedUsbAddresses,
            List<HidTransferRecord> transfers,
            Map<String, Integer> patternFrequencies,
            List<Integer> observedBatteryPercentages
    ) {}

    private AkkoPcapngAnalyzer() {}

    public static CaptureAnalysis analyzeFile(Path pcapngPath) throws IOException {
        return analyzeFile(pcapngPath, null);
    }

    public static CaptureAnalysis analyzeFile(Path pcapngPath, Integer fallbackUsbAddress) throws IOException {
        byte[] raw = Files.readAllBytes(pcapngPath);
        return analyzeBytes(pcapngPath.getFileName().toString(), raw, fallbackUsbAddress);
    }

    public static CaptureAnalysis analyzeBytes(String sourceName, byte[] rawBytes, Integer fallbackUsbAddress) {
        if (rawBytes == null || rawBytes.length < 12) {
            return new CaptureAnalysis(sourceName, Set.of(), List.of(), Map.of(), List.of());
        }

        ByteBuffer buf = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN);
        List<RawUsbPacket> allUsbPackets = new ArrayList<>();
        Set<Integer> descriptorAddresses = new LinkedHashSet<>();
        Set<Integer> protocolSignatureAddresses = new LinkedHashSet<>();

        int pos = 0;
        int pktCounter = 0;
        while (pos + 12 <= rawBytes.length) {
            int blockType = buf.getInt(pos);
            int blockTotalLen = buf.getInt(pos + 4);
            if (blockType == PCAPNG_SHB_TYPE && pos + 12 <= rawBytes.length) {
                int bom = buf.getInt(pos + 8);
                if (bom != BYTE_ORDER_MAGIC_LE) {
                    buf.order(ByteOrder.BIG_ENDIAN);
                    blockTotalLen = buf.getInt(pos + 4);
                }
            }
            if (blockTotalLen < 12 || pos + blockTotalLen > rawBytes.length) {
                break;
            }

            if (blockType == PCAPNG_EPB_TYPE && blockTotalLen >= 32) {
                int capLen = buf.getInt(pos + 20);
                int dataStart = pos + 28;
                if (capLen > 0 && dataStart + capLen <= pos + blockTotalLen - 4) {
                    pktCounter++;
                    parseUsbPcapFrame(pktCounter, rawBytes, dataStart, capLen,
                            allUsbPackets, descriptorAddresses, protocolSignatureAddresses);
                }
            } else if (blockType == PCAPNG_SPB_TYPE && blockTotalLen >= 16) {
                int dataStart = pos + 12;
                int capLen = blockTotalLen - 16;
                if (capLen > 0 && dataStart + capLen <= pos + blockTotalLen - 4) {
                    pktCounter++;
                    parseUsbPcapFrame(pktCounter, rawBytes, dataStart, capLen,
                            allUsbPackets, descriptorAddresses, protocolSignatureAddresses);
                }
            }

            pos += blockTotalLen;
        }

        Set<Integer> targetAddresses = new LinkedHashSet<>(descriptorAddresses);
        targetAddresses.addAll(protocolSignatureAddresses);
        if (targetAddresses.isEmpty() && fallbackUsbAddress != null && fallbackUsbAddress > 0) {
            targetAddresses.add(fallbackUsbAddress);
        }

        List<HidTransferRecord> filtered = new ArrayList<>();
        Map<String, Integer> frequencies = new LinkedHashMap<>();
        List<Integer> batteryValues = new ArrayList<>();

        for (RawUsbPacket pkt : allUsbPackets) {
            boolean addressMatch = targetAddresses.contains(pkt.deviceAddress);
            boolean signatureMatch = pkt.crc16Valid;
            if (!addressMatch && !signatureMatch) {
                continue;
            }
            targetAddresses.add(pkt.deviceAddress);
            HidTransferRecord rec = new HidTransferRecord(
                    pkt.packetIndex,
                    pkt.deviceAddress,
                    pkt.endpoint,
                    pkt.transferType,
                    pkt.direction,
                    pkt.payload[0] & 0xFF,
                    pkt.payload,
                    pkt.crc16Valid
            );
            filtered.add(rec);

            String sigKey = rec.direction().name() + " | " + rec.formatCompactHex(6);
            frequencies.merge(sigKey, 1, Integer::sum);

            if (rec.direction() == Direction.DEVICE_TO_HOST) {
                AkkoS9UltraProtocol.ParsedBattery parsed =
                        AkkoS9UltraProtocol.parseInputReport(rec.payload(), rec.payload().length);
                if (parsed != null) {
                    batteryValues.add(parsed.percent());
                }
            }
        }

        return new CaptureAnalysis(sourceName, targetAddresses, filtered, frequencies, batteryValues);
    }

    private record RawUsbPacket(
            int packetIndex,
            int deviceAddress,
            int endpoint,
            int transferType,
            Direction direction,
            byte[] payload,
            boolean crc16Valid
    ) {}

    private static void parseUsbPcapFrame(
            int packetIndex,
            byte[] raw,
            int start,
            int capLen,
            List<RawUsbPacket> outPackets,
            Set<Integer> descriptorAddresses,
            Set<Integer> protocolSignatureAddresses
    ) {
        if (capLen < 27) {
            return;
        }
        ByteBuffer lb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int headerLen = lb.getShort(start) & 0xFFFF;
        if (headerLen < 27 || headerLen > capLen) {
            return;
        }
        int info = raw[start + 16] & 0xFF;
        int deviceAddress = lb.getShort(start + 19) & 0xFFFF;
        int endpoint = raw[start + 21] & 0xFF;
        int transferType = raw[start + 22] & 0xFF;
        int dataLength = lb.getInt(start + 23);

        int payloadStart = start + headerLen;
        int availablePayload = Math.max(0, Math.min(dataLength, (start + capLen) - payloadStart));
        if (availablePayload <= 0) {
            return;
        }

        byte[] payload = Arrays.copyOfRange(raw, payloadStart, payloadStart + availablePayload);

        // Verifica se é um USB Device Descriptor (18 bytes: len=18, type=0x01, VID=0x36EB, PID=0x1087/0x0087)
        if (availablePayload >= 18) {
            for (int off = 0; off <= availablePayload - 18; off++) {
                int bLength = payload[off] & 0xFF;
                int bDescType = payload[off + 1] & 0xFF;
                if (bLength == 18 && bDescType == 0x01) {
                    int vid = ((payload[off + 9] & 0xFF) << 8) | (payload[off + 8] & 0xFF);
                    int pid = ((payload[off + 11] & 0xFF) << 8) | (payload[off + 10] & 0xFF);
                    if (vid == AkkoVergeS9Config.DEFAULT_VENDOR_ID
                            && (pid == AkkoVergeS9Config.DEFAULT_PRODUCT_ID || pid == AkkoVergeS9Config.WIRED_OR_ALT_PRODUCT_ID)) {
                        descriptorAddresses.add(deviceAddress);
                    }
                }
            }
        }

        if (transferType != URB_INTERRUPT && transferType != URB_CONTROL) {
            return;
        }

        // Em transferências de controle com cabeçalho de stage + setup (8 bytes), extrai o report HID se aplicável
        if (transferType == URB_CONTROL && payload.length > 64 && (payload[payload.length - 64] & 0xFF) == 0x02) {
            payload = Arrays.copyOfRange(payload, payload.length - 64, payload.length);
        }

        boolean endpointIn = (endpoint & 0x80) != 0;
        boolean fdoToPdo = (info & 0x01) == 0;
        Direction dir;
        if (transferType == URB_INTERRUPT) {
            if (endpointIn && !fdoToPdo) {
                dir = Direction.DEVICE_TO_HOST;
            } else if (!endpointIn && fdoToPdo) {
                dir = Direction.HOST_TO_DEVICE;
            } else {
                // Pacote de submissão URB IN vazio ou conclusão URB OUT sem payload relevante
                dir = endpointIn ? Direction.DEVICE_TO_HOST : Direction.HOST_TO_DEVICE;
            }
        } else {
            dir = fdoToPdo ? Direction.HOST_TO_DEVICE : Direction.DEVICE_TO_HOST;
        }

        boolean crcValid = payload.length == AkkoS9UltraProtocol.FULL_REPORT_LENGTH
                && (payload[0] & 0xFF) == (AkkoS9UltraProtocol.REPORT_ID & 0xFF)
                && AkkoS9UltraProtocol.verifyCrc16(payload, payload.length);
        if (crcValid) {
            protocolSignatureAddresses.add(deviceAddress);
        }

        outPackets.add(new RawUsbPacket(packetIndex, deviceAddress, endpoint, transferType, dir, payload, crcValid));
    }

    public static String buildReport(CaptureAnalysis analysis) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Relatório de Análise USBPcap / PCAPNG — Akko S9 Ultra (0x36EB:0x1087) ===\n");
        sb.append("Arquivo: ").append(analysis.sourceName()).append("\n");
        sb.append("Endereço(s) USB identificado(s): ")
                .append(analysis.matchedUsbAddresses().isEmpty() ? "nenhum" : analysis.matchedUsbAddresses())
                .append("\n");
        sb.append("Total de transferências HID filtradas: ").append(analysis.transfers().size()).append("\n");
        sb.append("Leituras de bateria (cmd 0x12) decodificadas: ")
                .append(analysis.observedBatteryPercentages().isEmpty()
                        ? "nenhuma"
                        : analysis.observedBatteryPercentages() + "%")
                .append("\n\n");

        sb.append("--- Padrões Repetidos (Frequência) ---\n");
        for (Map.Entry<String, Integer> entry : analysis.patternFrequencies().entrySet()) {
            sb.append(String.format("  %4dx  %s%n", entry.getValue(), entry.getKey()));
        }

        sb.append("\n--- Amostra de Transferências (Solicitações vs Respostas) ---\n");
        int limit = Math.min(40, analysis.transfers().size());
        for (int i = 0; i < limit; i++) {
            HidTransferRecord r = analysis.transfers().get(i);
            sb.append(String.format(
                    "  [#%04d] Addr=%d EP=0x%02X | %-28s | ReportID=0x%02X | CRC16=%-5s | %s%n",
                    r.packetIndex(),
                    r.usbDeviceAddress(),
                    r.endpoint(),
                    r.direction().getLabel(),
                    r.reportId(),
                    r.crc16Valid() ? "OK" : "N/A",
                    r.formatCompactHex(8)
            ));
        }
        return sb.toString();
    }

    /**
     * Compara duas capturas feitas em níveis de bateria diferentes para destacar bytes de resposta
     * (Device -> Host) que mudam para um mesmo cabeçalho de comando {@code [ReportID, cmd, len, status]}.
     */
    public static String compareCaptures(CaptureAnalysis first, CaptureAnalysis second) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Comparação de Capturas PCAPNG (Akko S9 Ultra) ===\n");
        sb.append("Captura A: ").append(first.sourceName())
                .append(" (baterias cmd 0x12: ").append(first.observedBatteryPercentages()).append(")\n");
        sb.append("Captura B: ").append(second.sourceName())
                .append(" (baterias cmd 0x12: ").append(second.observedBatteryPercentages()).append(")\n\n");

        Map<String, byte[]> respA = extractFirstResponseByHeader(first);
        Map<String, byte[]> respB = extractFirstResponseByHeader(second);

        boolean anyDiff = false;
        for (Map.Entry<String, byte[]> entry : respA.entrySet()) {
            String header = entry.getKey();
            byte[] a = entry.getValue();
            byte[] b = respB.get(header);
            if (b == null) {
                continue;
            }
            int minLen = Math.min(a.length, b.length);
            int compareEnd = minLen >= 64 ? 62 : minLen; // Exclui bytes 62..63 de CRC-16
            for (int idx = 4; idx < compareEnd; idx++) {
                int va = a[idx] & 0xFF;
                int vb = b[idx] & 0xFF;
                if (va != vb) {
                    anyDiff = true;
                    sb.append(String.format(
                            "Candidato em %s -> byte[%d]: Captura A = 0x%02X (%d%%) | Captura B = 0x%02X (%d%%)%n",
                            header, idx, va, va, vb, vb
                    ));
                }
            }
        }
        if (!anyDiff) {
            sb.append("Nenhuma diferença de payload encontrada entre respostas de mesmo cabeçalho.\n");
        }
        return sb.toString();
    }

    private static Map<String, byte[]> extractFirstResponseByHeader(CaptureAnalysis analysis) {
        Map<String, byte[]> map = new LinkedHashMap<>();
        for (HidTransferRecord r : analysis.transfers()) {
            if (r.direction() == Direction.DEVICE_TO_HOST && r.payload().length >= 5) {
                String header = String.format("0x%02X:0x%02X:0x%02X",
                        r.payload()[0] & 0xFF, r.payload()[1] & 0xFF, r.payload()[2] & 0xFF);
                map.putIfAbsent(header, r.payload());
            }
        }
        return map;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Uso: java br.com.wirelessbattery.diagnostics.AkkoPcapngAnalyzer <captura1.pcapng> [captura2.pcapng]");
            return;
        }
        CaptureAnalysis first = analyzeFile(Path.of(args[0]));
        System.out.println(buildReport(first));
        if (args.length >= 2) {
            CaptureAnalysis second = analyzeFile(Path.of(args[1]));
            System.out.println(buildReport(second));
            System.out.println(compareCaptures(first, second));
        }
    }
}
