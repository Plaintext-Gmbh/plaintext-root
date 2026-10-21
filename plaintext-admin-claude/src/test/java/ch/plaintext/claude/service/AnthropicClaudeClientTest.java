/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.claude.service;

import ch.plaintext.claude.ClaudeAnfrage;
import ch.plaintext.claude.ClaudeAntwort;
import ch.plaintext.claude.ClaudeException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Client against a local fake of the Anthropic API — never against the real one. */
class AnthropicClaudeClientTest {

    private static final String KEY = "test-key-nicht-echt";

    private HttpServer server;
    private String baseUrl;
    private final ClaudeNutzungSpeicher speicher = mock(ClaudeNutzungSpeicher.class);
    private final List<Duration> geschlafen = new ArrayList<>();
    /** Answers per path, consumed in order; the last one repeats. */
    private final Map<String, List<Antwort>> antworten = new ConcurrentHashMap<>();
    private final List<String> empfangeneKoepfe = new CopyOnWriteArrayList<>();
    private final List<String> empfangeneKoerper = new CopyOnWriteArrayList<>();

    record Antwort(int status, String body, Map<String, String> header) {
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::bediene);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopp() {
        server.stop(0);
    }

    private void bediene(HttpExchange ex) throws IOException {
        empfangeneKoepfe.add(ex.getRequestHeaders().getFirst("x-api-key") + "|" + ex.getRequestHeaders().getFirst("anthropic-version"));
        empfangeneKoerper.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        List<Antwort> liste = antworten.get(ex.getRequestURI().getPath());
        Antwort a = liste == null ? new Antwort(404, "{}", Map.of()) : (liste.size() > 1 ? liste.remove(0) : liste.get(0));
        a.header().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        byte[] b = a.body().getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(a.status(), b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private void antworte(String pfad, Antwort... a) {
        antworten.put(pfad, new ArrayList<>(List.of(a)));
    }

    private AnthropicClaudeClient client(String key, String adminKey) {
        return new AnthropicClaudeClient(HttpClient.newHttpClient(), key, adminKey, "claude-sonnet-5-5", baseUrl,
                speicher, geschlafen::add);
    }

    private static final String OK_BODY = """
            {"model":"claude-sonnet-5-5","stop_reason":"end_turn",
             "content":[{"type":"text","text":"Ja"},{"type":"text","text":"!"}],
             "usage":{"input_tokens":12,"output_tokens":3,"cache_creation_input_tokens":5,"cache_read_input_tokens":7}}""";

    @Test
    void frageLiefertTextTokensUndMerktRateLimit() {
        antworte("/v1/messages", new Antwort(200, OK_BODY, Map.of("request-id", "req_1",
                "anthropic-ratelimit-requests-limit", "50", "anthropic-ratelimit-requests-remaining", "49",
                "anthropic-ratelimit-requests-reset", "2026-10-21T20:00:00Z")));
        AnthropicClaudeClient c = client(KEY, null);

        ClaudeAntwort a = c.frage(new ClaudeAnfrage("test", null, "Sei kurz.",
                List.of(ClaudeAnfrage.Nachricht.benutzer("Hallo")), 20));

        assertEquals("Ja!", a.text());
        assertEquals("claude-sonnet-5-5", a.modell());
        assertEquals("end_turn", a.stopGrund());
        assertEquals(12, a.inputTokens());
        assertEquals(3, a.outputTokens());
        assertEquals(12, a.cacheTokens());
        assertEquals("req_1", a.requestId());
        assertEquals(KEY + "|2023-06-01", empfangeneKoepfe.get(0));
        String body = empfangeneKoerper.get(0);
        assertTrue(body.contains("\"max_tokens\":20") && body.contains("\"system\":\"Sei kurz.\"")
                && body.contains("\"model\":\"claude-sonnet-5-5\"") && body.contains("\"role\":\"user\""), body);
        AnthropicClaudeClient.RateLimit r = c.getRateLimitStand().werte().get(0);
        assertEquals(new AnthropicClaudeClient.RateLimit("requests", "50", "49", "2026-10-21T20:00:00Z"), r);
        verify(speicher).erfasse(eq("test"), eq("claude-sonnet-5-5"), eq(false), eq(12L), eq(3L), eq(12L), anyLong());
    }

    @Test
    void bequemlichkeitNimmtStandardwerte() {
        antworte("/v1/messages", new Antwort(200, OK_BODY, Map.of()));
        assertEquals("Ja!", client(KEY, null).frage("test", "Hallo"));
        assertTrue(empfangeneKoerper.get(0).contains("\"max_tokens\":1024"));
    }

    @Test
    void ohneSchluesselNichtKonfiguriertUndKeinAufruf() {
        AnthropicClaudeClient c = client("  ", null);
        assertFalse(c.isKonfiguriert());
        ClaudeException e = assertThrows(ClaudeException.class, () -> c.frage("test", "Hallo"));
        assertEquals(ClaudeException.Art.NICHT_KONFIGURIERT, e.art());
        assertTrue(empfangeneKoepfe.isEmpty());
        assertFalse(c.pruefeVerbindung().ok());
    }

    @Test
    void auth401WirdNichtWiederholtUndAlsFehlerGezaehlt() {
        antworte("/v1/messages", new Antwort(401, "{\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}", Map.of()));
        ClaudeException e = assertThrows(ClaudeException.class, () -> client(KEY, null).frage("test", "Hallo"));
        assertEquals(ClaudeException.Art.AUTH, e.art());
        assertEquals("HTTP 401: invalid x-api-key", e.getMessage());
        assertFalse(e.getMessage().contains(KEY));
        assertEquals(1, empfangeneKoepfe.size());
        verify(speicher).erfasse(eq("test"), eq("claude-sonnet-5-5"), eq(true), eq(0L), eq(0L), eq(0L), anyLong());
    }

    @Test
    void rateLimit429WartetRetryAfterUndGelingtBeimDrittenVersuch() {
        Antwort zuViel = new Antwort(429, "{\"error\":{\"message\":\"rate\"}}", Map.of("retry-after", "3"));
        antworte("/v1/messages", zuViel, new Antwort(529, "{}", Map.of()), new Antwort(200, OK_BODY, Map.of()));
        assertEquals("Ja!", client(KEY, null).frage("test", "Hallo"));
        assertEquals(List.of(Duration.ofSeconds(3), Duration.ofSeconds(2)), geschlafen);
    }

    @Test
    void rateLimitNachZweiWiederholungenGibtAufMitRetryAfter() {
        antworte("/v1/messages", new Antwort(429, "{}", Map.of("retry-after", "120")));
        ClaudeException e = assertThrows(ClaudeException.class, () -> client(KEY, null).frage("test", "Hallo"));
        assertEquals(ClaudeException.Art.RATE_LIMIT, e.art());
        assertEquals(Duration.ofSeconds(120), e.retryAfter());
        assertEquals(3, empfangeneKoepfe.size());
        assertEquals(List.of(Duration.ofSeconds(30), Duration.ofSeconds(30)), geschlafen); // gedeckelt
    }

    @Test
    void ueberlastetUndAndere() {
        antworte("/v1/messages", new Antwort(500, "kein json", Map.of()));
        assertEquals(ClaudeException.Art.UEBERLASTET,
                assertThrows(ClaudeException.class, () -> client(KEY, null).frage("test", "x")).art());
        antworte("/v1/messages", new Antwort(400, "{}", Map.of()));
        assertEquals(ClaudeException.Art.ANDERE,
                assertThrows(ClaudeException.class, () -> client(KEY, null).frage("test", "x")).art());
    }

    @Test
    void netzfehler() {
        server.stop(0);
        ClaudeException e = assertThrows(ClaudeException.class, () -> client(KEY, null).frage("test", "Hallo"));
        assertEquals(ClaudeException.Art.NETZ, e.art());
    }

    @Test
    void pruefeVerbindungListetModelleUndOrganisation() {
        antworte("/v1/models", new Antwort(200, "{\"data\":[{\"id\":\"claude-opus-5-5\"},{\"id\":\"claude-haiku-5-5\"}]}",
                Map.of("anthropic-ratelimit-tokens-limit", "1000")));
        antworte("/v1/organizations/me", new Antwort(200, "{\"name\":\"Plaintext GmbH\"}", Map.of()));
        AnthropicClaudeClient c = client(KEY, null);

        AnthropicClaudeClient.Verbindung v = c.pruefeVerbindung();

        assertTrue(v.ok());
        assertEquals(List.of("claude-opus-5-5", "claude-haiku-5-5"), v.modelle());
        assertEquals("Plaintext GmbH", v.organisation());
        assertNull(v.fehler());
        assertEquals(v, c.getLetztePruefung());
        assertEquals("tokens", c.getRateLimitStand().werte().get(0).art());
        verify(speicher, never()).erfasse(eq("test"), eq("x"), eq(false), anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void pruefeVerbindungMeldetFehlerOhneWurf() {
        antworte("/v1/models", new Antwort(401, "{}", Map.of()));
        AnthropicClaudeClient.Verbindung v = client(KEY, null).pruefeVerbindung();
        assertFalse(v.ok());
        assertTrue(v.fehler().startsWith("AUTH"), v.fehler());
    }

    @Test
    void kostenSummiertCentUeberSeitenMitAdminSchluessel() {
        AtomicInteger seite = new AtomicInteger();
        server.removeContext("/");
        server.createContext("/", ex -> {
            empfangeneKoepfe.add(ex.getRequestHeaders().getFirst("x-api-key") + "|" + ex.getRequestURI().getQuery());
            String body = seite.getAndIncrement() == 0
                    ? "{\"data\":[{\"results\":[{\"amount\":\"150.5\",\"currency\":\"USD\"},{\"amount\":\"49.5\"}]}],\"has_more\":true,\"next_page\":\"p2\"}"
                    : "{\"data\":[{\"results\":[{\"amount\":\"100\"}]}],\"has_more\":false}";
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        AnthropicClaudeClient c = client(KEY, "admin-test");

        AnthropicClaudeClient.Kosten k = c.kosten30Tage();

        assertEquals(0, new BigDecimal("3.00").compareTo(k.usd()));
        assertTrue(empfangeneKoepfe.get(0).startsWith("admin-test|bucket_width=1d&limit=31"), empfangeneKoepfe.get(0));
        assertTrue(empfangeneKoepfe.get(1).endsWith("&page=p2"));
        assertEquals(30, k.von().until(k.bis()).getDays() + 1);
    }

    @Test
    void kostenOhneAdminSchluessel() {
        AnthropicClaudeClient c = client(KEY, "");
        assertFalse(c.isAdminKonfiguriert());
        assertEquals(ClaudeException.Art.NICHT_KONFIGURIERT, assertThrows(ClaudeException.class, c::kosten30Tage).art());
    }
}
