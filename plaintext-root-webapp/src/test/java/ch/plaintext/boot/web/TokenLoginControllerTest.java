/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import ch.plaintext.apitoken.IApiTokenService;
import ch.plaintext.apitoken.IApiTokenService.ApiTokenValidationResult;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.boot.plugins.security.totp.TotpPendingAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test fuer den {@link TokenLoginController} — sicherheitskritischer Session-Bootstrap aus ApiToken-JWT.
 *
 * <p>Deckt neben dem legitimen Flow die Karte-309-Invarianten ab: Scope-Zwang (fail-closed),
 * Session-Erneuerung, Lockout, 2FA-Gate und den betrieblichen Not-Aus.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenLoginControllerTest {

    @Mock
    private IApiTokenService apiTokenService;
    @Mock
    private UserDetailsService userDetailsService;
    @Mock
    private MyUserRepository userRepository;

    private LoginTestSupport.Aufbau aufbau;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private TokenLoginController controller;

    private MyUserEntity testUser;
    private UserDetails userDetails;

    @BeforeEach
    void setUp() {
        aufbau = LoginTestSupport.baueAuf();
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        controller = new TokenLoginController(apiTokenService, userDetailsService, userRepository,
                aufbau.finalizer(), aufbau.securityProperties());

        testUser = new MyUserEntity();
        testUser.setId(123L);
        testUser.setUsername("kiosk@example.com");
        testUser.setPassword("encoded");

        userDetails = new User("kiosk@example.com", "encoded", authorities());

        when(userRepository.findById(123L)).thenReturn(Optional.of(testUser));
        when(userDetailsService.loadUserByUsername("kiosk@example.com")).thenReturn(userDetails);

        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static List<GrantedAuthority> authorities() {
        return List.of(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_MYUSERID_123"),
                new SimpleGrantedAuthority("PROPERTY_MANDAT_default"));
    }

    private ApiTokenValidationResult result(String mandat, String scope) {
        return new ApiTokenValidationResult(123L, mandat, "kiosk@example.com", "KIOSK_TOKEN",
                Instant.now().plusSeconds(3600), scope);
    }

    // ==================== Legitimer Flow ====================

    @Test
    @DisplayName("SESSION-Token baut die Session auf und landet auf der Startseite")
    void gueltigesToken_bautSessionAuf_undRedirectStartpage() {
        userDetails = new User("kiosk@example.com", "encoded",
                List.of(new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("PROPERTY_MANDAT_default"),
                        new SimpleGrantedAuthority("PROPERTY_STARTPAGE_dashboard.html")));
        when(userDetailsService.loadUserByUsername("kiosk@example.com")).thenReturn(userDetails);
        when(apiTokenService.validateToken("jwt-valid")).thenReturn(Optional.of(result("default", "SESSION")));

        String view = controller.tokenLogin("jwt-valid", request, response);

        assertNull(view);
        assertEquals("/dashboard.html", response.getRedirectedUrl());
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("kiosk@example.com", SecurityContextHolder.getContext().getAuthentication().getName());
        verify(aufbau.securityContextRepository()).saveContext(any(), any(), any());
        assertTrue(String.valueOf(response.getHeader("Cache-Control")).contains("no-store"));
    }

    @Test
    void ohneStartpage_redirectIndex() {
        when(apiTokenService.validateToken("jwt-valid")).thenReturn(Optional.of(result("default", "SESSION")));

        assertNull(controller.tokenLogin("jwt-valid", request, response));
        assertEquals("/index.html", response.getRedirectedUrl());
    }

    @Test
    @DisplayName("ADMIN-Token darf KEINE Browser-Session mehr eroeffnen (Karte 544)")
    void adminScope_wirdAbgelehnt() {
        // Bis 05.08.2026 war ADMIN zugelassen, damit bestehende Vollzugriffs-Tokens
        // weiterfunktionieren. Damit war jedes ADMIN-MCP-Token zugleich ein Generalschluessel fuer
        // eine Browser-Session mit den DB-Rollen seines Besitzers — und diese Tokens liegen im
        // Klartext in MCP-Konfigurationen. Erhebung vor der Umstellung: in 30 Tagen kein einziger
        // erfolgreicher /token-login, die drei ADMIN-Tokens mit use_count 0.
        when(apiTokenService.validateToken("jwt-admin")).thenReturn(Optional.of(result("default", "admin")));

        assertEquals("redirect:/login.html", controller.tokenLogin("jwt-admin", request, response));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(aufbau.securityContextRepository(), never()).saveContext(any(), any(), any());
    }

    // ==================== Karte 309: Scope-Zwang ====================

    @Test
    @DisplayName("READ-Token darf KEINE Browser-Session mit den vollen DB-Rollen eroeffnen")
    void readScope_wirdAbgelehnt() {
        when(apiTokenService.validateToken("jwt-read")).thenReturn(Optional.of(result("default", "READ")));

        String view = controller.tokenLogin("jwt-read", request, response);

        assertEquals("redirect:/login.html", view);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(aufbau.securityContextRepository(), never()).saveContext(any(), any(), any());
    }

    @Test
    void eintragenScope_wirdAbgelehnt() {
        when(apiTokenService.validateToken("jwt-ein")).thenReturn(Optional.of(result("default", "EINTRAGEN")));

        assertEquals("redirect:/login.html", controller.tokenLogin("jwt-ein", request, response));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("Token ohne scope-Claim wird abgelehnt (fail-closed)")
    void fehlenderScope_wirdAbgelehnt() {
        when(apiTokenService.validateToken("jwt-noscope")).thenReturn(Optional.of(result("default", null)));

        assertEquals("redirect:/login.html", controller.tokenLogin("jwt-noscope", request, response));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(aufbau.securityContextRepository(), never()).saveContext(any(), any(), any());
    }

    // ==================== Karte 309: Not-Aus ====================

    @Test
    void deaktivierterEndpunkt_lehntAllesAb() {
        aufbau.securityProperties().getTokenLogin().setEnabled(false);
        when(apiTokenService.validateToken(any())).thenReturn(Optional.of(result("default", "SESSION")));

        assertEquals("redirect:/login.html", controller.tokenLogin("jwt-valid", request, response));
        verify(apiTokenService, never()).validateToken(any());
    }

    // ==================== Karte 309: Session-Fixation / Lockout / 2FA ====================

    @Test
    void erneuertSessionId() {
        when(apiTokenService.validateToken("jwt-valid")).thenReturn(Optional.of(result("default", "SESSION")));
        String vorherigeId = request.getSession(true).getId();

        controller.tokenLogin("jwt-valid", request, response);

        assertNotEquals(vorherigeId, request.getSession(false).getId());
    }

    @Test
    void gesperrterAccount_wirdAbgelehnt() {
        userDetails = new User("kiosk@example.com", "encoded",
                true, true, true, /* accountNonLocked */ false, authorities());
        when(userDetailsService.loadUserByUsername("kiosk@example.com")).thenReturn(userDetails);
        when(apiTokenService.validateToken("jwt-valid")).thenReturn(Optional.of(result("default", "SESSION")));

        assertEquals("redirect:/login.html", controller.tokenLogin("jwt-valid", request, response));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(aufbau.securityContextRepository(), never()).saveContext(any(), any(), any());
    }

    @Test
    void totpUser_landetImPendingFlow() {
        when(apiTokenService.validateToken("jwt-valid")).thenReturn(Optional.of(result("default", "SESSION")));
        when(aufbau.totpAuthenticationService().isTotpRequired("kiosk@example.com")).thenReturn(true);

        assertNull(controller.tokenLogin("jwt-valid", request, response));
        assertEquals("/login/totp", response.getRedirectedUrl());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNotNull(request.getSession(false).getAttribute(TotpPendingAuthentication.SESSION_ATTRIBUTE));
    }

    // ==================== Bestehende Ablehnungsgruende ====================

    @Test
    void ungueltigesToken_keineSession_redirectLogin() {
        when(apiTokenService.validateToken("jwt-bad")).thenReturn(Optional.empty());

        assertEquals("redirect:/login.html", controller.tokenLogin("jwt-bad", request, response));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(aufbau.securityContextRepository(), never()).saveContext(any(), any(), any());
    }

    @Test
    void fehlendesToken_redirectLogin() {
        assertEquals("redirect:/login.html", controller.tokenLogin(null, request, response));
        verify(apiTokenService, never()).validateToken(any());
        verify(aufbau.securityContextRepository(), never()).saveContext(any(), any(), any());
    }

    @Test
    void mandatMismatch_keineSession_redirectLogin() {
        // Token lautet auf ein anderes Mandat als der User tatsaechlich hat (default).
        when(apiTokenService.validateToken("jwt-othermandat"))
                .thenReturn(Optional.of(result("fremd", "SESSION")));

        assertEquals("redirect:/login.html", controller.tokenLogin("jwt-othermandat", request, response));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(aufbau.securityContextRepository(), never()).saveContext(any(), any(), any());
    }

    @Test
    void userNichtGefunden_redirectLogin() {
        when(apiTokenService.validateToken("jwt-valid")).thenReturn(Optional.of(result("default", "SESSION")));
        when(userRepository.findById(123L)).thenReturn(Optional.empty());

        assertEquals("redirect:/login.html", controller.tokenLogin("jwt-valid", request, response));
        verify(aufbau.securityContextRepository(), never()).saveContext(any(), any(), any());
    }
}
