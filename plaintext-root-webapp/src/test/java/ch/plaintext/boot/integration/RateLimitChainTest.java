/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.DefaultResponseErrorHandler;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Card 303 — integration test through the <b>real</b> filter chain.
 *
 * <p>The existing {@code RateLimitFilterTest} tests the filter in isolation and therefore
 * structurally could not reveal that in the running application the filter was registered behind the
 * {@code springSecurityFilterChain}, which made the login branches dead code.
 * This test sends real HTTP requests against the running server.
 *
 * <p>The requests come in over loopback (127.0.0.1) — which is a trusted proxy by default, so the
 * test can reproduce the PROD topology:
 * {@code X-Forwarded-For: <vom Angreifer gesetzt>, <von Cloudflare angehaengte echte IP>,
 * <vom nginx angehaengter Tunnel-Host>}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "plaintext.rate-limit.login.max-requests=5",
        "plaintext.rate-limit.login.window-seconds=60",
        "plaintext.rate-limit.api.max-requests=5",
        "plaintext.rate-limit.api.window-seconds=60"
})
class RateLimitChainTest {

    private static final int LIMIT = 5;

    /** Address that the nginx on the NAS appends — a trusted hop. */
    private static final String PROXY_HOP = "192.168.1.224";


    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        EmbeddedPg.registrieren(registry, "ratelimitchaintest");
    }

    @LocalServerPort
    private int port;

    private final AtomicInteger spoofCounter = new AtomicInteger();

    private RestTemplate client() {
        RestTemplate template = new RestTemplate();
        // No exceptions on 4xx/5xx — we want to evaluate the status code.
        template.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false;
            }
        });
        // Do not follow redirects, otherwise the client swallows the filter's response.
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        template.setRequestFactory(factory);
        return template;
    }

    private ResponseEntity<String> post(String path, String forwardedFor, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        if (forwardedFor != null) {
            headers.set("X-Forwarded-For", forwardedFor);
        }
        return client().exchange("http://localhost:" + port + path, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String path, String forwardedFor) {
        HttpHeaders headers = new HttpHeaders();
        if (forwardedFor != null) {
            headers.set("X-Forwarded-For", forwardedFor);
        }
        return client().exchange("http://localhost:" + port + path, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    /** Reproduces the PROD chain; {@code spoofed=true} additionally sets a forged value. */
    private String forwardedFor(String realClientIp, boolean spoofed) {
        String tail = realClientIp + ", " + PROXY_HOP;
        return spoofed ? "1.2.3." + (spoofCounter.incrementAndGet() % 250) + ", " + tail : tail;
    }

    @Test
    @DisplayName("Befund 1: POST /login wird limitiert — der Filter laeuft vor der Security-Chain")
    void loginIsRateLimited() {
        String xff = forwardedFor("198.51.100.11", false);
        String body = "username=nobody&password=wrong";
        for (int i = 0; i < LIMIT; i++) {
            assertNotEquals(429, post("/login", xff, body).getStatusCode().value(),
                    "Request " + (i + 1) + " haette noch durchgehen muessen");
        }
        assertEquals(429, post("/login", xff, body).getStatusCode().value(),
                "Nach " + LIMIT + " Fehl-Logins muss ein 429 kommen");
    }

    @Test
    @DisplayName("Befund 1: POST /ott/generate wird limitiert (Magic-Link-Mailbombing)")
    void magicLinkGenerationIsRateLimited() {
        String xff = forwardedFor("198.51.100.12", false);
        String body = "username=nobody@example.com";
        for (int i = 0; i < LIMIT; i++) {
            assertNotEquals(429, post("/ott/generate", xff, body).getStatusCode().value());
        }
        assertEquals(429, post("/ott/generate", xff, body).getStatusCode().value());
    }

    @Test
    @DisplayName("Befund 2: ein wechselnder, gefaelschter X-Forwarded-For umgeht das Limit nicht")
    void spoofedForwardedForDoesNotBypassTheLimit() {
        String body = "username=nobody&password=wrong";
        for (int i = 0; i < LIMIT; i++) {
            assertNotEquals(429,
                    post("/login", forwardedFor("198.51.100.13", true), body).getStatusCode().value());
        }
        assertEquals(429,
                post("/login", forwardedFor("198.51.100.13", true), body).getStatusCode().value(),
                "Der gefaelschte XFF-Prefix darf keinen frischen Bucket erzeugen");
    }

    @Test
    @DisplayName("Befund 2: verschiedene echte Clients teilen sich KEINEN Bucket")
    void distinctClientsAreNotLimitedTogether() {
        String body = "username=nobody&password=wrong";
        for (int i = 0; i < LIMIT; i++) {
            assertNotEquals(429, post("/login", forwardedFor("198.51.100.14", false), body)
                    .getStatusCode().value());
        }
        assertEquals(429, post("/login", forwardedFor("198.51.100.14", false), body)
                .getStatusCode().value());
        // Another user behind the same proxy must not notice anything of it.
        assertNotEquals(429, post("/login", forwardedFor("198.51.100.15", false), body)
                .getStatusCode().value());
    }

    @Test
    @DisplayName("Regression: die oeffentlichen /nosec/-Endpunkte und die Login-Seite bleiben offen")
    void publicEndpointsStillWork() {
        assertEquals(200, get("/nosec/version", forwardedFor("198.51.100.20", false))
                .getStatusCode().value());
        assertEquals(200, get("/actuator/health", forwardedFor("198.51.100.20", false))
                .getStatusCode().value());
        assertEquals(200, get("/login.html", forwardedFor("198.51.100.20", false))
                .getStatusCode().value());
    }

    @Test
    @DisplayName("Regression: /api/** wird weiterhin limitiert (keine Doppelzaehlung)")
    void apiIsStillRateLimited() {
        String xff = forwardedFor("198.51.100.21", false);
        for (int i = 0; i < LIMIT; i++) {
            assertNotEquals(429, get("/api/preferences/ping", xff).getStatusCode().value());
        }
        assertEquals(429, get("/api/preferences/ping", xff).getStatusCode().value());
    }
}
