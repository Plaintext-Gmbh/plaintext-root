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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.HttpURLConnection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Card 652 — the ERROR dispatch must not overwrite the original error status.
 *
 * <p><b>The finding:</b> every error produced via {@code response.sendError(...)} triggers a
 * second, internal pass on {@code /error} in the container. The
 * {@code springSecurityFilterChain} is mapped to {@code REQUEST+ASYNC+ERROR} and ran there
 * again — anonymously, because the bearer/token filters, being a {@code FilterRegistrationBean}, only
 * run on {@code REQUEST}. {@code /error} under {@code anyRequest().authenticated()} then meant:
 * 302 to the login page, and the real status (403/404/500) was gone. For a missing permission a
 * bearer client got HTML instead of JSON, and a script with {@code curl -L} read
 * HTTP 200 out of it — a permission denial as a success.</p>
 *
 * <p><b>Why through the real chain and not with MockMvc:</b> MockMvc performs no
 * ERROR dispatch. A mock test structurally cannot see this bug and could not have
 * prevented it either — the same lesson as with the {@link RateLimitChainTest} (card 303).
 * Hence real HTTP requests against a real Tomcat.</p>
 *
 * <p>The last two tests are the counter-checks: the browser path must still redirect to the
 * login unchanged, and the new rule must not open {@code /error} to callers from the
 * outside (there {@code DispatcherType.REQUEST} applies, not {@code ERROR}).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ErrorDispatchChainTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        EmbeddedPg.registrieren(registry, "errordispatchchaintest");
    }

    @LocalServerPort
    private int port;

    /**
     * Redirects must NOT be followed — swallowing the 302 is precisely the bug.
     * Otherwise {@code SimpleClientHttpRequestFactory} follows them by itself on GET and the test would
     * see the 200 of the login page instead of the 302.
     */
    private RestTemplate client() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod)
                    throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        RestTemplate template = new RestTemplate(factory);
        template.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false;
            }
        });
        return template;
    }

    private ResponseEntity<String> get(String path) {
        return client().exchange("http://localhost:" + port + path, HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), String.class);
    }

    private ResponseEntity<String> post(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return client().exchange("http://localhost:" + port + path, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    @Test
    @DisplayName("403 bleibt 403: eine Autorisierungsverweigerung wird nicht zur Anmeldeseite")
    void accessDeniedStaysForbidden() {
        // POST to a permitAll path WITHOUT a CSRF token: the CsrfFilter rejects it with an
        // AccessDeniedException -> AccessDeniedHandlerImpl -> sendError(403) -> ERROR dispatch.
        // The same path that a missing permission of a bearer client takes, only reproducible
        // without token infrastructure.
        ResponseEntity<String> response = post("/register", "username=nobody@example.com");

        assertEquals(403, response.getStatusCode().value(),
                "Eine Abweisung muss als 403 beim Aufrufer ankommen, nicht als Umleitung");
        assertNotEquals("/login.html", response.getHeaders().getFirst("Location"),
                "Der Client darf nicht auf die Anmeldeseite geschickt werden");
    }

    @Test
    @DisplayName("404 bleibt 404: ein unbekannter Pfad unter permitAll wird nicht zur Anmeldeseite")
    void notFoundStaysNotFound() {
        // /nosec/** is reachable anonymously (DEFAULT_PERMIT_ALL). A 404 here cannot possibly have
        // been rejected in the first pass — whoever sees a 302 here is seeing the
        // ERROR dispatch.
        ResponseEntity<String> response = get("/nosec/diesen-pfad-gibt-es-nicht");

        assertEquals(404, response.getStatusCode().value(),
                "Ein 404 auf einem permitAll-Pfad muss ein 404 bleiben");
    }

    @Test
    @DisplayName("Gegenprobe: der Browser-Pfad leitet weiterhin auf /login.html um")
    void protectedPageStillRedirectsToLogin() {
        ResponseEntity<String> response = get("/index.html");

        assertEquals(302, response.getStatusCode().value(),
                "Geschuetzte Seiten muessen fuer anonyme Aufrufer weiterhin umleiten");
        String location = response.getHeaders().getFirst("Location");
        assertTrue(location != null && location.endsWith("/login.html"),
                "Ziel der Umleitung muss die Anmeldeseite bleiben, war: " + location);
    }

    @Test
    @DisplayName("Gegenprobe: /error von aussen bleibt gesperrt — die Regel gilt nur intern")
    void errorPageIsNotOpenedForExternalCallers() {
        // A call from the outside carries DispatcherType.REQUEST; the new rule only matches ERROR.
        ResponseEntity<String> response = get("/error");

        assertNotEquals(200, response.getStatusCode().value(),
                "Die Fehlerseite darf durch den Fix nicht anonym aufrufbar werden");
    }
}
