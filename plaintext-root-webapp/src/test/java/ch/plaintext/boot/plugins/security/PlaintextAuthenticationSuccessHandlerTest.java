/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for PlaintextAuthenticationSuccessHandler - login success handling.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlaintextAuthenticationSuccessHandlerTest {

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private ch.plaintext.boot.plugins.security.totp.TotpAuthenticationService totpAuthenticationService;

    @Mock
    private org.springframework.security.web.context.SecurityContextRepository securityContextRepository;

    @Mock
    private PersistentTokenBasedRememberMeServices rememberMeServices;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    private PlaintextSecurityProperties securityProperties;

    private PlaintextAuthenticationSuccessHandler handler;

    @BeforeEach
    void setUp() {
        when(request.getScheme()).thenReturn("https");
        when(request.getServerName()).thenReturn("example.com");
        when(request.getServerPort()).thenReturn(443);

        securityProperties = new PlaintextSecurityProperties();
        // Default in production is true; tests set it explicitly per case where relevant.
        securityProperties.setRememberMeOnOauth(true);
        handler = new PlaintextAuthenticationSuccessHandler(eventPublisher, totpAuthenticationService,
                securityContextRepository, securityProperties, rememberMeServices);
    }

    private static OAuth2AuthenticationToken oauthToken(String... authorities) {
        List<SimpleGrantedAuthority> granted = Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .toList();
        OAuth2User principal = new DefaultOAuth2User(granted, Map.of("sub", "user@test.com"), "sub");
        return new OAuth2AuthenticationToken(principal, granted, "keycloak");
    }

    @Test
    void onAuthenticationSuccess_shouldRedirectToDefault_whenNoStartpage() throws Exception {
        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", "pass",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

        handler.onAuthenticationSuccess(request, response, auth);

        verify(response).sendRedirect("/index.html");
    }

    @Test
    void onAuthenticationSuccess_shouldRedirectToStartpage_whenConfigured() throws Exception {
        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", "pass",
                Arrays.asList(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("PROPERTY_STARTPAGE_dashboard.html")
                ));

        handler.onAuthenticationSuccess(request, response, auth);

        verify(response).sendRedirect("/dashboard.html");
    }

    @Test
    void onAuthenticationSuccess_shouldPublishLoginEvent() throws Exception {
        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", "pass",
                Arrays.asList(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("PROPERTY_MYUSERID_42"),
                        new SimpleGrantedAuthority("PROPERTY_MANDAT_production")
                ));

        handler.onAuthenticationSuccess(request, response, auth);

        verify(eventPublisher).publishEvent(any(PlaintextLoginEvent.class));
    }

    @Test
    void onAuthenticationSuccess_shouldExtractUserId() throws Exception {
        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", "pass",
                Arrays.asList(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("PROPERTY_MYUSERID_42")
                ));

        handler.onAuthenticationSuccess(request, response, auth);

        ArgumentCaptor<PlaintextLoginEvent> captor = ArgumentCaptor.forClass(PlaintextLoginEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        PlaintextLoginEvent event = captor.getValue();
        assertEquals(42L, event.getUserId());
    }

    @Test
    void onAuthenticationSuccess_shouldReturnMinusOne_whenNoUserIdAuthority() throws Exception {
        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", "pass",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

        handler.onAuthenticationSuccess(request, response, auth);

        ArgumentCaptor<PlaintextLoginEvent> captor = ArgumentCaptor.forClass(PlaintextLoginEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        PlaintextLoginEvent event = captor.getValue();
        assertEquals(-1L, event.getUserId());
    }

    @Test
    void onAuthenticationSuccess_shouldExtractMandat() throws Exception {
        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", "pass",
                Arrays.asList(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("PROPERTY_MANDAT_TESTMANDAT")
                ));

        handler.onAuthenticationSuccess(request, response, auth);

        ArgumentCaptor<PlaintextLoginEvent> captor = ArgumentCaptor.forClass(PlaintextLoginEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        PlaintextLoginEvent event = captor.getValue();
        assertEquals("testmandat", event.getMandat());
    }

    @Test
    void onAuthenticationSuccess_shouldReturnDefaultMandat_whenNoMandatAuthority() throws Exception {
        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", "pass",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

        handler.onAuthenticationSuccess(request, response, auth);

        ArgumentCaptor<PlaintextLoginEvent> captor = ArgumentCaptor.forClass(PlaintextLoginEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        PlaintextLoginEvent event = captor.getValue();
        assertEquals("default", event.getMandat());
    }

    @Test
    void onAuthenticationSuccess_shouldExtractBaseUrl_withDefaultPort() throws Exception {
        // Karte 1068: der Request liefert hier dieselbe Adresse wie die Konfiguration — der Test
        // belegt damit nur noch, dass die konfigurierte Adresse unveraendert ankommt.
        when(request.getScheme()).thenReturn("https");
        when(request.getServerName()).thenReturn("app.example.com");
        when(request.getServerPort()).thenReturn(443);
        org.springframework.test.util.ReflectionTestUtils.setField(handler, "plaintextBaseurl", "https://app.example.com");

        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", "pass",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

        handler.onAuthenticationSuccess(request, response, auth);

        ArgumentCaptor<PlaintextLoginEvent> captor = ArgumentCaptor.forClass(PlaintextLoginEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        PlaintextLoginEvent event = captor.getValue();
        assertEquals("https://app.example.com", event.getRequestBaseUrl());
    }

    @Test
    void onAuthenticationSuccess_basisUrlKommtAusDerKonfiguration_nichtAusDemRequest() throws Exception {
        // Karte 1068: bis 05.09.2026 stand hier http://localhost:8080 aus Schema, Servername und
        // Port des Requests. Jetzt zaehlt nur die Konfiguration, der Request wird nicht befragt.
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("localhost");
        when(request.getServerPort()).thenReturn(8080);
        org.springframework.test.util.ReflectionTestUtils.setField(handler, "plaintextBaseurl",
                "http://konfiguriert.example:8080/");

        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", "pass",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

        handler.onAuthenticationSuccess(request, response, auth);

        ArgumentCaptor<PlaintextLoginEvent> captor = ArgumentCaptor.forClass(PlaintextLoginEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        PlaintextLoginEvent event = captor.getValue();
        assertEquals("http://konfiguriert.example:8080", event.getRequestBaseUrl(),
                "konfigurierte Adresse ohne Endslash, nicht localhost:8080 aus dem Request");
        verify(request, never()).getServerName();
    }

    @Test
    void onAuthenticationSuccess_ignoriertForwardedHeaders_Karte1068() throws Exception {
        // Diese drei Koepfe bestimmten bis 05.09.2026 die Basis-URL des Login-Events — eine
        // Adresse, die der Client waehlen konnte. Sie duerfen keine Wirkung mehr haben.
        when(request.getHeader("X-Forwarded-Proto")).thenReturn("https");
        when(request.getHeader("X-Forwarded-Host")).thenReturn("pruefung.invalid");
        when(request.getHeader("X-Forwarded-Port")).thenReturn("443");
        when(request.getServerName()).thenReturn("pruefung.invalid");
        when(request.getServerPort()).thenReturn(8080);
        org.springframework.test.util.ReflectionTestUtils.setField(handler, "plaintextBaseurl",
                "https://app.plaintext.ch");

        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", "pass",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

        handler.onAuthenticationSuccess(request, response, auth);

        ArgumentCaptor<PlaintextLoginEvent> captor = ArgumentCaptor.forClass(PlaintextLoginEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        PlaintextLoginEvent ereignis = captor.getValue();
        assertEquals("https://app.plaintext.ch", ereignis.getRequestBaseUrl());
        assertFalse(ereignis.getRequestBaseUrl().contains("pruefung.invalid"),
                "der gefaelschte Host darf nirgends in der Basis-URL auftauchen");
        verify(request, never()).getHeader("X-Forwarded-Host");
        verify(request, never()).getHeader("X-Forwarded-Proto");
    }

    @Test
    void onAuthenticationSuccess_ohneKonfigurationBleibtDieBasisUrlLeer() throws Exception {
        when(request.getServerName()).thenReturn("pruefung.invalid");
        org.springframework.test.util.ReflectionTestUtils.setField(handler, "plaintextBaseurl", "");

        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", "pass",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

        handler.onAuthenticationSuccess(request, response, auth);

        ArgumentCaptor<PlaintextLoginEvent> captor = ArgumentCaptor.forClass(PlaintextLoginEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals("", captor.getValue().getRequestBaseUrl(),
                "lieber leer als eine Adresse, die der Aufrufer bestimmt hat");
    }

    @Test
    void onAuthenticationSuccess_altText_shouldUseForwardedHeaders_ersetztDurchKarte1068() throws Exception {
        // Der fruehere Test verlangte hier "https://proxy.example.com" aus X-Forwarded-Host.
        // Genau dieses Verhalten ist die Schwachstelle aus Karte 1068; die Erwartung ist umgekehrt.
        when(request.getHeader("X-Forwarded-Proto")).thenReturn("https");
        when(request.getHeader("X-Forwarded-Host")).thenReturn("proxy.example.com");
        when(request.getHeader("X-Forwarded-Port")).thenReturn("443");
        when(request.getServerPort()).thenReturn(8080);
        org.springframework.test.util.ReflectionTestUtils.setField(handler, "plaintextBaseurl", "https://konfiguriert.example");

        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", "pass",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

        handler.onAuthenticationSuccess(request, response, auth);

        ArgumentCaptor<PlaintextLoginEvent> captor = ArgumentCaptor.forClass(PlaintextLoginEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        PlaintextLoginEvent event = captor.getValue();
        assertEquals("https://konfiguriert.example", event.getRequestBaseUrl(),
                "proxy.example.com aus X-Forwarded-Host darf nicht mehr gewinnen");
    }

    @Test
    void onAuthenticationSuccess_shouldContinueEvenIfEventPublishingFails() throws Exception {
        doThrow(new RuntimeException("Event error")).when(eventPublisher).publishEvent(any());

        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", "pass",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

        handler.onAuthenticationSuccess(request, response, auth);

        // Should still redirect
        verify(response).sendRedirect("/index.html");
    }

    @Test
    void onAuthenticationSuccess_shouldHandleInvalidForwardedPort() throws Exception {
        when(request.getHeader("X-Forwarded-Port")).thenReturn("not-a-number");
        when(request.getServerPort()).thenReturn(8080);

        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", "pass",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

        handler.onAuthenticationSuccess(request, response, auth);

        // Should still work, using the original port
        verify(response).sendRedirect("/index.html");
    }

    @Test
    void onAuthenticationSuccess_shouldUseFirstStartpageFound() throws Exception {
        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", "pass",
                Arrays.asList(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("PROPERTY_STARTPAGE_first.html"),
                        new SimpleGrantedAuthority("PROPERTY_STARTPAGE_second.html")
                ));

        handler.onAuthenticationSuccess(request, response, auth);

        verify(response).sendRedirect("/first.html");
    }

    // --- Forced password change (card 306) ----------------------------------------------

    @Test
    void onAuthenticationSuccess_shouldRedirectToPasswordPage_whenMustChangePassword() throws Exception {
        // Even with a configured start page the forced password change wins.
        Authentication auth = new UsernamePasswordAuthenticationToken("root@root.root", "pass",
                Arrays.asList(
                        new SimpleGrantedAuthority("ROLE_ROOT"),
                        new SimpleGrantedAuthority("PROPERTY_STARTPAGE_dashboard.html"),
                        new SimpleGrantedAuthority(
                                ch.plaintext.boot.plugins.security.service.MyUserDetailsService.MUST_CHANGE_PASSWORD_AUTHORITY)
                ));

        handler.onAuthenticationSuccess(request, response, auth);

        verify(response).sendRedirect("/" + PlaintextAuthenticationSuccessHandler.MUST_CHANGE_PASSWORD_PAGE);
        verify(response, never()).sendRedirect("/dashboard.html");
    }

    // --- Remember-Me on OAuth (Auth-1) -------------------------------------------------

    @Test
    void onAuthenticationSuccess_shouldIssueRememberMe_forOAuthLogin_whenEnabled() throws Exception {
        securityProperties.setRememberMeOnOauth(true);
        Authentication auth = oauthToken("ROLE_USER");

        handler.onAuthenticationSuccess(request, response, auth);

        // OAuth login: the AbstractAuthenticationProcessingFilter does NOT call loginSuccess,
        // so the handler must do it to leave a persistent cookie.
        verify(rememberMeServices).loginSuccess(request, response, auth);
        verify(response).sendRedirect("/index.html");
    }

    @Test
    void onAuthenticationSuccess_shouldNotIssueRememberMe_forFormLogin() throws Exception {
        securityProperties.setRememberMeOnOauth(true);
        // Form login (UsernamePasswordAuthenticationToken): the filter already called
        // loginSuccess itself. A second call here would create a duplicate PERSISTENT_LOGINS row.
        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", "pass",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

        handler.onAuthenticationSuccess(request, response, auth);

        verify(rememberMeServices, never()).loginSuccess(any(), any(), any());
        verify(response).sendRedirect("/index.html");
    }

    @Test
    void onAuthenticationSuccess_shouldNotIssueRememberMe_forOAuthLogin_whenDisabled() throws Exception {
        securityProperties.setRememberMeOnOauth(false);
        Authentication auth = oauthToken("ROLE_USER");

        handler.onAuthenticationSuccess(request, response, auth);

        // Flag off => legacy behaviour: no remember-me cookie even on OAuth login.
        verify(rememberMeServices, never()).loginSuccess(any(), any(), any());
        verify(response).sendRedirect("/index.html");
    }

    @Test
    void onAuthenticationSuccess_shouldStillRedirect_whenRememberMeThrows() throws Exception {
        securityProperties.setRememberMeOnOauth(true);
        doThrow(new RuntimeException("cookie error"))
                .when(rememberMeServices).loginSuccess(any(), any(), any());
        Authentication auth = oauthToken("ROLE_USER");

        handler.onAuthenticationSuccess(request, response, auth);

        // A remember-me failure must never block the login redirect.
        verify(response).sendRedirect("/index.html");
    }
}
