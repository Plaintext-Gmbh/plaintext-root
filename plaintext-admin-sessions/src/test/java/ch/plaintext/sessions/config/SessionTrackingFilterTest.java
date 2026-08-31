/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sessions.config;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.sessions.service.HttpSessionRegistry;
import ch.plaintext.sessions.service.SessionAuditWriter;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionTrackingFilterTest {

    @Mock
    private SessionAuditWriter sessionAuditWriter;

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
        filter = new SessionTrackingFilter(sessionAuditWriter, security, sessionRegistry, setupConfigProvider);
        // Card 627: the default for the existing cases is "switch on" — they check the
        // behaviour BEFORE the switch and must keep documenting it unchanged. lenient(), because
        // most cases bail out before the switch is even read.
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
    void sitzungsverfolgung_TracksAuthenticatedUser() throws Exception {
        SecurityContextHolder.setContext(securityContext);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn("admin");
        when(httpRequest.getSession(false)).thenReturn(httpSession);
        when(httpSession.getId()).thenReturn("sess-123");
        when(security.getId()).thenReturn(42L);
        when(httpRequest.getHeader("User-Agent")).thenReturn("TestAgent/1.0");

        filter.doFilter(httpRequest, httpResponse, filterChain);

        verify(sessionRegistry).registerSession("sess-123", httpSession);
        verify(sessionAuditWriter).schreibe(42L, "sess-123", authentication, "TestAgent/1.0");
    }

    @Test
    void sitzungsverfolgung_SkipsAnonymousUser() throws Exception {
        SecurityContextHolder.setContext(securityContext);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn("anonymousUser");

        filter.doFilter(httpRequest, httpResponse, filterChain);

        verifyNoInteractions(sessionRegistry);
        verifyNoInteractions(sessionAuditWriter);
    }

    @Test
    void sitzungsverfolgung_SkipsUnauthenticatedUser() throws Exception {
        SecurityContextHolder.setContext(securityContext);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(false);

        filter.doFilter(httpRequest, httpResponse, filterChain);

        verifyNoInteractions(sessionRegistry);
        verifyNoInteractions(sessionAuditWriter);
    }

    @Test
    void sitzungsverfolgung_SkipsNullAuthentication() throws Exception {
        SecurityContextHolder.setContext(securityContext);
        when(securityContext.getAuthentication()).thenReturn(null);

        filter.doFilter(httpRequest, httpResponse, filterChain);

        verifyNoInteractions(sessionRegistry);
        verifyNoInteractions(sessionAuditWriter);
    }

    @Test
    void sitzungsverfolgung_SkipsNullSession() throws Exception {
        SecurityContextHolder.setContext(securityContext);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn("admin");
        when(httpRequest.getSession(false)).thenReturn(null);

        filter.doFilter(httpRequest, httpResponse, filterChain);

        verifyNoInteractions(sessionRegistry);
        verifyNoInteractions(sessionAuditWriter);
    }

    @Test
    void sitzungsverfolgung_SkipsNullUserId() throws Exception {
        SecurityContextHolder.setContext(securityContext);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn("admin");
        when(httpRequest.getSession(false)).thenReturn(httpSession);
        when(httpSession.getId()).thenReturn("sess-123");
        when(security.getId()).thenReturn(null);

        filter.doFilter(httpRequest, httpResponse, filterChain);

        verifyNoInteractions(sessionRegistry);
        verifyNoInteractions(sessionAuditWriter);
    }

    @Test
    void sitzungsverfolgung_HandlesExceptionGracefully() throws Exception {
        SecurityContextHolder.setContext(securityContext);
        when(securityContext.getAuthentication()).thenThrow(new RuntimeException("Unexpected error"));

        // Should not throw
        filter.doFilter(httpRequest, httpResponse, filterChain);

        verifyNoInteractions(sessionRegistry);
        verifyNoInteractions(sessionAuditWriter);
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
        verify(sessionAuditWriter).schreibe(99L, "sess-456", authentication, "Mozilla/5.0");
        verify(filterChain).doFilter(httpRequest, httpResponse);
    }

    // ── Card 627: the switch ────────────────────────────────────────────────────────────────

    /**
     * The point of the card: switch off → no new entry. The registration in the transient
     * {@link HttpSessionRegistry} keeps running, otherwise ROOT would lose the forced logout.
     */
    @Test
    void schalterAusZeichnetNichtAufLaesstAberDasAbmeldenIntakt() throws Exception {
        angemeldeteSitzung("sess-aus", 7L, "Firefox/1.0");
        when(security.getMandat()).thenReturn("default");
        when(setupConfigService.isSessionTrackingEnabled("default")).thenReturn(false);

        filter.doFilter(httpRequest, httpResponse, filterChain);

        verify(sessionRegistry).registerSession("sess-aus", httpSession);
        verifyNoInteractions(sessionAuditWriter);
    }

    @Test
    void schalterAnZeichnetAuf() throws Exception {
        angemeldeteSitzung("sess-an", 7L, "Firefox/1.0");
        when(security.getMandat()).thenReturn("default");
        when(setupConfigService.isSessionTrackingEnabled("default")).thenReturn(true);

        filter.doFilter(httpRequest, httpResponse, filterChain);

        verify(sessionAuditWriter).schreibe(7L, "sess-an", authentication, "Firefox/1.0");
    }

    /** Application without the module {@code plaintext-admin-settings}: record as before the card. */
    @Test
    void ohneSettingsModulWirdAufgezeichnet() throws Exception {
        angemeldeteSitzung("sess-ohne", 8L, "curl/8.0");
        when(setupConfigProvider.getIfAvailable()).thenReturn(null);

        filter.doFilter(httpRequest, httpResponse, filterChain);

        verify(sessionAuditWriter).schreibe(8L, "sess-ohne", authentication, "curl/8.0");
    }

    /**
     * An error while reading the switch must not silently end the recording —
     * that would be a data loss nobody notices, because no request fails.
     */
    @Test
    void schalterNichtLesbarZeichnetAuf() throws Exception {
        angemeldeteSitzung("sess-fehler", 9L, "curl/8.0");
        when(security.getMandat()).thenThrow(new IllegalStateException("kein Mandant im Kontext"));

        filter.doFilter(httpRequest, httpResponse, filterChain);

        verify(sessionAuditWriter).schreibe(9L, "sess-fehler", authentication, "curl/8.0");
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

    // ---------------------------------------------------------------- card 968: proxy contract

    /**
     * Records why {@code @Async} no longer sits in the filter (Sonar {@code java:S6809}).
     *
     * <p>Previously {@code trackSessionAsync} carried the annotation and was called via {@code this} —
     * past the Spring proxy, so without effect. Whoever brings it back must attach it to a
     * <b>different</b> bean: the method read {@code SecurityContextHolder}, the
     * {@code HttpServletRequest} and {@code PlaintextSecurity}, and those are gone on a pool thread.
     * The recording would have stopped silently — a data loss nobody notices.
     */
    @Test
    void asyncGehoertAnDenSchreiber_nichtAnDenFilter() {
        assertThat(java.util.Arrays.stream(SessionTrackingFilter.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(org.springframework.scheduling.annotation.Async.class))
                .map(java.lang.reflect.Method::getName).toList())
                .as("Ein @Async im Filter selbst wuerde per Selbstaufruf wieder wirkungslos sein")
                .isEmpty();

        assertThat(java.util.Arrays.stream(SessionAuditWriter.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(org.springframework.scheduling.annotation.Async.class))
                .map(java.lang.reflect.Method::getName).toList())
                .as("Ohne @Async am Schreiber liefe die Aufzeichnung wieder im Request-Thread")
                .containsExactly("schreibe");
    }
}
