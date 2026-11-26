package br.com.wirelessbattery.update;

import static org.junit.jupiter.api.Assertions.*;

import br.com.wirelessbattery.config.AppSettings;
import br.com.wirelessbattery.update.UpdateCheckResult.Status;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateCheckerServiceTest {

    @Test
    void semanticVersionParsesAndComparesCorrectly() {
        SemanticVersion v120 = SemanticVersion.parse("v1.2.0");
        SemanticVersion v130Beta = SemanticVersion.parse("1.3.0-beta.1");
        SemanticVersion v130Rc = SemanticVersion.parse("1.3.0-rc1");
        SemanticVersion v130 = SemanticVersion.parse("1.3.0");
        SemanticVersion v130Build = SemanticVersion.parse("v1.3.0+20261126");

        assertTrue(v120.compareTo(v130) < 0);
        assertTrue(v130Beta.compareTo(v130Rc) < 0);
        assertTrue(v130Rc.compareTo(v130) < 0);
        assertEquals(0, v130.compareTo(v130Build));
        assertTrue(v130Beta.isPreRelease());
        assertFalse(v130.isPreRelease());

        assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse(""));
        assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse("invalid"));
    }

    @Test
    void detectsUpdateAvailableIgnoringPrereleasesAndDraftsAndUpdatesCache(@TempDir Path tempDir) {
        String githubJson = """
                [
                  {
                    "tag_name": "v2.0.0",
                    "draft": true,
                    "prerelease": false,
                    "html_url": "https://github.com/kybiy/Wireless-Device-Battery-Monitor/releases/tag/v2.0.0",
                    "body": "Draft release"
                  },
                  {
                    "tag_name": "v1.4.0-beta.1",
                    "draft": false,
                    "prerelease": true,
                    "html_url": "https://github.com/kybiy/Wireless-Device-Battery-Monitor/releases/tag/v1.4.0-beta.1",
                    "body": "Beta"
                  },
                  {
                    "tag_name": "v1.3.0",
                    "draft": false,
                    "prerelease": false,
                    "author": { "login": "kybiy", "tag_name": "ignored-nested" },
                    "assets": [ { "name": "WirelessBattery-1.3.0.msi", "html_url": "ignored" } ],
                    "html_url": "https://github.com/kybiy/Wireless-Device-Battery-Monitor/releases/tag/v1.3.0",
                    "body": "Notas \\"v1.3.0\\"\\n- Suporte \\/ i18n\\n- Acentua\\u00E7\\u00E3o"
                  }
                ]
                """;

        Instant now = Instant.parse("2026-11-26T15:00:00Z");
        Clock fixedClock = Clock.fixed(now, ZoneOffset.UTC);
        AppSettings settings = new AppSettings(tempDir.resolve("settings.properties"));

        UpdateCheckerService service = new UpdateCheckerService(
                "1.2.0",
                url -> new UpdateCheckerService.HttpReply(200, githubJson),
                UpdateCheckerService.RELEASES_URL,
                fixedClock
        );

        UpdateCheckResult result = service.checkForUpdates(settings);
        assertEquals(Status.UPDATE_AVAILABLE, result.status());
        assertEquals("1.2.0", result.currentVersion());
        assertEquals("1.3.0", result.latestVersion());
        assertEquals("https://github.com/kybiy/Wireless-Device-Battery-Monitor/releases/tag/v1.3.0", result.releaseUrl());
        assertEquals("Notas \"v1.3.0\"\n- Suporte / i18n\n- Acentuação", result.releaseNotes());
        assertEquals(now.toEpochMilli(), result.checkedAtEpochMillis());

        assertEquals(now.toEpochMilli(), settings.getLastUpdateCheckEpochMillis());
        assertEquals("1.3.0", settings.getCachedLatestVersion());
        assertEquals(result.releaseUrl(), settings.getCachedReleaseUrl());
        assertEquals(result.releaseNotes(), settings.getCachedReleaseNotes());
    }

    @Test
    void reportsUpToDateAndHandlesRateLimitAndOfflineWithCachedFallback(@TempDir Path tempDir) {
        AppSettings settings = new AppSettings(tempDir.resolve("settings.properties"));
        settings.setCachedLatestVersion("1.3.0");
        settings.setCachedReleaseUrl("https://github.com/kybiy/Wireless-Device-Battery-Monitor/releases/tag/v1.3.0");
        settings.setCachedReleaseNotes("Cached notes");

        String upToDateJson = """
                [{"tag_name":"v1.3.0","draft":false,"prerelease":false,"html_url":"https://example.com/1.3.0","body":"Stable"}]
                """;
        UpdateCheckerService upToDateService = new UpdateCheckerService(
                "1.3.0",
                url -> new UpdateCheckerService.HttpReply(200, upToDateJson)
        );
        AppSettings cleanSettings = new AppSettings(tempDir.resolve("settings-up-to-date.properties"));
        assertEquals(Status.UP_TO_DATE, upToDateService.checkForUpdates(cleanSettings).status());

        UpdateCheckerService rateLimitedService = new UpdateCheckerService(
                "1.2.0",
                url -> new UpdateCheckerService.HttpReply(429, "{\"message\":\"API rate limit exceeded\"}")
        );
        UpdateCheckResult rateResult = rateLimitedService.checkForUpdates(settings);
        assertEquals(Status.RATE_LIMITED, rateResult.status());
        assertEquals("1.3.0", rateResult.latestVersion());
        assertEquals("Cached notes", rateResult.releaseNotes());

        UpdateCheckerService offlineService = new UpdateCheckerService(
                "1.2.0",
                url -> {
                    throw new IOException("Connection timed out");
                }
        );
        UpdateCheckResult offlineResult = offlineService.checkForUpdates(settings);
        assertEquals(Status.OFFLINE_OR_ERROR, offlineResult.status());
        assertEquals("1.3.0", offlineResult.latestVersion());
        assertTrue(offlineResult.errorMessage().contains("Connection timed out"));
    }

    @Test
    void enforces24HourAutoCheckThrottle(@TempDir Path tempDir) {
        AppSettings settings = new AppSettings(tempDir.resolve("settings.properties"));
        long t0 = Instant.parse("2026-11-26T10:00:00Z").toEpochMilli();

        assertFalse(UpdateCheckerService.shouldAutoCheck(settings, t0));
        settings.setAutoCheckUpdates(true);
        assertTrue(UpdateCheckerService.shouldAutoCheck(settings, t0));

        AtomicInteger calls = new AtomicInteger();
        String json = """
                [{"tag_name":"v1.2.0","draft":false,"prerelease":false,"html_url":"https://example.com","body":"OK"}]
                """;
        Clock clock23h = Clock.fixed(Instant.ofEpochMilli(t0 + UpdateCheckerService.AUTO_CHECK_INTERVAL_MILLIS - 1_000L),
                ZoneOffset.UTC);
        UpdateCheckerService serviceAtT0 = new UpdateCheckerService(
                "1.2.0",
                url -> {
                    calls.incrementAndGet();
                    return new UpdateCheckerService.HttpReply(200, json);
                },
                UpdateCheckerService.RELEASES_URL,
                Clock.fixed(Instant.ofEpochMilli(t0), ZoneOffset.UTC)
        );

        assertTrue(serviceAtT0.checkIfDue(settings).isPresent());
        assertEquals(1, calls.get());

        // 23h59m59s depois -> não deve consultar novamente
        UpdateCheckerService serviceBefore24h = new UpdateCheckerService(
                "1.2.0",
                url -> {
                    calls.incrementAndGet();
                    return new UpdateCheckerService.HttpReply(200, json);
                },
                UpdateCheckerService.RELEASES_URL,
                clock23h
        );
        assertTrue(serviceBefore24h.checkIfDue(settings).isEmpty());
        assertEquals(1, calls.get());

        // Exatamente 24h depois -> deve consultar
        UpdateCheckerService serviceAt24h = new UpdateCheckerService(
                "1.2.0",
                url -> {
                    calls.incrementAndGet();
                    return new UpdateCheckerService.HttpReply(200, json);
                },
                UpdateCheckerService.RELEASES_URL,
                Clock.fixed(Instant.ofEpochMilli(t0 + UpdateCheckerService.AUTO_CHECK_INTERVAL_MILLIS), ZoneOffset.UTC)
        );
        assertTrue(serviceAt24h.checkIfDue(settings).isPresent());
        assertEquals(2, calls.get());
    }
}
