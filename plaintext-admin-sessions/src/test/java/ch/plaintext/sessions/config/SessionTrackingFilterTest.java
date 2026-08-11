/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sessions.config;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.sessions.service.HttpSessionRegistry;
import ch.plaintext.sessions.service.SessionAuditServiceImpl;
import ch.plaintext.settings.ISetupConfigService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionTrackingFilterTest {

    @Mock
    private SessionAuditServiceImpl sessionAuditService;

    @Mock
    private PlaintextSecurity security;

    @Mock
    private HttpSessionRegistry sessionRegistry;

    @Mock
    private HttpServletRequest httpRequest;

    @Mock
    private HttpServletResponse httpResponse;

    @Mock
    private FilterChain filterChain;

    @Mock
    private HttpSession httpSession;

    @Mock
    private Authentication authentication;

    @Mock
    private SecurityContext securityContext;

    @Mock
    private ObjectProvider<ISetupConfigService> setupConfigProvider;

    @Mock
    private ISetupConfigService setupConfigService;

    private SessionTrackingFilter filter;

    @BeforeEach
    void setUp() {
        filter = new SessionTrackingFilter(sessionAuditService, security, sessionRegistry, setupConfigProvider);
        // Karte 627: Voreinstellung fuer die bestehenden Faelle ist „Schalter an" — sie pruefen das
        // Verhalten VOR dem Schalter und muessen es unveraendert weiter belegen. lenient(), weil die
        // meisten Faelle abbrechen, bevor der Schalter ueberhaupt gelesen wird.
        lenient().when(setupConfigProvider.getIfAvailable()).thenReturn(setupConfigService);
        lenient().when(setupConfigService.isSessionTrackingEnabled(any())).thenReturn(true);
    }

    @Test
    void doFilterContinuesChainForHttpRequest() throws Exception {
        SecurityContextHolder.setContext(securityContext);
        when(securityContext.getAuthentication()).thenReturn(null);

        filter.doFilter(httpRequest, httpResponse, filterChain);

        verify(filterChain).doFilter(httpRequest, httpResponse);
    }

    @Test
    void doFilterContinuesChainForNonHttpRequest() throws Exception {
        ServletRequest nonHttpRequest = mock(ServletRequest.class);

        filter.doFilter(nonHttpRequest, httpResponse, filterChain);

        verify(filterChain).doFilter(nonHttpRequest, httpResponse);
    }

    @Test
    void trackSessionAsyncTracksAuthenticatedUser() {
        SecurityContextHolder.setContext(securityContext);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn("admin");
        when(httpRequest.getSession(false)).thenReturn(httpSession);
        when(httpSession.getId()).thenReturn("sess-123");
        when(security.getId()).thenReturn(42L);
        when(httpRequest.getHeader("User-Agent")).thenReturn("TestAgent/1.0");

        filter.trackSessionAsync(httpRequest);

        verify(sessionRegistry).registerSession("sess-123", httpSession);
        verify(sessionAuditService).updateOrCreate(42L, "sess-123", authentication, "TestAgent/1.0");
    }

    @Test
    void trackSessionAsyncSkipsAnonymousUser() {
        SecurityContextHolder.setContext(securityContext);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn("anonymousUser");

        filter.trackSessionAsync(httpRequest);

        verifyNoInteractions(sessionRegistry);
        verifyNoInteractions(sessionAuditService);
    }

    @Test
    void trackSessionAsyncSkipsUnauthenticatedUser() {
        SecurityContextHolder.setContext(securityContext);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(false);

        filter.trackSessionAsync(httpRequest);

        verifyNoInteractions(sessionRegistry);
        verifyNoInteractions(sessionAuditService);
    }

    @Test
    void trackSessionAsyncSkipsNullAuthentication() {
        SecurityContextHolder.setContext(securityContext);
        when(securityContext.getAuthentication()).thenReturn(null);

        filter.trackSessionAsync(httpRequest);

        verifyNoInteractions(sessionRegistry);
        verifyNoInteractions(sessionAuditService);
    }

    @Test
    void trackSessionAsyncSkipsNullSession() {
        SecurityContextHolder.setContext(securityContext);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn("admin");
        when(httpRequest.getSession(false)).thenReturn(null);

        filter.trackSessionAsync(httpRequest);

        verifyNoInteractions(sessionRegistry);
        verifyNoInteractions(sessionAuditService);
    }

    @Test
    void trackSessionAsyncSkipsNullUserId() {
        SecurityContextHolder.setContext(securityContext);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn("admin");
        when(httpRequest.getSession(false)).thenReturn(httpSession);
        when(httpSession.getId()).thenReturn("sess-123");
        when(security.getId()).thenReturn(null);

        filter.trackSessionAsync(httpRequest);

        verifyNoInteractions(sessionRegistry);
        verifyNoInteractions(sessionAuditService);
    }

    @Test
    void trackSessionAsyncHandlesExceptionGracefully() {
        SecurityContextHolder.setContext(securityContext);
        when(securityContext.getAuthentication()).thenThrow(new RuntimeException("Unexpected error"));

        // Should not throw
        filter.trackSessionAsync(httpRequest);

        verifyNoInteractions(sessionRegistry);
        verifyNoInteractions(sessionAuditService);
    }

    @Test
    void doFilterTracksSessionAndContinuesChain() throws Exception {
        SecurityContextHolder.setContext(securityContext);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn("admin");
        when(httpRequest.getSession(false)).thenReturn(httpSession);
        when(httpSession.getId()).thenReturn("sess-456");
        when(security.getId()).thenReturn(99L);
        when(httpRequest.getHeader("User-Agent")).thenReturn("Mozilla/5.0");

        filter.doFilter(httpRequest, httpResponse, filterChain);

        verify(sessionRegistry).registerSession("sess-456", httpSession);
        verify(sessionAuditService).updateOrCreate(99L, "sess-456", authentication, "Mozilla/5.0");
        verify(filterChain).doFilter(httpRequest, httpResponse);
    }

    // ── Karte 627: der Schalter ────────────────────────────────────────────────────────────────

    /**
     * Der Zweck der Karte: Schalter aus → kein neuer Eintrag. Die Registrierung in der flüchtigen
     * {@link HttpSessionRegistry} läuft weiter, sonst verlöre ROOT das Zwangs-Abmelden.
     */
    @Test
    void schalterAusZeichnetNichtAufLaesstAberDasAbmeldenIntakt() {
        angemeldeteSitzung("sess-aus", 7L, "Firefox/1.0");
        when(security.getMandat()).thenReturn("default");
        when(setupConfigService.isSessionTrackingEnabled("default")).thenReturn(false);

        filter.trackSessionAsync(httpRequest);

        verify(sessionRegistry).registerSession("sess-aus", httpSession);
        verifyNoInteractions(sessionAuditService);
    }

    @Test
    void schalterAnZeichnetAuf() {
        angemeldeteSitzung("sess-an", 7L, "Firefox/1.0");
        when(security.getMandat()).thenReturn("default");
        when(setupConfigService.isSessionTrackingEnabled("default")).thenReturn(true);

        filter.trackSessionAsync(httpRequest);

        verify(sessionAuditService).updateOrCreate(7L, "sess-an", authentication, "Firefox/1.0");
    }

    /** Anwendung ohne Modul {@code plaintext-admin-settings}: aufzeichnen wie vor der Karte. */
    @Test
    void ohneSettingsModulWirdAufgezeichnet() {
        angemeldeteSitzung("sess-ohne", 8L, "curl/8.0");
        when(setupConfigProvider.getIfAvailable()).thenReturn(null);

        filter.trackSessionAsync(httpRequest);

        verify(sessionAuditService).updateOrCreate(8L, "sess-ohne", authentication, "curl/8.0");
    }

    /**
     * Ein Fehler beim Lesen des Schalters darf die Aufzeichnung nicht stillschweigend beenden —
     * das wäre ein Datenverlust, den niemand bemerkt, weil kein Request fehlschlägt.
     */
    @Test
    void schalterNichtLesbarZeichnetAuf() {
        angemeldeteSitzung("sess-fehler", 9L, "curl/8.0");
        when(security.getMandat()).thenThrow(new IllegalStateException("kein Mandant im Kontext"));

        filter.trackSessionAsync(httpRequest);

        verify(sessionAuditService).updateOrCreate(9L, "sess-fehler", authentication, "curl/8.0");
    }

    private void angemeldeteSitzung(String sessionId, Long userId, String userAgent) {
        SecurityContextHolder.setContext(securityContext);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn("admin");
        when(httpRequest.getSession(false)).thenReturn(httpSession);
        when(httpSession.getId()).thenReturn(sessionId);
        when(security.getId()).thenReturn(userId);
        when(httpRequest.getHeader("User-Agent")).thenReturn(userAgent);
    }
}
