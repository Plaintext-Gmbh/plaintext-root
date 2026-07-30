/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Extended tests for PlaintextAuthenticationSuccessHandler covering
 * startpage extraction, base URL extraction, mandat extraction, user ID extraction.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlaintextAuthenticationSuccessHandlerExtendedTest {

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private ch.plaintext.boot.plugins.security.totp.TotpAuthenticationService totpAuthenticationService;

    @Mock
    private org.springframework.security.web.context.SecurityContextRepository securityContextRepository;

    @Mock
    private PlaintextSecurityProperties securityProperties;

    @Mock
    private PersistentTokenBasedRememberMeServices rememberMeServices;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @InjectMocks
    private PlaintextAuthenticationSuccessHandler handler;

    @BeforeEach
    void setUp() {
        when(request.getScheme()).thenReturn("https");
        when(request.getServerName()).thenReturn("example.com");
        when(request.getServerPort()).thenReturn(443);
    }

    @Test
    void onAuthenticationSuccess_shouldRedirectToDefaultPage() throws Exception {
        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("PROPERTY_MYUSERID_1")));

        handler.onAuthenticationSuccess(request, response, auth);

        verify(response).sendRedirect("/index.html");
    }

    @Test
    void onAuthenticationSuccess_shouldRedirectToStartpage() throws Exception {
        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", "pass",
                Arrays.asList(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("PROPERTY_MYUSERID_1"),
                        new SimpleGrantedAuthority("PROPERTY_STARTPAGE_dashboard.html")
                ));

        handler.onAuthenticationSuccess(request, response, auth);

        verify(response).sendRedirect("/dashboard.html");
    }

    @Test
    void onAuthenticationSuccess_shouldPublishLoginEvent() throws Exception {
        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("PROPERTY_MYUSERID_42"),
                        new SimpleGrantedAuthority("PROPERTY_MANDAT_production")));

        handler.onAuthenticationSuccess(request, response, auth);

        verify(eventPublisher).publishEvent(any(PlaintextLoginEvent.class));
    }

    @Test
    void onAuthenticationSuccess_shouldHandleEventPublishFailure() throws Exception {
        doThrow(new RuntimeException("event error")).when(eventPublisher).publishEvent(any());

        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("PROPERTY_MYUSERID_1")));

        // Should not throw even if event publishing fails
        handler.onAuthenticationSuccess(request, response, auth);

        verify(response).sendRedirect("/index.html");
    }

    @Test
    void onAuthenticationSuccess_shouldExtractBaseUrl_withForwardedHeaders() throws Exception {
        when(request.getHeader("X-Forwarded-Proto")).thenReturn("https");
        when(request.getHeader("X-Forwarded-Host")).thenReturn("app.example.com");
        when(request.getHeader("X-Forwarded-Port")).thenReturn("8443");

        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("PROPERTY_MYUSERID_1")));

        handler.onAuthenticationSuccess(request, response, auth);

        verify(eventPublisher).publishEvent(any(PlaintextLoginEvent.class));
    }

    @Test
    void onAuthenticationSuccess_shouldExtractBaseUrl_withDefaultPorts() throws Exception {
        when(request.getScheme()).thenReturn("http");
        when(request.getServerPort()).thenReturn(80);

        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("PROPERTY_MYUSERID_1")));

        handler.onAuthenticationSuccess(request, response, auth);

        verify(eventPublisher).publishEvent(any(PlaintextLoginEvent.class));
    }

    @Test
    void onAuthenticationSuccess_shouldExtractBaseUrl_withNonDefaultPort() throws Exception {
        when(request.getScheme()).thenReturn("http");
        when(request.getServerPort()).thenReturn(8080);

        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("PROPERTY_MYUSERID_1")));

        handler.onAuthenticationSuccess(request, response, auth);

        verify(eventPublisher).publishEvent(any(PlaintextLoginEvent.class));
    }

    @Test
    void onAuthenticationSuccess_shouldHandleInvalidForwardedPort() throws Exception {
        when(request.getHeader("X-Forwarded-Port")).thenReturn("invalid");

        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("PROPERTY_MYUSERID_1")));

        handler.onAuthenticationSuccess(request, response, auth);

        verify(response).sendRedirect("/index.html");
    }

    @Test
    void onAuthenticationSuccess_shouldExtractMandat_default_whenMissing() throws Exception {
        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("PROPERTY_MYUSERID_1")));

        handler.onAuthenticationSuccess(request, response, auth);

        verify(eventPublisher).publishEvent(any(PlaintextLoginEvent.class));
    }

    @Test
    void onAuthenticationSuccess_shouldExtractUserId_minusOne_whenMissing() throws Exception {
        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", "pass",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

        handler.onAuthenticationSuccess(request, response, auth);

        verify(eventPublisher).publishEvent(any(PlaintextLoginEvent.class));
    }

    @Test
    void onAuthenticationSuccess_shouldExtractUserId_withInvalidNumber() throws Exception {
        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("PROPERTY_MYUSERID_notanumber")));

        handler.onAuthenticationSuccess(request, response, auth);

        verify(eventPublisher).publishEvent(any(PlaintextLoginEvent.class));
    }

    @Test
    void onAuthenticationSuccess_shouldHandleEmptyAuthorities() throws Exception {
        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", "pass",
                Collections.emptyList());

        handler.onAuthenticationSuccess(request, response, auth);

        verify(response).sendRedirect("/index.html");
    }

    @Test
    void onAuthenticationSuccess_shouldUseFirstStartpageFound() throws Exception {
        Authentication auth = new UsernamePasswordAuthenticationToken("user@test.com", "pass",
                Arrays.asList(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("PROPERTY_STARTPAGE_first.html"),
                        new SimpleGrantedAuthority("PROPERTY_STARTPAGE_second.html"),
                        new SimpleGrantedAuthority("PROPERTY_MYUSERID_1")
                ));

        handler.onAuthenticationSuccess(request, response, auth);

        verify(response).sendRedirect("/first.html");
    }
}
