/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.boot.plugins.security.totp.TotpPendingAuthentication;
import ch.plaintext.settings.ISetupConfigService;
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
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests fuer den {@link AutoLoginController} — sicherheitskritischer Anmeldeweg.
 *
 * <p>Der Session-Aufbau laeuft ueber einen <b>echten</b> {@code SessionLoginFinalizer} inkl. echtem
 * {@code PlaintextAuthenticationSuccessHandler} (siehe {@link LoginTestSupport}), damit die Karte-309-
 * Invarianten (neue Session-Id, Lockout, 2FA) hier auch wirklich geprueft werden.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AutoLoginControllerTest {

    @Mock
    private UserDetailsService userDetailsService;

    @Mock
    private MyUserRepository userRepository;

    @Mock
    private ISetupConfigService setupConfigService;

    private LoginTestSupport.Aufbau aufbau;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private AutoLoginController autoLoginController;

    private MyUserEntity testUser;
    private UserDetails userDetails;

    @BeforeEach
    void setUp() {
        aufbau = LoginTestSupport.baueAuf();
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        autoLoginController = new AutoLoginController(userDetailsService, userRepository, setupConfigService,
                aufbau.finalizer());

        testUser = new MyUserEntity();
        testUser.setId(123L);
        testUser.setUsername("test@example.com");
        testUser.setPassword("encodedPassword");
        testUser.setAutologinKey("validKey123");

        userDetails = new User("test@example.com", "encodedPassword", authorities());

        when(setupConfigService.isAutologinEnabled(anyString())).thenReturn(true);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static List<GrantedAuthority> authorities() {
        return Arrays.asList(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_MYUSERID_123"),
                new SimpleGrantedAuthority("PROPERTY_MANDAT_default"));
    }

    private void gueltigerKey() {
        when(userRepository.findByAutologinKey("validKey123")).thenReturn(testUser);
        when(userDetailsService.loadUserByUsername("test@example.com")).thenReturn(userDetails);
    }

    // ==================== Erfolgsfall (legitimer, produktiv genutzter Flow) ====================

    @Test
    @DisplayName("gueltiger Key -> Vollsession und Redirect auf die Startseite")
    void autoLogin_shouldSucceed_whenValidKeyAndFeatureEnabled() {
        gueltigerKey();

        String result = autoLoginController.autoLogin("validKey123", request, response);

        // Der SuccessHandler schreibt den Redirect selbst -> kein View-Name mehr.
        assertNull(result);
        assertEquals("/index.html", response.getRedirectedUrl());

        verify(userRepository).findByAutologinKey("validKey123");
        verify(userDetailsService).loadUserByUsername("test@example.com");
        verify(aufbau.securityContextRepository()).saveContext(any(SecurityContext.class), any(), any());

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertTrue(SecurityContextHolder.getContext().getAuthentication().isAuthenticated());
    }

    @Test
    void autoLogin_shouldSetCorrectAuthorities() {
        gueltigerKey();

        autoLoginController.autoLogin("validKey123", request, response);

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication);
        assertEquals("test@example.com", ((UserDetails) authentication.getPrincipal()).getUsername());
        assertTrue(authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_USER")));
        assertTrue(authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("PROPERTY_MYUSERID_123")));
    }

    @Test
    @DisplayName("individuelle Startseite wird weiterhin angesteuert (jetzt validiert)")
    void autoLogin_shouldRedirectToStartpage() {
        userDetails = new User("test@example.com", "encodedPassword",
                List.of(new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("PROPERTY_MANDAT_default"),
                        new SimpleGrantedAuthority("PROPERTY_STARTPAGE_dashboard.html")));
        gueltigerKey();

        autoLoginController.autoLogin("validKey123", request, response);

        assertEquals("/dashboard.html", response.getRedirectedUrl());
    }

    // ==================== Karte 309: Session-Fixation ====================

    @Test
    @DisplayName("Session-Id wird beim Autologin erneuert (Session-Fixation-Schutz)")
    void autoLogin_erneuertSessionId() {
        gueltigerKey();
        // Angreifer-Szenario: dem Opfer ist vorab eine bekannte Session untergeschoben worden.
        String vorherigeId = request.getSession(true).getId();

        autoLoginController.autoLogin("validKey123", request, response);

        assertNotEquals(vorherigeId, request.getSession(false).getId(),
                "Die Session-Id muss sich beim Login aendern, sonst ist die untergeschobene Session "
                        + "danach voll authentifiziert");
    }

    // ==================== Karte 309: Lockout ====================

    @Test
    @DisplayName("gesperrter Account (Brute-Force-Lockout) kommt auch per Autologin nicht durch")
    void autoLogin_gesperrterAccount_wirdAbgelehnt() {
        userDetails = new User("test@example.com", "encodedPassword",
                true, true, true, /* accountNonLocked */ false, authorities());
        gueltigerKey();

        String result = autoLoginController.autoLogin("validKey123", request, response);

        assertEquals("redirect:/login.html", result);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(aufbau.securityContextRepository(), never()).saveContext(any(), any(), any());
    }

    // ==================== Karte 309: 2FA ====================

    @Test
    @DisplayName("TOTP-User landet auch per Autologin im zweiten Schritt, nicht in einer Vollsession")
    void autoLogin_totpUser_landetImPendingFlow() {
        gueltigerKey();
        when(aufbau.totpAuthenticationService().isTotpRequired("test@example.com")).thenReturn(true);

        String result = autoLoginController.autoLogin("validKey123", request, response);

        assertNull(result);
        assertEquals("/login/totp", response.getRedirectedUrl());
        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "Ohne zweiten Faktor darf keine Authentication im Context stehen");
        assertNotNull(request.getSession(false).getAttribute(TotpPendingAuthentication.SESSION_ATTRIBUTE));
    }

    // ==================== Feature-Schalter ====================

    @Test
    void autoLogin_shouldRedirectToLogin_whenFeatureDisabled() {
        when(setupConfigService.isAutologinEnabled(anyString())).thenReturn(false);
        gueltigerKey();

        String result = autoLoginController.autoLogin("validKey123", request, response);

        assertEquals("redirect:/login.html", result);
        verify(userRepository).findByAutologinKey("validKey123");
        verify(aufbau.securityContextRepository(), never()).saveContext(any(), any(), any());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    // ==================== Ungueltige Keys ====================

    @Test
    void autoLogin_shouldRedirectToLogin_whenKeyIsNull() {
        String result = autoLoginController.autoLogin(null, request, response);

        assertEquals("redirect:/login.html", result);
        verify(userRepository, never()).findByAutologinKey(any());
        verify(userDetailsService, never()).loadUserByUsername(any());
    }

    @Test
    void autoLogin_shouldRedirectToLogin_whenKeyIsEmpty() {
        String result = autoLoginController.autoLogin("", request, response);

        assertEquals("redirect:/login.html", result);
        verify(userRepository, never()).findByAutologinKey(any());
    }

    @Test
    void autoLogin_shouldRedirectToLogin_whenUserNotFoundForKey() {
        when(userRepository.findByAutologinKey("invalidKey")).thenReturn(null);

        String result = autoLoginController.autoLogin("invalidKey", request, response);

        assertEquals("redirect:/login.html", result);
        verify(userRepository).findByAutologinKey("invalidKey");
        verify(userDetailsService, never()).loadUserByUsername(any());
        verify(aufbau.securityContextRepository(), never()).saveContext(any(), any(), any());
    }

    // ==================== Fehlerbehandlung ====================

    @Test
    void autoLogin_shouldRedirectToLogin_whenUserDetailsServiceThrowsException() {
        when(userRepository.findByAutologinKey("validKey123")).thenReturn(testUser);
        when(userDetailsService.loadUserByUsername("test@example.com"))
                .thenThrow(new UsernameNotFoundException("User not found"));

        String result = autoLoginController.autoLogin("validKey123", request, response);

        assertEquals("redirect:/login.html", result);
        verify(aufbau.securityContextRepository(), never()).saveContext(any(), any(), any());
    }

    @Test
    void autoLogin_shouldRedirectToLogin_whenRepositoryThrowsException() {
        when(userRepository.findByAutologinKey("validKey123")).thenThrow(new RuntimeException("Database error"));

        String result = autoLoginController.autoLogin("validKey123", request, response);

        assertEquals("redirect:/login.html", result);
        verify(userDetailsService, never()).loadUserByUsername(any());
    }

    // ==================== Security-Context ====================

    @Test
    void autoLogin_shouldReplaceExistingSecurityContext() {
        SecurityContext existingContext = SecurityContextHolder.createEmptyContext();
        existingContext.setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "olduser", "oldpass"));
        SecurityContextHolder.setContext(existingContext);
        gueltigerKey();

        autoLoginController.autoLogin("validKey123", request, response);

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication);
        assertEquals("test@example.com", ((UserDetails) authentication.getPrincipal()).getUsername());
    }

    @Test
    void autoLogin_shouldHandleMultipleConsecutiveCalls() {
        gueltigerKey();

        assertNull(autoLoginController.autoLogin("validKey123", request, response));
        assertNull(autoLoginController.autoLogin("validKey123", request, new MockHttpServletResponse()));

        verify(userRepository, times(2)).findByAutologinKey("validKey123");
        verify(userDetailsService, times(2)).loadUserByUsername("test@example.com");
    }

    // ==================== Edge Cases ====================

    @Test
    void autoLogin_shouldHandleWhitespaceKey() {
        when(userRepository.findByAutologinKey("   ")).thenReturn(null);

        String result = autoLoginController.autoLogin("   ", request, response);

        assertEquals("redirect:/login.html", result);
        verify(userRepository).findByAutologinKey("   ");
    }

    @Test
    void autoLogin_shouldHandleVeryLongKey() {
        String longKey = "a".repeat(1000);
        when(userRepository.findByAutologinKey(longKey)).thenReturn(testUser);
        when(userDetailsService.loadUserByUsername("test@example.com")).thenReturn(userDetails);

        assertNull(autoLoginController.autoLogin(longKey, request, response));
        verify(userRepository).findByAutologinKey(longKey);
    }

    @Test
    void autoLogin_shouldHandleSpecialCharactersInKey() {
        String specialKey = "key!@#$%^&*()_+-=[]{}|;':\",./<>?";
        when(userRepository.findByAutologinKey(specialKey)).thenReturn(testUser);
        when(userDetailsService.loadUserByUsername("test@example.com")).thenReturn(userDetails);

        assertNull(autoLoginController.autoLogin(specialKey, request, response));
        verify(userRepository).findByAutologinKey(specialKey);
    }

    @Test
    void autoLogin_setztNoStoreHeader() {
        when(userRepository.findByAutologinKey(anyString())).thenReturn(null);

        autoLoginController.autoLogin("x", request, response);

        assertTrue(String.valueOf(response.getHeader("Cache-Control")).contains("no-store"));
    }
}
