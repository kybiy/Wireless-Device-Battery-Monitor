package br.com.wirelessbattery.audio;

/**
 * Informações somente-leitura do endpoint padrão de saída de áudio do Windows.
 */
public record AudioEndpointInfo(
        String endpointId,
        String friendlyName,
        String interfaceName,
        String containerId
) {}
