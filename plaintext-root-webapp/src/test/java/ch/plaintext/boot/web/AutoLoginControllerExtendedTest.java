/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import ch.plaintext.boot.plugins.security.PlaintextLoginEvent;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.settings.ISetupConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ergaenzende Tests fuer den {@link AutoLoginController}: Startseiten-Aufloesung, Login-Event und
 * Mandat-Ermittlung.
 *
 * <p><b>Karte 309:</b> Startseite, Login-Event und Base-URL-Ermittlung liegen nicht mehr im
 * Controller, sondern im {@code PlaintextAuthenticationSuccessHandler} — demselben, den auch der
 * Form-Login benutzt. Getestet wird hier deshalb das Ergebnis ueber den echten Handler, nicht mehr
 * die (entfernte) Controller-eigene Kopie.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AutoLoginControllerExtendedTest {

    @Mock
    private UserDetailsService userDetailsService;

    @Mock
    private MyUserRepository userRepository;

    @Mock
    private ISetupConfigService setupConfigService;

    private LoginTestSupport.Aufbau aufbau;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private AutoLoginController controller;

    @BeforeEach
    void setUp() {
        aufbau = LoginTestSupport.baueAuf();
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        controller = new AutoLoginController(userDetailsService, userRepository, setupConfigService,
                aufbau.finalizer());
        when(setupConfigService.isAutologinEnabled(anyString())).thenReturn(true);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void user(List<GrantedAuthority> authorities) {
        MyUserEntity user = new MyUserEntity();
        user.setId(1L);
        user.setUsername("user@test.com");
        user.setAutologinKey("key123");
        UserDetails userDetails = new User("user@test.com", "pass", authorities);
        when(userRepository.findByAutologinKey("key123")).thenReturn(user);
        when(userDetailsService.loadUserByUsername("user@test.com")).thenReturn(userDetails);
    }

    @Test
    void autoLogin_shouldRedirectToStartpage_whenConfigured() {
        user(List.of(new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_MYUSERID_1"),
                new SimpleGrantedAuthority("PROPERTY_MANDAT_dev"),
                new SimpleGrantedAuthority("PROPERTY_STARTPAGE_dashboard.html")));

        assertNull(controller.autoLogin("key123", request, response));
        assertEquals("/dashboard.html", response.getRedirectedUrl());
    }

    @Test
    void autoLogin_shouldRedirectToIndex_whenNoStartpage() {
        user(List.of(new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_MYUSERID_1")));

        assertNull(controller.autoLogin("key123", request, response));
        assertEquals("/index.html", response.getRedirectedUrl());
    }

    @Test
    @DisplayName("unbrauchbare Startseite fuehrt auf index.html statt ins Leere")
    void autoLogin_ungueltigeStartpage_faelltAufIndexZurueck() {
        user(List.of(new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_STARTPAGE_https://evil.example.com/")));

        assertNull(controller.autoLogin("key123", request, response));
        assertEquals("/index.html", response.getRedirectedUrl());
    }

    @Test
    void autoLogin_shouldPublishLoginEvent() {
        user(List.of(new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_MYUSERID_1"),
                new SimpleGrantedAuthority("PROPERTY_MANDAT_dev")));
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "example.com");
        request.addHeader("X-Forwarded-Port", "443");

        controller.autoLogin("key123", request, response);

        ArgumentCaptor<PlaintextLoginEvent> captor = ArgumentCaptor.forClass(PlaintextLoginEvent.class);
        verify(aufbau.eventPublisher()).publishEvent(captor.capture());
        PlaintextLoginEvent event = captor.getValue();
        assertEquals("user@test.com", event.getUserEmail());
        assertEquals("dev", event.getMandat());
        assertEquals("https://example.com", event.getRequestBaseUrl());
    }

    @Test
    void autoLogin_shouldHandleEventPublishFailure() {
        user(List.of(new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_MYUSERID_1")));
        doThrow(new RuntimeException("event error")).when(aufbau.eventPublisher())
                .publishEvent(org.mockito.ArgumentMatchers.any(PlaintextLoginEvent.class));

        // Der Login gelingt trotzdem — das Event ist ein Hook, kein Gate.
        assertNull(controller.autoLogin("key123", request, response));
        assertEquals("/index.html", response.getRedirectedUrl());
    }

    @Test
    void autoLogin_shouldExtractMandat_defaultWhenMissing() {
        user(List.of(new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_MYUSERID_1")));

        controller.autoLogin("key123", request, response);

        // Ohne PROPERTY_MANDAT_-Authority wird "default" angefragt (Autologin-Freigabe je Mandat).
        verify(setupConfigService).isAutologinEnabled("default");
    }

    @Test
    void autoLogin_shouldHandleInvalidUserIdInAuthority() {
        user(List.of(new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_MYUSERID_not_a_number")));

        assertNull(controller.autoLogin("key123", request, response));
        assertEquals("/index.html", response.getRedirectedUrl());
    }
}
