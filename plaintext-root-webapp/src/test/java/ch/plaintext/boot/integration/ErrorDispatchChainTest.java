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
 * Karte 652 — der ERROR-Dispatch darf den urspruenglichen Fehlerstatus nicht ueberschreiben.
 *
 * <p><b>Der Befund:</b> Jeder ueber {@code response.sendError(...)} erzeugte Fehler loest im
 * Container einen zweiten, internen Durchlauf auf {@code /error} aus. Die
 * {@code springSecurityFilterChain} ist auf {@code REQUEST+ASYNC+ERROR} gemappt und lief dort
 * erneut — anonym, weil die Bearer-/Token-Filter als {@code FilterRegistrationBean} nur auf
 * {@code REQUEST} laufen. {@code /error} unter {@code anyRequest().authenticated()} hiess dann:
 * 302 auf die Anmeldeseite, und der echte Status (403/404/500) war weg. Ein Bearer-Client bekam
 * fuer eine fehlende Berechtigung HTML statt JSON, und ein Skript mit {@code curl -L} las daraus
 * HTTP 200 — eine Rechteverweigerung als Erfolg.</p>
 *
 * <p><b>Warum ueber die echte Kette und nicht mit MockMvc:</b> MockMvc fuehrt keinen
 * ERROR-Dispatch aus. Ein Mock-Test kann diesen Bug strukturell nicht sehen und haette ihn auch
 * nicht verhindern koennen — dieselbe Lehre wie beim {@link RateLimitChainTest} (Karte 303).
 * Deshalb echte HTTP-Requests gegen einen echten Tomcat.</p>
 *
 * <p>Die beiden letzten Tests sind die Gegenproben: der Browser-Pfad muss unveraendert auf die
 * Anmeldung umleiten, und die neue Regel darf {@code /error} nicht fuer Aufrufer von aussen
 * oeffnen (dort steht {@code DispatcherType.REQUEST}, nicht {@code ERROR}).</p>
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
     * Redirects duerfen NICHT gefolgt werden — genau das Verschlucken der 302 ist der Bug.
     * {@code SimpleClientHttpRequestFactory} folgt bei GET sonst von selbst und der Test saehe
     * die 200 der Anmeldeseite statt der 302.
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
        // POST auf einen permitAll-Pfad OHNE CSRF-Token: der CsrfFilter weist mit einer
        // AccessDeniedException ab -> AccessDeniedHandlerImpl -> sendError(403) -> ERROR-Dispatch.
        // Derselbe Weg, den eine fehlende Berechtigung eines Bearer-Clients nimmt, nur ohne
        // Token-Infrastruktur reproduzierbar.
        ResponseEntity<String> response = post("/register", "username=nobody@example.com");

        assertEquals(403, response.getStatusCode().value(),
                "Eine Abweisung muss als 403 beim Aufrufer ankommen, nicht als Umleitung");
        assertNotEquals("/login.html", response.getHeaders().getFirst("Location"),
                "Der Client darf nicht auf die Anmeldeseite geschickt werden");
    }

    @Test
    @DisplayName("404 bleibt 404: ein unbekannter Pfad unter permitAll wird nicht zur Anmeldeseite")
    void notFoundStaysNotFound() {
        // /nosec/** ist anonym erreichbar (DEFAULT_PERMIT_ALL). Ein 404 hier kann im ersten
        // Durchlauf gar nicht abgewiesen worden sein — wer hier eine 302 sieht, sieht den
        // ERROR-Dispatch.
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
        // Ein Aufruf von aussen traegt DispatcherType.REQUEST; die neue Regel matcht nur ERROR.
        ResponseEntity<String> response = get("/error");

        assertNotEquals(200, response.getStatusCode().value(),
                "Die Fehlerseite darf durch den Fix nicht anonym aufrufbar werden");
    }
}
