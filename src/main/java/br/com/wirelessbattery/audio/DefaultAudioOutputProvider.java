package br.com.wirelessbattery.audio;

import java.util.Optional;

/**
 * Provedor somente-leitura da saída de áudio padrão do sistema operacional.
 */
public interface DefaultAudioOutputProvider {

    Optional<AudioEndpointInfo> getDefaultRenderEndpoint();
}
