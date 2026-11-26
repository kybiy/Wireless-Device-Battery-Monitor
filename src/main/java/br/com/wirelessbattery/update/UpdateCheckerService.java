package br.com.wirelessbattery.update;

import br.com.wirelessbattery.config.AppSettings;
import br.com.wirelessbattery.config.AppVersion;
import br.com.wirelessbattery.update.UpdateCheckResult.Status;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Serviço de verificação de atualizações públicas no GitHub Releases sem envio de telemetria
 * ou tokens, com cache de 24 horas e tratamento offline/rate-limit.
 */
public final class UpdateCheckerService {

    public static final String RELEASES_URL =
            "https://api.github.com/repos/kybiy/Wireless-Device-Battery-Monitor/releases?per_page=10";
    public static final long AUTO_CHECK_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    @FunctionalInterface
    public interface HttpFetcher {
        HttpReply fetch(String url) throws IOException, InterruptedException;
    }

    public record HttpReply(int statusCode, String body) {
    }

    private record ParsedRelease(
            String tagName,
            String htmlUrl,
            boolean draft,
            boolean prerelease,
            String body,
            SemanticVersion version
    ) {
    }

    private final String currentVersion;
    private final HttpFetcher fetcher;
    private final String endpointUrl;
    private final Clock clock;

    public UpdateCheckerService() {
        this(AppVersion.getVersion(), null, RELEASES_URL, Clock.systemUTC());
    }

    public UpdateCheckerService(String currentVersion, HttpFetcher fetcher) {
        this(currentVersion, fetcher, RELEASES_URL, Clock.systemUTC());
    }

    public UpdateCheckerService(String currentVersion, HttpFetcher fetcher, String endpointUrl, Clock clock) {
        this.currentVersion = (currentVersion == null || currentVersion.isBlank())
                ? AppVersion.getVersion()
                : currentVersion.trim();
        this.fetcher = fetcher != null ? fetcher : defaultFetcher(this.currentVersion);
        this.endpointUrl = (endpointUrl == null || endpointUrl.isBlank()) ? RELEASES_URL : endpointUrl;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public static boolean shouldAutoCheck(AppSettings settings, long nowEpochMillis) {
        if (settings == null || !settings.isAutoCheckUpdates()) {
            return false;
        }
        long lastCheck = settings.getLastUpdateCheckEpochMillis();
        if (lastCheck <= 0L) {
            return true;
        }
        return (nowEpochMillis - lastCheck) >= AUTO_CHECK_INTERVAL_MILLIS;
    }

    public Optional<UpdateCheckResult> checkIfDue(AppSettings settings) {
        if (!shouldAutoCheck(settings, clock.millis())) {
            return Optional.empty();
        }
        return Optional.of(checkForUpdates(settings));
    }

    public UpdateCheckResult checkForUpdates(AppSettings settings) {
        long now = clock.millis();
        try {
            HttpReply reply = fetcher.fetch(endpointUrl);
            if (reply == null) {
                return cachedErrorResult(Status.OFFLINE_OR_ERROR, settings, now, "Empty HTTP reply");
            }
            int code = reply.statusCode();
            if (code == 403 || code == 429) {
                return cachedErrorResult(Status.RATE_LIMITED, settings, now, "HTTP " + code);
            }
            if (code < 200 || code >= 300) {
                return cachedErrorResult(Status.OFFLINE_OR_ERROR, settings, now, "HTTP " + code);
            }

            List<ParsedRelease> releases = parseReleases(reply.body());
            ParsedRelease latestStable = selectLatestStable(releases);
            if (latestStable == null) {
                return new UpdateCheckResult(
                        Status.UP_TO_DATE,
                        normalizedVersion(currentVersion),
                        normalizedVersion(currentVersion),
                        "",
                        "",
                        now,
                        ""
                );
            }

            String latestStr = latestStable.version().toString();
            if (settings != null) {
                settings.setLastUpdateCheckEpochMillis(now);
                settings.setCachedLatestVersion(latestStr);
                settings.setCachedReleaseUrl(latestStable.htmlUrl());
                settings.setCachedReleaseNotes(latestStable.body());
            }

            SemanticVersion currentSemVer = SemanticVersion.parse(currentVersion);
            Status status = latestStable.version().compareTo(currentSemVer) > 0
                    ? Status.UPDATE_AVAILABLE
                    : Status.UP_TO_DATE;

            return new UpdateCheckResult(
                    status,
                    currentSemVer.toString(),
                    latestStr,
                    latestStable.htmlUrl(),
                    latestStable.body(),
                    now,
                    ""
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return cachedErrorResult(Status.OFFLINE_OR_ERROR, settings, now, e.getMessage());
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return cachedErrorResult(Status.OFFLINE_OR_ERROR, settings, now, msg);
        }
    }

    private UpdateCheckResult cachedErrorResult(Status status, AppSettings settings, long now, String error) {
        String cachedVer = settings != null ? settings.getCachedLatestVersion() : "";
        String cachedUrl = settings != null ? settings.getCachedReleaseUrl() : "";
        String cachedNotes = settings != null ? settings.getCachedReleaseNotes() : "";
        return new UpdateCheckResult(
                status,
                normalizedVersion(currentVersion),
                cachedVer,
                cachedUrl,
                cachedNotes,
                now,
                error
        );
    }

    private static String normalizedVersion(String version) {
        return SemanticVersion.tryParse(version).map(SemanticVersion::toString).orElse(version);
    }

    private static ParsedRelease selectLatestStable(List<ParsedRelease> releases) {
        ParsedRelease best = null;
        for (ParsedRelease candidate : releases) {
            if (candidate.draft() || candidate.prerelease() || candidate.version().isPreRelease()) {
                continue;
            }
            if (best == null || candidate.version().compareTo(best.version()) > 0) {
                best = candidate;
            }
        }
        return best;
    }

    private static List<ParsedRelease> parseReleases(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Resposta JSON vazia");
        }
        Object parsed = new MiniJsonParser(json).parseRoot();
        List<Map<String, Object>> objects = new ArrayList<>();
        if (parsed instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    objects.add(castObjectMap(map));
                }
            }
        } else if (parsed instanceof Map<?, ?> map) {
            objects.add(castObjectMap(map));
        } else {
            throw new IllegalArgumentException("JSON inesperado para releases");
        }

        List<ParsedRelease> releases = new ArrayList<>();
        for (Map<String, Object> obj : objects) {
            String tagName = asString(obj.get("tag_name"));
            Optional<SemanticVersion> version = SemanticVersion.tryParse(tagName);
            if (version.isEmpty()) {
                continue;
            }
            String htmlUrl = asString(obj.get("html_url"));
            boolean draft = asBoolean(obj.get("draft"));
            boolean prerelease = asBoolean(obj.get("prerelease"));
            String body = asString(obj.get("body"));
            releases.add(new ParsedRelease(tagName, htmlUrl, draft, prerelease, body, version.get()));
        }
        return releases;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castObjectMap(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : "";
    }

    private static boolean asBoolean(Object value) {
        return value instanceof Boolean b && b;
    }

    private static HttpFetcher defaultFetcher(String userAgentVersion) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return url -> {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(HTTP_TIMEOUT)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "WirelessBatteryMonitor/" + userAgentVersion)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new HttpReply(response.statusCode(), response.body());
        };
    }

    /**
     * Parser JSON recursivo mínimo sem dependências externas para lidar com payloads
     * da API do GitHub contendo objetos/arrays aninhados e escapes Unicode.
     */
    private static final class MiniJsonParser {
        private final String src;
        private int pos = 0;

        MiniJsonParser(String src) {
            this.src = Objects.requireNonNull(src);
        }

        Object parseRoot() {
            skipWhitespace();
            Object value = parseValue();
            skipWhitespace();
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (pos >= src.length()) {
                throw new IllegalArgumentException("Fim inesperado do JSON");
            }
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> {
                    if (c == '-' || Character.isDigit(c)) {
                        yield parseNumber();
                    }
                    throw new IllegalArgumentException("Caractere inesperado no JSON: " + c + " na posição " + pos);
                }
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                } else if (peek('}')) {
                    pos++;
                    break;
                } else {
                    throw new IllegalArgumentException("Esperado ',' ou '}' na posição " + pos);
                }
            }
            return map;
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                } else if (peek(']')) {
                    pos++;
                    break;
                } else {
                    throw new IllegalArgumentException("Esperado ',' ou ']' na posição " + pos);
                }
            }
            return list;
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= src.length()) {
                        throw new IllegalArgumentException("Escape incompleto no JSON");
                    }
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"', '\\', '/' -> sb.append(esc);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > src.length()) {
                                throw new IllegalArgumentException("Escape \\u inválido no JSON");
                            }
                            String hex = src.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> throw new IllegalArgumentException("Escape desconhecido: \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("String JSON não terminada");
        }

        private Boolean parseBoolean() {
            if (src.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (src.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Booleano inválido na posição " + pos);
        }

        private Object parseNull() {
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IllegalArgumentException("Literal null inválido na posição " + pos);
        }

        private String parseNumber() {
            int start = pos;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (Character.isDigit(c) || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                    pos++;
                } else {
                    break;
                }
            }
            return src.substring(start, pos);
        }

        private void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        private void expect(char expected) {
            if (pos >= src.length() || src.charAt(pos) != expected) {
                throw new IllegalArgumentException("Esperado '" + expected + "' na posição " + pos);
            }
            pos++;
        }

        private boolean peek(char ch) {
            return pos < src.length() && src.charAt(pos) == ch;
        }
    }
}
