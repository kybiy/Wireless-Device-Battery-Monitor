package br.com.wirelessbattery.update;

/**
 * Resultado de uma consulta de atualização de versão no GitHub Releases.
 */
public record UpdateCheckResult(
        Status status,
        String currentVersion,
        String latestVersion,
        String releaseUrl,
        String releaseNotes,
        long checkedAtEpochMillis,
        String errorMessage
) {
    public enum Status {
        UP_TO_DATE,
        UPDATE_AVAILABLE,
        OFFLINE_OR_ERROR,
        RATE_LIMITED
    }

    public UpdateCheckResult {
        status = status != null ? status : Status.OFFLINE_OR_ERROR;
        currentVersion = currentVersion == null ? "" : currentVersion;
        latestVersion = latestVersion == null ? "" : latestVersion;
        releaseUrl = releaseUrl == null ? "" : releaseUrl;
        releaseNotes = releaseNotes == null ? "" : releaseNotes;
        errorMessage = errorMessage == null ? "" : errorMessage;
    }
}
