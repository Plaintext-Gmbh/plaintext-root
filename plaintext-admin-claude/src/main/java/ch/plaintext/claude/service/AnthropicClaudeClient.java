/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.claude.service;

import ch.plaintext.claude.ClaudeAnfrage;
import ch.plaintext.claude.ClaudeAntwort;
import ch.plaintext.claude.ClaudeClient;
import ch.plaintext.claude.ClaudeException;
import ch.plaintext.claude.ClaudeException.Art;
import ch.plaintext.modules.ModulApiUmsetzung;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link ClaudeClient} over the Anthropic HTTP API with the JDK {@link HttpClient} (no SDK, no new
 * dependency), plus what the monitoring page needs: connection check, last rate-limit state, costs.
 *
 * <p>The key comes from {@code plaintext.claude.api-key} (typically {@code vault:anthropic.api-key})
 * and is never logged, shown or put into an exception message.
 */
@Slf4j
@Component
@ModulApiUmsetzung(beschreibung = "Calls the Anthropic Messages API with the installation's key, retries 429/529 up to twice "
        + "honouring retry-after, and counts tokens, latency and errors per calling module, model and day.",
        seiteneffekte = ModulApiUmsetzung.Seiteneffekte.AUSSEN,
        hinweise = {"Without plaintext.claude.api-key every call throws ClaudeException(NICHT_KONFIGURIERT)",
                "Blocking call without streaming; keep maxTokens moderate (timeout 120 s)",
                "Check stopGrund: refusal or max_tokens mean the text is incomplete",
                "Costs the installation real money per token"},
        beispiele = {"claudeClient.frage(\"plaintext-z-mail\", \"Summarize: ...\") -> \"Summary ...\""})
public class AnthropicClaudeClient implements ClaudeClient {

    static final String API_VERSION = "2023-06-01";
    static final int DEFAULT_MAX_TOKENS = 1024;
    static final int MAX_WIEDERHOLUNGEN = 2;
    private static final Duration MAX_WARTEZEIT = Duration.ofSeconds(30);
    private static final ObjectMapper JSON = JsonMapper.builderWithJackson2Defaults().build();
    private static final List<String> RATE_LIMIT_ARTEN = List.of("requests", "tokens", "input-tokens", "output-tokens");

    /** Sleeps between retries; replaced in tests. */
    interface Schlaefer {
        void schlafe(Duration d) throws InterruptedException;
    }

    /** One rate-limit dimension from the response headers of the last call. */
    public record RateLimit(String art, String limit, String remaining, String reset) {
    }

    /** Rate-limit headers of the last answered call. */
    public record RateLimitStand(Instant zeitpunkt, List<RateLimit> werte) {
    }

    /** Result of {@link #pruefeVerbindung()}. */
    public record Verbindung(Instant zeitpunkt, boolean ok, long latenzMs, String organisation, List<String> modelle,
                             String fehler) {
    }

    /** Costs according to the Admin API cost report. */
    public record Kosten(LocalDate von, LocalDate bis, BigDecimal usd) {
    }

    private final HttpClient http;
    private final String apiKey;
    private final String adminKey;
    private final String defaultModell;
    private final String baseUrl;
    private final ClaudeNutzungSpeicher speicher;
    private final Schlaefer schlaefer;

    private volatile RateLimitStand rateLimitStand;
    private volatile Verbindung letztePruefung;

    @Autowired
    public AnthropicClaudeClient(@Value("${plaintext.claude.api-key:}") String apiKey,
                                 @Value("${plaintext.claude.admin-key:}") String adminKey,
                                 @Value("${plaintext.claude.default-model:claude-sonnet-5-5}") String defaultModell,
                                 @Value("${plaintext.claude.base-url:https://api.anthropic.com}") String baseUrl,
                                 ClaudeNutzungSpeicher speicher) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                apiKey, adminKey, defaultModell, baseUrl, speicher, d -> Thread.sleep(d.toMillis()));
    }

    AnthropicClaudeClient(HttpClient http, String apiKey, String adminKey, String defaultModell, String baseUrl,
                          ClaudeNutzungSpeicher speicher, Schlaefer schlaefer) {
        this.http = http;
        this.apiKey = leerAlsNull(apiKey);
        this.adminKey = leerAlsNull(adminKey);
        this.defaultModell = defaultModell;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.speicher = speicher;
        this.schlaefer = schlaefer;
    }

    @Override
    public boolean isKonfiguriert() {
        return apiKey != null;
    }

    public boolean isAdminKonfiguriert() {
        return adminKey != null;
    }

    public String getDefaultModell() {
        return defaultModell;
    }

    public RateLimitStand getRateLimitStand() {
        return rateLimitStand;
    }

    public Verbindung getLetztePruefung() {
        return letztePruefung;
    }

    @Override
    public ClaudeAntwort frage(ClaudeAnfrage anfrage) {
        if (!isKonfiguriert()) {
            throw new ClaudeException(Art.NICHT_KONFIGURIERT, "plaintext.claude.api-key ist nicht gesetzt");
        }
        String modell = anfrage.modell() == null || anfrage.modell().isBlank() ? defaultModell : anfrage.modell();
        ObjectNode body = JSON.createObjectNode();
        body.put("model", modell);
        body.put("max_tokens", anfrage.maxTokens() > 0 ? anfrage.maxTokens() : DEFAULT_MAX_TOKENS);
        if (anfrage.system() != null && !anfrage.system().isBlank()) {
            body.put("system", anfrage.system());
        }
        ArrayNode messages = body.putArray("messages");
        anfrage.nachrichten().forEach(n -> messages.addObject().put("role", n.rolle()).put("content", n.text()));

        long start = System.nanoTime();
        try {
            HttpResponse<String> r = sende(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/messages"))
                    .timeout(Duration.ofSeconds(120))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)), apiKey);
            JsonNode j = lies(r.body());
            StringBuilder text = new StringBuilder();
            for (JsonNode block : j.path("content")) {
                if ("text".equals(block.path("type").asString())) {
                    text.append(block.path("text").asString());
                }
            }
            JsonNode u = j.path("usage");
            long in = u.path("input_tokens").asLong();
            long out = u.path("output_tokens").asLong();
            long cache = u.path("cache_creation_input_tokens").asLong() + u.path("cache_read_input_tokens").asLong();
            String antwortModell = j.path("model").asString(modell);
            speicher.erfasse(anfrage.quelle(), antwortModell, false, in, out, cache, millisSeit(start));
            return new ClaudeAntwort(text.toString(), antwortModell, j.path("stop_reason").asString(null), in, out, cache,
                    r.headers().firstValue("request-id").orElse(null));
        } catch (ClaudeException e) {
            speicher.erfasse(anfrage.quelle(), modell, true, 0, 0, 0, millisSeit(start));
            throw e;
        }
    }

    /** GET /v1/models and /v1/organizations/me; never throws, the result is kept for the page. */
    public Verbindung pruefeVerbindung() {
        Verbindung v;
        if (!isKonfiguriert()) {
            v = new Verbindung(Instant.now(), false, 0, null, List.of(), "Kein API-Schlüssel gesetzt (plaintext.claude.api-key)");
        } else {
            long start = System.nanoTime();
            try {
                JsonNode modelle = lies(sende(get("/v1/models?limit=100"), apiKey).body());
                long latenz = millisSeit(start);
                List<String> ids = new ArrayList<>();
                modelle.path("data").forEach(m -> ids.add(m.path("id").asString()));
                v = new Verbindung(Instant.now(), true, latenz, organisation(), ids, null);
            } catch (ClaudeException e) {
                v = new Verbindung(Instant.now(), false, millisSeit(start), null, List.of(), e.art() + ": " + e.getMessage());
            }
        }
        letztePruefung = v;
        return v;
    }

    private String organisation() {
        try {
            return lies(sende(get("/v1/organizations/me"), apiKey).body()).path("name").asString(null);
        } catch (ClaudeException e) {
            log.info("Claude: Organisation nicht lesbar: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Costs of the last 30 days (including today) from the Admin API cost report — needs
     * {@code plaintext.claude.admin-key}. Amounts come as decimal strings in cents.
     *
     * @throws ClaudeException without admin key or when the report fails
     */
    public Kosten kosten30Tage() {
        if (!isAdminKonfiguriert()) {
            throw new ClaudeException(Art.NICHT_KONFIGURIERT, "plaintext.claude.admin-key ist nicht gesetzt");
        }
        LocalDate bis = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        LocalDate von = bis.minusDays(30);
        String basis = "/v1/organizations/cost_report?bucket_width=1d&limit=31&starting_at=" + von + "T00:00:00Z&ending_at="
                + bis + "T00:00:00Z";
        BigDecimal cent = BigDecimal.ZERO;
        String seite = null;
        for (int i = 0; i < 10; i++) {
            String pfad = seite == null ? basis : basis + "&page=" + URLEncoder.encode(seite, StandardCharsets.UTF_8);
            JsonNode j = lies(sende(get(pfad), adminKey).body());
            for (JsonNode bucket : j.path("data")) {
                for (JsonNode res : bucket.path("results")) {
                    String betrag = res.path("amount").asString(null);
                    if (betrag != null && !betrag.isBlank()) {
                        cent = cent.add(new BigDecimal(betrag));
                    }
                }
            }
            if (!j.path("has_more").asBoolean(false)) {
                break;
            }
            seite = j.path("next_page").asString(null);
        }
        return new Kosten(von, bis.minusDays(1), cent.movePointLeft(2));
    }

    private HttpRequest.Builder get(String pfad) {
        return HttpRequest.newBuilder(URI.create(baseUrl + pfad)).timeout(Duration.ofSeconds(20)).GET();
    }

    /** Sends with key and version header; retries 429/529 up to {@value #MAX_WIEDERHOLUNGEN} times. */
    HttpResponse<String> sende(HttpRequest.Builder anfrage, String schluessel) {
        HttpRequest req = anfrage.header("x-api-key", schluessel).header("anthropic-version", API_VERSION).build();
        for (int versuch = 0; ; versuch++) {
            HttpResponse<String> r;
            try {
                r = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new ClaudeException(Art.NETZ, "Anthropic nicht erreichbar: " + e.getClass().getSimpleName(), null, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ClaudeException(Art.NETZ, "Unterbrochen", null, e);
            }
            merkeRateLimit(r);
            int s = r.statusCode();
            if (s >= 200 && s < 300) {
                return r;
            }
            Duration warte = retryAfter(r);
            if ((s == 429 || s == 529) && versuch < MAX_WIEDERHOLUNGEN) {
                Duration d = warte != null ? warte : Duration.ofSeconds(1L << versuch);
                try {
                    schlaefer.schlafe(d.compareTo(MAX_WARTEZEIT) > 0 ? MAX_WARTEZEIT : d);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ClaudeException(Art.NETZ, "Unterbrochen", warte, e);
                }
                continue;
            }
            throw fehler(r, warte);
        }
    }

    private static ClaudeException fehler(HttpResponse<String> r, Duration warte) {
        int s = r.statusCode();
        Art art = switch (s) {
            case 401, 403 -> Art.AUTH;
            case 429 -> Art.RATE_LIMIT;
            default -> s == 529 || s >= 500 ? Art.UEBERLASTET : Art.ANDERE;
        };
        String meldung = "HTTP " + s;
        try {
            String m = JSON.readTree(r.body()).path("error").path("message").asString(null);
            if (m != null) {
                meldung += ": " + (m.length() > 300 ? m.substring(0, 300) : m);
            }
        } catch (JacksonException | IllegalArgumentException e) {
            // kein JSON-Fehlerkoerper — der Status reicht
        }
        return new ClaudeException(art, meldung, warte, null);
    }

    private void merkeRateLimit(HttpResponse<String> r) {
        List<RateLimit> werte = new ArrayList<>();
        for (String a : RATE_LIMIT_ARTEN) {
            String p = "anthropic-ratelimit-" + a + "-";
            String limit = r.headers().firstValue(p + "limit").orElse(null);
            if (limit != null) {
                werte.add(new RateLimit(a, limit, r.headers().firstValue(p + "remaining").orElse(null),
                        r.headers().firstValue(p + "reset").orElse(null)));
            }
        }
        if (!werte.isEmpty()) {
            rateLimitStand = new RateLimitStand(Instant.now(), List.copyOf(werte));
        }
    }

    static Duration retryAfter(HttpResponse<?> r) {
        try {
            return r.headers().firstValue("retry-after").map(v -> Duration.ofSeconds(Long.parseLong(v.trim()))).orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static JsonNode lies(String body) {
        try {
            return JSON.readTree(body);
        } catch (JacksonException e) {
            throw new ClaudeException(Art.ANDERE, "Antwort ist kein JSON", null, e);
        }
    }

    private static long millisSeit(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String leerAlsNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
