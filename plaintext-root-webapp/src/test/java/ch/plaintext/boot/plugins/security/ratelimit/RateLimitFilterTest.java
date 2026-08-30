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
        // Card 303: a real 429 instead of a 302 redirect (sendRedirect had overwritten the 429)
        filter.doFilter(request, response, filterChain);
        verify(response).setStatus(429);
        verify(response, never()).sendRedirect(anyString());
        assertTrue(sw.toString().contains("Zu viele Anmeldeversuche"));
    }

    @Test
    @DisplayName("Zustandsbericht 29.08.2026 (H3): der zweite Faktor wird wie /login gebremst")
    void shouldRateLimitTotpAttempts() throws Exception {
        when(request.getRequestURI()).thenReturn("/login/totp");
        when(request.getMethod()).thenReturn("POST");
        when(request.getRemoteAddr()).thenReturn("10.0.0.7");
        StringWriter sw = new StringWriter();
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(sw));

        for (int i = 0; i < 3; i++) {
            filter.doFilter(request, response, filterChain);
        }
        verify(filterChain, times(3)).doFilter(request, response);

        filter.doFilter(request, response, filterChain);
        verify(response).setStatus(429);
        verify(filterChain, times(3)).doFilter(request, response);
    }

    @Test
    @DisplayName("Karte 560: /token-login wird NICHT mehr gedrosselt — der Pfad ist unbesetzt und "
            + "faellt unter authenticated(); ein Limit darauf haette nichts zu bremsen")
    void shouldNotRateLimitRemovedTokenLogin() throws Exception {
        when(request.getRequestURI()).thenReturn("/token-login");
        // Deliberately NO getRemoteAddr() stub: for this path the filter no longer determines a
        // client IP at all. Mockito's strict stub checking is the actual proof here --
        // a superfluous stub would make the test fail as soon as the branch came back.

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

    /**
     * Card 968 (Sonar {@code java:S2699}): the test only called {@code cleanupExpiredBuckets()}
     * and checked nothing — it was green no matter what the method does.
     *
     * <p>The statement that matters is not a technical one but a security one:
     * <b>the cleanup must not lift running blocks.</b> If it emptied the buckets indiscriminately
     * instead of only the expired ones, an attacker would be handed their quota every five minutes
     * ({@code @Scheduled(fixedRate = 300000)}) — the brake would be practically without effect.
     *
     * <p>The expiry itself is covered one level deeper ({@code RateLimiterTest}, both
     * directions). Here it is about the interplay inside the filter.
     */
    @Test
    void cleanupHebtLaufendeSperrenNichtAuf() throws Exception {
        when(request.getRequestURI()).thenReturn("/login");
        when(request.getMethod()).thenReturn("POST");
        when(request.getRemoteAddr()).thenReturn("10.0.9.9");
        StringWriter sw = new StringWriter();
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(sw));

        // Use up the quota (login: 3 per window) and trigger the block.
        for (int i = 0; i < 4; i++) {
            filter.doFilter(request, response, filterChain);
        }
        verify(filterChain, times(3)).doFilter(request, response);
        verify(response).setStatus(429);

        filter.cleanupExpiredBuckets();

        // The bucket is fresh, hence not expired: the block has to stay in place.
        filter.doFilter(request, response, filterChain);
        verify(filterChain, times(3)).doFilter(request, response);
        verify(response, times(2)).setStatus(429);
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

        // Clearly more than the limit of 3 - all successful, hence never limited.
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
     * SECURITY (card 314, item 16): despite the promise to the contrary in the controller Javadoc,
     * {@code /nosec/wiki} had no rate limit — the filter simply did not know the path. The
     * generic {@code /nosec/} catch-all branch now covers it.
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

    /** Analogous for /nosec/challenge — the same generic branch. */
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
     * SECURITY (card 314, item 16): the generic branch must not additionally count
     * {@code /nosec/api/claude}, otherwise one request consumes two quotas.
     */
    @Test
    void claudeEndpointShouldNotBeCountedTwice() throws Exception {
        when(request.getRequestURI()).thenReturn("/nosec/api/claude/status");
        when(request.getRemoteAddr()).thenReturn("203.0.113.12");
        StringWriter sw = new StringWriter();
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(sw));

        // claudeLimiter permits 4, the generic limiter only 3 — if both applied,
        // the 4th request would already be blocked.
        for (int i = 0; i < 4; i++) {
            filter.doFilter(request, response, filterChain);
        }
        verify(filterChain, times(4)).doFilter(request, response);
    }

    /**
     * SECURITY (card 314, item 10): {@code POST /password-reset} is permitAll and sends
     * mails to an address chosen by the caller — without a limit a spam relay and a
     * tool for account enumeration.
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

    /** SECURITY (card 314, item 10): the same for the self-registration. */
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

    /** GET /password-reset (form display) must NOT be limited. */
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

    // ----------------------------------------------------------------- CalDAV/CardDAV (card 657)

    /**
     * Card 657: CalDAV lay in the generic /nosec bucket and thereby rejected real clients 73 times
     * — among them four calls of the public appointments page by a browser that
     * shared the quota with a concurrent Apple sync. Measured against the nginx log of
     * plaintext-app: peaks of 85 CalDAV requests per minute against a limit of 60.
     */
    private RateLimitFilter mitDavGrenze(int davMax, int nosecPublicMax) {
        // api, login, claude, nosec-token, nosec-public, dav
        return new RateLimitFilter(5, 60, 3, 60, 4, 60, 2, 60, nosecPublicMax, 60, davMax, 60);
    }

    @Test
    @DisplayName("CalDAV verbraucht den DAV-Eimer und nicht das generische /nosec-Limit")
    void calDavNutztEigenenEimer() throws Exception {
        // The generic limit is deliberately tiny (1): if CalDAV still lay within it, it would be over
        // from the second call on. The DAV bucket permits 6.
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
     * The core of the finding: a CalDAV sync must no longer lock out the public appointments
     * page. Previously both shared the nosec-public bucket.
     */
    @Test
    @DisplayName("Ein ausgeschoepfter DAV-Eimer sperrt die oeffentliche Terminseite NICHT aus")
    void davVerbrauchSperrtDieTerminseiteNicht() throws Exception {
        RateLimitFilter f = mitDavGrenze(2, 5);
        when(request.getRemoteAddr()).thenReturn("203.0.113.43");
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        // Let the DAV bucket run empty (2 permitted, the third is rejected)
        when(request.getRequestURI()).thenReturn("/nosec/caldav/mad/");
        for (int i = 0; i < 3; i++) {
            f.doFilter(request, response, filterChain);
        }
        verify(filterChain, times(2)).doFilter(request, response);

        // The same connection now calls the public appointments page — it has to get through.
        reset(filterChain);
        when(request.getRequestURI()).thenReturn("/nosec/khost/termin/9902861ff6774a9b8ae7ad442d44df8a");
        f.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    /** The remaining /nosec paths stay on the generic limit — card 314/16 applies unchanged. */
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
