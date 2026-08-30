/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.integration;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Card 652 — a permission denial at a bearer-authenticated endpoint has to arrive at the caller as a
 * 403, not as a redirect to the login page.
 *
 * <p><b>Why this test exists:</b> on schuetu INT a valid token with scope
 * {@code READ} against a {@code @PreAuthorize("hasAuthority('SCOPE_WRITE')")} endpoint returns
 * {@code HTTP 302 -> /login.html}. A device gets HTML instead of JSON, and a script with
 * {@code curl -L} reads HTTP 200 out of it — a missing permission as a success. Two explanations
 * for it were plausible and both incomplete; that is why the constellation is
 * rebuilt here instead of guessing further at the test environment.</p>
 *
 * <p><b>What is rebuilt</b> — exactly the constellation of the {@code McpBearerTokenFilter}:
 * a {@link FilterRegistrationBean} with {@code order=1} (that is, INSIDE the
 * {@code springSecurityFilterChain}, which sits at {@code -100}) that sets a fresh
 * {@link SecurityContext}, restores the previous one in the {@code finally} and answers an
 * {@link AccessDeniedException} in the {@code catch} itself as JSON. The endpoint lies
 * under {@code permitAll}, because the filter does the authentication — just like
 * {@code /api/turnier/**} in schuetu.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "karte652"})
@TestPropertySource(properties = {
        // The endpoint comes through the chain; authentication happens in the filter (like /api/turnier/**).
        "plaintext.security.permit-all-patterns[0]=/api/karte652/**",
        // Without this line the test measures the CSRF filter instead of the authorization.
        "plaintext.security.csrf-ignore-patterns[0]=/api/karte652/**"
})
class BearerAccessDeniedChainTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        EmbeddedPg.registrieren(registry, "bearaccessdeniedchaintest");
    }

    @LocalServerPort
    private int port;

    /** A header instead of a real JWT — what is checked is the filter chain, not the token validation. */
    private static final String TEST_HEADER = "X-Karte652-Scope";

    @TestConfiguration
    static class TestFilterConfig {

        @Bean
        FilterRegistrationBean<Filter> karte652BearerFilter() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(new NachbauFilter());
            registration.addUrlPatterns("/api/karte652/*");
            registration.setOrder(1);   // like McpBearerTokenFilterProperties.order
            return registration;
        }
    }

    /** Rebuild of {@code McpBearerTokenFilter#doFilter} — the same order, the same branches. */
    static class NachbauFilter implements Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest req = (HttpServletRequest) request;
            HttpServletResponse res = (HttpServletResponse) response;

            String scope = req.getHeader(TEST_HEADER);
            if (scope == null) {
                res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                res.setContentType("application/json");
                res.getWriter().write("{\"error\":\"Unauthorized\"}");
                return;
            }

            var authorities = "WRITE".equals(scope)
                    ? List.of(new SimpleGrantedAuthority("SCOPE_READ"), new SimpleGrantedAuthority("SCOPE_WRITE"))
                    : List.of(new SimpleGrantedAuthority("SCOPE_READ"));
            var auth = new UsernamePasswordAuthenticationToken("karte652@plaintext.ch", null, authorities);

            SecurityContext previous = SecurityContextHolder.getContext();
            SecurityContext bearerContext = SecurityContextHolder.createEmptyContext();
            bearerContext.setAuthentication(auth);
            SecurityContextHolder.setContext(bearerContext);
            try {
                chain.doFilter(request, response);
            } catch (ServletException | RuntimeException ex) {
                // Deliberately via the helper method of the PRODUCTION FILTER: the exception from
                // @PreAuthorize arrives here wrapped in a ServletException (measured, card
                // 652). If the test searched the chain itself, it would be checking its own
                // reproduction instead of the shipped code.
                AccessDeniedException e = ch.plaintext.apitoken.McpBearerTokenFilter.findeAccessDenied(ex);
                if (e == null) {
                    throw ex;
                }
                if (res.isCommitted()) {
                    throw e;
                }
                res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                res.setContentType("application/json");
                res.getWriter().write("{\"error\":\"Forbidden\"}");
            } finally {
                SecurityContextHolder.setContext(previous);
            }
        }
    }

    /**
     * A profile of its own, because the component scan of {@code ch.RootBootApplication} also
     * covers test classes: without {@code @Profile} this endpoint would hang in EVERY
     * application context of the test suite.
     */
    @org.springframework.context.annotation.Profile("karte652")
    @RestController
    @RequestMapping("/api/karte652")
    static class RestEndpunkte {

        @PreAuthorize("hasAuthority('SCOPE_WRITE')")
        @PostMapping("/schreiben")
        String schreiben() {
            return "{\"ok\":true}";
        }

        @PreAuthorize("hasAuthority('SCOPE_READ')")
        @GetMapping("/lesen")
        String lesen() {
            return "[]";
        }
    }

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

    private ResponseEntity<String> ruf(HttpMethod methode, String pfad, String scope) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (scope != null) {
            headers.set(TEST_HEADER, scope);
        }
        return client().exchange("http://localhost:" + port + pfad, methode,
                new HttpEntity<>("{}", headers), String.class);
    }

    @Test
    @DisplayName("Der Befund: READ-Token am Schreib-Gate muss 403 bekommen, keine Umleitung")
    void readTokenAmSchreibGateBekommt403() {
        ResponseEntity<String> response = ruf(HttpMethod.POST, "/api/karte652/schreiben", "READ");

        assertEquals(403, response.getStatusCode().value(),
                "Fehlende Berechtigung muss als 403 ankommen, war: " + response.getStatusCode()
                        + " Location=" + response.getHeaders().getFirst("Location"));
        String contentType = String.valueOf(response.getHeaders().getContentType());
        assertTrue(contentType.contains("json"),
                "Ein Bearer-Client erwartet JSON, bekam Content-Type: " + contentType);
    }

    @Test
    @DisplayName("Gegenprobe: dasselbe Token am Lese-Gate geht weiterhin durch")
    void readTokenAmLeseGateBekommt200() {
        ResponseEntity<String> response = ruf(HttpMethod.GET, "/api/karte652/lesen", "READ");

        assertEquals(200, response.getStatusCode().value(),
                "Das Token ist gueltig — ohne diese Zeile waere jedes 403 mehrdeutig");
    }

    @Test
    @DisplayName("Gegenprobe: mit dem noetigen Scope laesst das Gate durch")
    void writeTokenAmSchreibGateBekommt200() {
        ResponseEntity<String> response = ruf(HttpMethod.POST, "/api/karte652/schreiben", "WRITE");

        assertEquals(200, response.getStatusCode().value(),
                "Mit SCOPE_WRITE muss der Endpunkt antworten — sonst weist das Gate alles ab");
    }

    @Test
    @DisplayName("Gegenprobe: ohne Token antwortet der Filter selbst mit 401 JSON")
    void ohneTokenBekommt401() {
        ResponseEntity<String> response = ruf(HttpMethod.POST, "/api/karte652/schreiben", null);

        assertEquals(401, response.getStatusCode().value(),
                "Der Filter muss fehlende Authentisierung selbst beantworten");
    }
}
