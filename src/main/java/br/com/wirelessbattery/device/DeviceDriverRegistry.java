package br.com.wirelessbattery.device;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.hid4java.HidDevice;

/**
 * Registro central e inteligente de drivers de dispositivos.
 *
 * Responsabilidades:
 * - Manter metadados declarativos de cada driver (prioridade, se é opcional, capacidade de bateria,
 *   interface esperada e validador de VID/PID).
 * - Instanciar drivers sob demanda.
 * - Evitar conflitos de hardware garantindo que dois drivers nunca reivindiquem o mesmo par VID:PID.
 */
public final class DeviceDriverRegistry {

    public interface VidPidMatcher {
        boolean matches(int vid, int pid);
    }

    public record DriverRegistration(
            String driverId,
            String displayName,
            int priority,
            boolean optional,
            boolean supportsBatteryQuery,
            int expectedInterface,
            VidPidMatcher matcher,
            Supplier<BatteryDevice> factory
    ) {
        public DriverRegistration {
            Objects.requireNonNull(driverId, "driverId não pode ser nulo");
            Objects.requireNonNull(displayName, "displayName não pode ser nulo");
            Objects.requireNonNull(matcher, "matcher não pode ser nulo");
            Objects.requireNonNull(factory, "factory não pode ser nulo");
        }
    }

    public record ActiveDriverBind(
            DriverRegistration registration,
            BatteryDevice driver,
            boolean hardwareDetected
    ) {}

    private final List<DriverRegistration> registrations = new ArrayList<>();
    private final Map<String, BatteryDevice> instances = new LinkedHashMap<>();

    public synchronized void register(DriverRegistration registration) {
        Objects.requireNonNull(registration, "registration não pode ser nulo");
        registrations.removeIf(r -> r.driverId().equals(registration.driverId()));
        registrations.add(registration);
        registrations.sort(Comparator.comparingInt(DriverRegistration::priority));
    }

    public synchronized List<DriverRegistration> getRegistrations() {
        return Collections.unmodifiableList(new ArrayList<>(registrations));
    }

    /**
     * Resolve quais drivers devem ser consultados no ciclo atual com base nos dispositivos HID
     * conectados e na lista de dispositivos já conhecidos na sessão.
     *
     * Garante isolamento: se um VID:PID for reivindicado por um driver de maior prioridade,
     * nenhum driver subsequente poderá reivindicá-lo.
     */
    public synchronized List<ActiveDriverBind> resolveActiveDrivers(
            List<HidDevice> attachedHidDevices,
            Set<String> previouslySeenDriverIds
    ) {
        Set<Long> claimedVidPids = new HashSet<>();
        List<ActiveDriverBind> result = new ArrayList<>();

        for (DriverRegistration reg : registrations) {
            boolean detected = false;
            if (attachedHidDevices != null) {
                for (HidDevice d : attachedHidDevices) {
                    int vid = d.getVendorId() & 0xFFFF;
                    int pid = d.getProductId() & 0xFFFF;
                    long key = (((long) vid) << 16) | pid;
                    if (!claimedVidPids.contains(key) && reg.matcher().matches(vid, pid)) {
                        detected = true;
                        claimedVidPids.add(key);
                    }
                }
            }

            boolean wasSeenBefore = previouslySeenDriverIds != null
                    && previouslySeenDriverIds.contains(reg.driverId());

            // Drivers opcionais só são instanciados e exibidos
            // quando o receptor correspondente é detectado (ou se já tiverem sido conectados na sessão).
            if (reg.optional() && !detected && !wasSeenBefore) {
                continue;
            }

            BatteryDevice instance = instances.computeIfAbsent(reg.driverId(), id -> reg.factory().get());
            result.add(new ActiveDriverBind(reg, instance, detected));
        }

        return result;
    }

    /**
     * Retorna as instâncias dos drivers prioritários (não opcionais) mais quaisquer drivers
     * opcionais que já tenham sido instanciados durante a sessão.
     */
    public synchronized List<BatteryDevice> getKnownDriverInstances() {
        for (DriverRegistration reg : registrations) {
            if (!reg.optional()) {
                instances.computeIfAbsent(reg.driverId(), id -> reg.factory().get());
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(instances.values()));
    }
}
