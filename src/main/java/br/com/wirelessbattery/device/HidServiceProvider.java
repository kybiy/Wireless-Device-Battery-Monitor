package br.com.wirelessbattery.device;

import br.com.wirelessbattery.diagnostics.AppLogger;
import org.hid4java.HidManager;
import org.hid4java.HidServices;
import org.hid4java.HidServicesSpecification;

/**
 * Gerencia uma instância única e compartilhada de {@link HidServices} para todos os drivers
 * reais e para a ferramenta de diagnóstico HID.
 */
public final class HidServiceProvider implements AutoCloseable {
    private final AppLogger logger;
    private HidServices services;
    private boolean closed;

    public HidServiceProvider(AppLogger logger) {
        this.logger = logger;
    }

    public synchronized HidServices getServices() {
        if (closed) {
            throw new IllegalStateException("HidServiceProvider já foi encerrado.");
        }
        if (services == null) {
            HidServicesSpecification spec = new HidServicesSpecification();
            spec.setAutoStart(false);
            spec.setAutoShutdown(false);
            services = HidManager.getHidServices(spec);
            services.start();
            if (logger != null) {
                logger.info("Serviço HID (hid4java) inicializado.");
            }
        }
        return services;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (services != null) {
            try {
                services.shutdown();
                if (logger != null) {
                    logger.info("Serviço HID encerrado com segurança.");
                }
            } catch (Exception e) {
                if (logger != null) {
                    logger.error("Erro ao encerrar serviço HID", e);
                }
            }
            services = null;
        }
    }
}
