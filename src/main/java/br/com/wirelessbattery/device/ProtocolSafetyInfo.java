package br.com.wirelessbattery.device;

import java.util.Objects;

/**
 * Contrato declarativo de segurança do protocolo de um driver.
 */
public record ProtocolSafetyInfo(
        QueryMechanism mechanism,
        boolean strictlyPassive,
        boolean requiresHidOutputWrite,
        boolean allowedInReadOnlyMode,
        boolean requiresFirstUseNotice,
        String protocolSummary,
        String referenceUrl
) {
    public ProtocolSafetyInfo {
        Objects.requireNonNull(mechanism, "mechanism não pode ser nulo");
        protocolSummary = protocolSummary != null ? protocolSummary : "";
        referenceUrl = referenceUrl != null ? referenceUrl : "";
    }

    public static ProtocolSafetyInfo passiveOs(String summary, String referenceUrl) {
        return new ProtocolSafetyInfo(
                QueryMechanism.OS_PASSIVE_PROPERTY,
                true,
                false,
                true,
                false,
                summary,
                referenceUrl
        );
    }
}
