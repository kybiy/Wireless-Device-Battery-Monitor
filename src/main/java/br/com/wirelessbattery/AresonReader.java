package br.com.wirelessbattery;

import java.util.*;
import org.hid4java.*;

/** Consulta apenas receptores Areson 25A7, sem alterar DPI, luzes ou configuracoes. */
public final class AresonReader implements AutoCloseable {
    private final HidServices services;
    public AresonReader() {
        HidServicesSpecification spec = new HidServicesSpecification();
        spec.setAutoStart(false);
        spec.setAutoShutdown(false);
        services = HidManager.getHidServices(spec);
        services.start();
    }
    public synchronized List<BatteryStatus> poll() {
        List<BatteryStatus> found = new ArrayList<>();
        List<HidDevice> all = services.getAttachedHidDevices();
        Set<Integer> pids = new HashSet<>();
        for (HidDevice device : all) {
            int vid = device.getVendorId(), pid = device.getProductId();
            if (!AresonProtocol.supports(vid, pid) || !pids.add(pid)) continue;
            List<HidDevice> commands = new ArrayList<>(), replies = new ArrayList<>();
            for (HidDevice other : all) {
                if (other.getVendorId() != vid || other.getProductId() != pid || other.getInterfaceNumber() != 1) continue;
                if (other.getUsagePage() == 0xFF02 && other.getUsage() == 0x0002) commands.add(other);
                if (other.getUsagePage() == 0xFF01 && other.getUsage() == 0x0000) replies.add(other);
            }
            outer: for (HidDevice cmd : commands) for (HidDevice reply : replies) {
                BatteryStatus status = query(cmd, reply, pid);
                if (status != null) { found.add(status); break outer; }
            }
        }
        return found;
    }
    private BatteryStatus query(HidDevice cmd, HidDevice reply, int pid) {
        // hid4java inclui o report ID separado do payload em sendFeatureReport.
        byte[] request = AresonProtocol.request();
        byte[] payload = Arrays.copyOfRange(request, 1, request.length);
        boolean commandOpen = false, replyOpen = false;
        try {
            commandOpen = cmd.open();
            if (!commandOpen) return null;
            replyOpen = reply == cmd || reply.open();
            if (!replyOpen) return null;
            reply.setNonBlocking(false);
            for (int attempt = 0; attempt < 2; attempt++) {
                if (attempt > 0) Thread.sleep(200);
                // Descarta apenas mensagens ja recebidas, para nao confundir respostas antigas.
                reply.setNonBlocking(true);
                byte[] discard = new byte[128];
                for (int k=0; k<12 && reply.read(discard, 0)>0; k++) {}
                reply.setNonBlocking(false);
                if (cmd.sendFeatureReport(payload, request[0]) < 0) return null;
                byte[] buffer = new byte[128];
                int n = reply.read(buffer, 1800);
                BatteryStatus result = AresonProtocol.parse(pid, buffer, n);
                if (result != null) return result;
            }
        } catch (Exception e) {
            System.err.println("Falha HID: " + e.getMessage());
        } finally {
            if (replyOpen && reply != cmd) reply.close();
            if (commandOpen) cmd.close();
        }
        return null;
    }
    @Override public void close() { services.shutdown(); }
}
