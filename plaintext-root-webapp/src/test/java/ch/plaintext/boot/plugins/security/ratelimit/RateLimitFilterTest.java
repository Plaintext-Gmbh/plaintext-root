/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitFilter")
class RateLimitFilterTest {

    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain filterChain;

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter(5, 60, 3, 60, 4, 60, 2, 60, 3, 60);
    }

    @Test
    void shouldAllowNormalRequests() throws Exception {
        when(request.getRequestURI()).thenReturn("/index.xhtml");
        filter.doFilter(request, response, filterChain);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void shouldRateLimitApiEndpoints() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/preferences/save");
        when(request.getRemoteAddr()).thenReturn("192.168.1.100");
        StringWriter sw = new StringWriter();
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(sw));

        // First 5 should pass
        for (int i = 0; i < 5; i++) {
            filter.doFilter(request, response, filterChain);
        }
        verify(filterChain, times(5)).doFilter(request, response);

        // 6th should be blocked
        filter.doFilter(request, response, filterChain);
        verify(response).setStatus(429);
        verify(filterChain, times(5)).doFilter(request, response); // still 5
    }

    @Test
    void shouldRateLimitLoginAttempts() throws Exception {
        when(request.getRequestURI()).thenReturn("/login");
        when(request.getMethod()).thenReturn("POST");
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        StringWriter sw = new StringWriter();
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(sw));

        // First 3 should pass
        for (int i = 0; i < 3; i++) {
            filter.doFilter(request, response, filterChain);
        }
        verify(filterChain, times(3)).doFilter(request, response);

        // 4th should be blocked
        // Karte 303: echter 429 statt 302-Redirect (sendRedirect hatte den 429 ueberschrieben)
        filter.doFilter(request, response, filterChain);
        verify(response).setStatus(429);
        verify(response, never()).sendRedirect(anyString());
        assertTrue(sw.toString().contains("Zu viele Anmeldeversuche"));
    }

    @Test
    @DisplayName("Karte 560: /token-login wird NICHT mehr gedrosselt — der Pfad ist unbesetzt und "
            + "faellt unter authenticated(); ein Limit darauf haette nichts zu bremsen")
    void shouldNotRateLimitRemovedTokenLogin() throws Exception {
        when(request.getRequestURI()).thenReturn("/token-login");
        // Bewusst KEIN getRemoteAddr()-Stub: der Filter ermittelt fuer diesen Pfad gar keine
        // Client-IP mehr. Mockitos strikte Stub-Pruefung ist hier der eigentliche Beweis --
        // ein ueberfluessiger Stub liesse den Test scheitern, sobald der Zweig zurueckkaeme.

        for (int i = 0; i < 5; i++) {
            filter.doFilter(request, response, filterChain);
        }

        verify(filterChain, times(5)).doFilter(request, response);
        verify(response, never()).setStatus(429);
    }

    @Test
    @DisplayName("Karte 303: XFF wird von rechts ausgewertet, das vom Client gesetzte erste "
            + "Element ist NICHT der Schluessel")
    void shouldUseRightmostUntrustedXForwardedForElement() {
        when(request.getRemoteAddr()).thenReturn("192.168.208.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4, 203.0.113.9, 192.168.1.224");
        assertEquals("203.0.113.9", filter.getClientIp(request));
    }

    @Test
    @DisplayName("Karte 303: kommt der Request nicht von einem vertrauenswuerdigen Proxy, "
            + "wird XFF komplett ignoriert")
    void shouldIgnoreXForwardedForFromUntrustedPeer() {
        when(request.getRemoteAddr()).thenReturn("203.0.113.5");
        assertEquals("203.0.113.5", filter.getClientIp(request));
        verify(request, never()).getHeader("X-Forwarded-For");
    }

    @Test
    @DisplayName("Karte 303: X-Real-IP wird nicht mehr ausgewertet (nicht verifizierbar)")
    void shouldIgnoreXRealIpHeader() {
        when(request.getRemoteAddr()).thenReturn("10.1.2.3");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        lenient().when(request.getHeader("X-Real-IP")).thenReturn("9.8.7.6");
        assertEquals("10.1.2.3", filter.getClientIp(request));
    }

    @Test
    void shouldFallBackToRemoteAddr() {
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        assertEquals("127.0.0.1", filter.getClientIp(request));
    }

    @Test
    @DisplayName("Karte 303: gefaelschte XFF-Werte erzeugen keine neuen Buckets")
    void spoofedXForwardedForDoesNotEscapeTheLimit() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/data");
        when(request.getRemoteAddr()).thenReturn("192.168.208.1");
        when(request.getHeader("X-Forwarded-For")).thenAnswer(inv -> spoof() + ", 203.0.113.77, 192.168.1.224");
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        for (int i = 0; i < 5; i++) {
            filter.doFilter(request, response, filterChain);
        }
        verify(filterChain, times(5)).doFilter(request, response);

        filter.doFilter(request, response, filterChain);
        verify(response).setStatus(429);
        verify(filterChain, times(5)).doFilter(request, response);
    }

    private int spoofCounter;

    private String spoof() {
        return "1.2.3." + (spoofCounter++ % 250);
    }

    @Test
    void shouldSetRateLimitHeader() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/data");
        when(request.getRemoteAddr()).thenReturn("10.0.0.5");

        filter.doFilter(request, response, filterChain);
        verify(response).setHeader(eq("X-RateLimit-Remaining"), anyString());
    }

    @Test
    void shouldNotRateLimitGetLogin() throws Exception {
        when(request.getRequestURI()).thenReturn("/login");
        when(request.getMethod()).thenReturn("GET");

        // GET /login should not be rate limited (only POST)
        for (int i = 0; i < 10; i++) {
            filter.doFilter(request, response, filterChain);
        }
        verify(filterChain, times(10)).doFilter(request, response);
    }

    @Test
    void shouldRateLimitClaudeAutomationEndpoints() throws Exception {
        when(request.getRequestURI()).thenReturn("/nosec/api/claude/has-work");
        when(request.getRemoteAddr()).thenReturn("10.0.0.9");
        StringWriter sw = new StringWriter();
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(sw));

        // First 4 should pass (claude limiter configured with 4 req/window)
        for (int i = 0; i < 4; i++) {
            filter.doFilter(request, response, filterChain);
        }
        verify(filterChain, times(4)).doFilter(request, response);

        // 5th should be blocked with 429 + Retry-After
        filter.doFilter(request, response, filterChain);
        verify(response).setStatus(429);
        verify(response).setHeader("Retry-After", "60");
        verify(filterChain, times(4)).doFilter(request, response); // still 4
    }

    @Test
    void claudeLimiterIsPerIp() throws Exception {
        when(request.getRequestURI()).thenReturn("/nosec/api/claude/next-task");
        when(request.getRemoteAddr()).thenReturn("10.0.1.1");
        StringWriter sw = new StringWriter();
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(sw));

        for (int i = 0; i < 5; i++) {
            filter.doFilter(request, response, filterChain);
        }

        // Different IP is not affected by the blocked one
        when(request.getRemoteAddr()).thenReturn("10.0.1.2");
        filter.doFilter(request, response, filterChain);
        verify(filterChain, times(5)).doFilter(request, response); // 4 from first IP + 1 from second
    }

    @Test
    void shouldRateLimitSchiriMobileEndpoints() throws Exception {
        when(request.getRequestURI()).thenReturn("/nosec/schiri-mobile/abc123");
        when(request.getRemoteAddr()).thenReturn("10.0.2.1");
        StringWriter sw = new StringWriter();
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(sw));

        // First 2 should pass (nosec-token limiter configured with 2 req/window)
        for (int i = 0; i < 2; i++) {
            filter.doFilter(request, response, filterChain);
        }
        verify(filterChain, times(2)).doFilter(request, response);

        // 3rd should be blocked with 429 + Retry-After
        filter.doFilter(request, response, filterChain);
        verify(response).setStatus(429);
        verify(response).setHeader("Retry-After", "60");
        verify(filterChain, times(2)).doFilter(request, response); // still 2
    }

    @Test
    void shouldHandleCleanup() {
        filter.cleanupExpiredBuckets(); // Should not throw
    }

    @Test
    @DisplayName("Karte 303: erfolgreiche Logins verbrauchen kein Kontingent - das Limit bremst "
            + "Rateversuche, nicht echte Anmeldungen")
    void successfulLoginsAreRefunded() throws Exception {
        when(request.getRequestURI()).thenReturn("/login");
        when(request.getMethod()).thenReturn("POST");
        when(request.getRemoteAddr()).thenReturn("10.0.0.77");
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        jakarta.servlet.http.HttpSession session = mock(jakarta.servlet.http.HttpSession.class);
        org.springframework.security.core.Authentication auth =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "user", "pw", java.util.List.of());
        org.springframework.security.core.context.SecurityContext context =
                new org.springframework.security.core.context.SecurityContextImpl(auth);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(org.springframework.security.web.context
                .HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).thenReturn(context);

        // Deutlich mehr als das Limit von 3 - alle erfolgreich, also nie limitiert.
        for (int i = 0; i < 10; i++) {
            filter.doFilter(request, response, filterChain);
        }
        verify(filterChain, times(10)).doFilter(request, response);
        verify(response, never()).setStatus(429);
    }

    @Test
    @DisplayName("Karte 303: fehlgeschlagene Logins zaehlen weiterhin (fail-closed)")
    void failedLoginsStillCount() throws Exception {
        when(request.getRequestURI()).thenReturn("/login");
        when(request.getMethod()).thenReturn("POST");
        when(request.getRemoteAddr()).thenReturn("10.0.0.78");
        when(request.getSession(false)).thenReturn(null);
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        for (int i = 0; i < 3; i++) {
            filter.doFilter(request, response, filterChain);
        }
        filter.doFilter(request, response, filterChain);
        verify(response).setStatus(429);
        verify(filterChain, times(3)).doFilter(request, response);
    }

    /**
     * SECURITY (Karte 314, Punkt 16): {@code /nosec/wiki} hatte trotz gegenteiliger Zusage im
     * Controller-Javadoc kein Rate-Limit — der Filter kannte den Pfad schlicht nicht. Der
     * generische {@code /nosec/}-Auffangzweig deckt ihn jetzt ab.
     */
    @Test
    void shouldRateLimitGenericNosecPaths() throws Exception {
        when(request.getRequestURI()).thenReturn("/nosec/wiki/seite");
        when(request.getRemoteAddr()).thenReturn("203.0.113.10");
        StringWriter sw = new StringWriter();
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(sw));

        for (int i = 0; i < 3; i++) {
            filter.doFilter(request, response, filterChain);
        }
        verify(filterChain, times(3)).doFilter(request, response);

        filter.doFilter(request, response, filterChain);
        verify(filterChain, times(3)).doFilter(request, response);
        verify(response).setStatus(429);
    }

    /** Analog fuer /nosec/challenge — derselbe generische Zweig. */
    @Test
    void shouldRateLimitNosecChallenge() throws Exception {
        when(request.getRequestURI()).thenReturn("/nosec/challenge/eintrag");
        when(request.getRemoteAddr()).thenReturn("203.0.113.11");
        StringWriter sw = new StringWriter();
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(sw));

        for (int i = 0; i < 4; i++) {
            filter.doFilter(request, response, filterChain);
        }
        verify(filterChain, times(3)).doFilter(request, response);
        verify(response).setStatus(429);
    }

    /**
     * SECURITY (Karte 314, Punkt 16): der generische Zweig darf {@code /nosec/api/claude} nicht
     * zusaetzlich zaehlen, sonst verbraucht ein Request zwei Kontingente.
     */
    @Test
    void claudeEndpointShouldNotBeCountedTwice() throws Exception {
        when(request.getRequestURI()).thenReturn("/nosec/api/claude/status");
        when(request.getRemoteAddr()).thenReturn("203.0.113.12");
        StringWriter sw = new StringWriter();
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(sw));

        // claudeLimiter erlaubt 4, der generische Limiter nur 3 — kaeme beides zum Zug,
        // waere schon der 4. Request blockiert.
        for (int i = 0; i < 4; i++) {
            filter.doFilter(request, response, filterChain);
        }
        verify(filterChain, times(4)).doFilter(request, response);
    }

    /**
     * SECURITY (Karte 314, Punkt 10): {@code POST /password-reset} ist permitAll und verschickt
     * Mails an eine vom Aufrufer gewaehlte Adresse — ohne Limit ein Spam-Relais und ein
     * Werkzeug zur Konto-Enumeration.
     */
    @Test
    void shouldRateLimitPasswordReset() throws Exception {
        when(request.getRequestURI()).thenReturn("/password-reset");
        when(request.getMethod()).thenReturn("POST");
        when(request.getRemoteAddr()).thenReturn("203.0.113.20");
        StringWriter sw = new StringWriter();
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(sw));

        for (int i = 0; i < 3; i++) {
            filter.doFilter(request, response, filterChain);
        }
        verify(filterChain, times(3)).doFilter(request, response);

        filter.doFilter(request, response, filterChain);
        verify(filterChain, times(3)).doFilter(request, response);
        verify(response).setStatus(429);
    }

    /** SECURITY (Karte 314, Punkt 10): dasselbe fuer die Selbstregistrierung. */
    @Test
    void shouldRateLimitRegister() throws Exception {
        when(request.getRequestURI()).thenReturn("/register");
        when(request.getMethod()).thenReturn("POST");
        when(request.getRemoteAddr()).thenReturn("203.0.113.21");
        StringWriter sw = new StringWriter();
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(sw));

        for (int i = 0; i < 4; i++) {
            filter.doFilter(request, response, filterChain);
        }
        verify(filterChain, times(3)).doFilter(request, response);
        verify(response).setStatus(429);
    }

    /** GET /password-reset (Formularanzeige) darf NICHT limitiert werden. */
    @Test
    void shouldNotRateLimitPasswordResetForm() throws Exception {
        when(request.getRequestURI()).thenReturn("/password-reset");
        when(request.getMethod()).thenReturn("GET");
        lenient().when(request.getRemoteAddr()).thenReturn("203.0.113.22");

        for (int i = 0; i < 10; i++) {
            filter.doFilter(request, response, filterChain);
        }
        verify(filterChain, times(10)).doFilter(request, response);
    }

    // ---------------------------------------------------------------- CalDAV/CardDAV (Karte 657)

    /**
     * Karte 657: CalDAV lag im generischen /nosec-Eimer und hat damit 73 Mal echte Clients
     * abgewiesen — darunter vier Aufrufe der oeffentlichen Terminseite durch einen Browser, der
     * sich das Kontingent mit einem gleichzeitigen Apple-Sync teilte. Gemessen am nginx-Log von
     * plaintext-app: Spitzen von 85 CalDAV-Anfragen pro Minute gegen eine Grenze von 60.
     */
    private RateLimitFilter mitDavGrenze(int davMax, int nosecPublicMax) {
        // api, login, claude, nosec-token, nosec-public, dav
        return new RateLimitFilter(5, 60, 3, 60, 4, 60, 2, 60, nosecPublicMax, 60, davMax, 60);
    }

    @Test
    @DisplayName("CalDAV verbraucht den DAV-Eimer und nicht das generische /nosec-Limit")
    void calDavNutztEigenenEimer() throws Exception {
        // Generisches Limit absichtlich winzig (1): Laege CalDAV noch darin, waere ab dem
        // zweiten Aufruf Schluss. Der DAV-Eimer erlaubt 6.
        RateLimitFilter f = mitDavGrenze(6, 1);
        when(request.getRequestURI()).thenReturn("/nosec/caldav/mad/4/");
        when(request.getRemoteAddr()).thenReturn("203.0.113.40");

        for (int i = 0; i < 6; i++) {
            f.doFilter(request, response, filterChain);
        }

        verify(filterChain, times(6)).doFilter(request, response);
        verify(response, never()).setStatus(429);
    }

    @Test
    @DisplayName("CardDAV ebenso — der Zweig deckt beide Protokolle ab")
    void cardDavNutztDenselbenEimer() throws Exception {
        RateLimitFilter f = mitDavGrenze(6, 1);
        when(request.getRequestURI()).thenReturn("/nosec/carddav/mad/addressbooks/default/");
        when(request.getRemoteAddr()).thenReturn("203.0.113.41");

        for (int i = 0; i < 6; i++) {
            f.doFilter(request, response, filterChain);
        }

        verify(filterChain, times(6)).doFilter(request, response);
    }

    @Test
    @DisplayName("Der DAV-Eimer ist eine Bremse, keine Freikarte")
    void davEimerGreiftAnSeinerGrenze() throws Exception {
        RateLimitFilter f = mitDavGrenze(3, 60);
        when(request.getRequestURI()).thenReturn("/nosec/caldav/mad/");
        when(request.getRemoteAddr()).thenReturn("203.0.113.42");
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        for (int i = 0; i < 4; i++) {
            f.doFilter(request, response, filterChain);
        }

        verify(filterChain, times(3)).doFilter(request, response);
        verify(response).setStatus(429);
    }

    /**
     * Der Kern des Befunds: Ein CalDAV-Sync darf die oeffentliche Terminseite nicht mehr
     * aussperren. Frueher teilten sich beide den nosec-public-Eimer.
     */
    @Test
    @DisplayName("Ein ausgeschoepfter DAV-Eimer sperrt die oeffentliche Terminseite NICHT aus")
    void davVerbrauchSperrtDieTerminseiteNicht() throws Exception {
        RateLimitFilter f = mitDavGrenze(2, 5);
        when(request.getRemoteAddr()).thenReturn("203.0.113.43");
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        // DAV-Eimer leerlaufen lassen (2 erlaubt, der dritte wird abgewiesen)
        when(request.getRequestURI()).thenReturn("/nosec/caldav/mad/");
        for (int i = 0; i < 3; i++) {
            f.doFilter(request, response, filterChain);
        }
        verify(filterChain, times(2)).doFilter(request, response);

        // Derselbe Anschluss ruft jetzt die oeffentliche Terminseite auf — muss durchkommen.
        reset(filterChain);
        when(request.getRequestURI()).thenReturn("/nosec/khost/termin/9902861ff6774a9b8ae7ad442d44df8a");
        f.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    /** Die uebrigen /nosec-Pfade bleiben am generischen Limit — Karte 314/16 gilt unveraendert. */
    @Test
    @DisplayName("Nicht-DAV-Pfade unter /nosec bleiben am generischen Limit")
    void generischesNosecLimitUnveraendert() throws Exception {
        RateLimitFilter f = mitDavGrenze(240, 2);
        when(request.getRequestURI()).thenReturn("/nosec/wiki/p/seite.xhtml");
        when(request.getRemoteAddr()).thenReturn("203.0.113.44");
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        for (int i = 0; i < 3; i++) {
            f.doFilter(request, response, filterChain);
        }

        verify(filterChain, times(2)).doFilter(request, response);
        verify(response).setStatus(429);
    }
}
