/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import ch.plaintext.apitoken.IApiTokenService;
import ch.plaintext.apitoken.IApiTokenService.ApiTokenValidationResult;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test fuer den {@link TokenLoginController} — sicherheitskritischer Session-Bootstrap aus ApiToken-JWT.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenLoginControllerTest {

    @Mock
    private IApiTokenService apiTokenService;
    @Mock
    private UserDetailsService userDetailsService;
    @Mock
    private org.springframework.security.web.context.SecurityContextRepository securityContextRepository;
    @Mock
    private MyUserRepository userRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;

    @InjectMocks
    private TokenLoginController controller;

    private MyUserEntity testUser;
    private UserDetails userDetails;

    @BeforeEach
    void setUp() {
        testUser = new MyUserEntity();
        testUser.setId(123L);
        testUser.setUsername("kiosk@example.com");
        testUser.setPassword("encoded");

        List<GrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_MYUSERID_123"),
                new SimpleGrantedAuthority("PROPERTY_MANDAT_default")
        );
        userDetails = new User("kiosk@example.com", "encoded", authorities);

        when(userRepository.findById(123L)).thenReturn(Optional.of(testUser));
        when(userDetailsService.loadUserByUsername("kiosk@example.com")).thenReturn(userDetails);

        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private ApiTokenValidationResult result(String mandat) {
        return new ApiTokenValidationResult(123L, mandat, "kiosk@example.com", "KIOSK_TOKEN",
                Instant.now().plusSeconds(3600));
    }

    @Test
    void gueltigesToken_bautSessionAuf_undRedirectStartpage() {
        testUser.setStartpage("dashboard.html");
        when(apiTokenService.validateToken("jwt-valid")).thenReturn(Optional.of(result("default")));

        String view = controller.tokenLogin("jwt-valid", request, response);

        assertEquals("redirect:/dashboard.html", view);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("kiosk@example.com", SecurityContextHolder.getContext().getAuthentication().getName());
        verify(securityContextRepository).saveContext(any(), any(), any());
        verify(response).setHeader(org.mockito.ArgumentMatchers.eq("Cache-Control"),
                org.mockito.ArgumentMatchers.contains("no-store"));
    }

    @Test
    void ohneStartpage_redirectIndex() {
        when(apiTokenService.validateToken("jwt-valid")).thenReturn(Optional.of(result("default")));
        String view = controller.tokenLogin("jwt-valid", request, response);
        assertEquals("redirect:/index.html", view);
    }

    @Test
    void ungueltigesToken_keineSession_redirectLogin() {
        when(apiTokenService.validateToken("jwt-bad")).thenReturn(Optional.empty());

        String view = controller.tokenLogin("jwt-bad", request, response);

        assertEquals("redirect:/login.html", view);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(securityContextRepository, never()).saveContext(any(), any(), any());
    }

    @Test
    void fehlendesToken_redirectLogin() {
        String view = controller.tokenLogin(null, request, response);
        assertEquals("redirect:/login.html", view);
        verify(apiTokenService, never()).validateToken(any());
        verify(securityContextRepository, never()).saveContext(any(), any(), any());
    }

    @Test
    void mandatMismatch_keineSession_redirectLogin() {
        // Token lautet auf ein anderes Mandat als der User tatsaechlich hat (default).
        when(apiTokenService.validateToken("jwt-othermandat")).thenReturn(Optional.of(result("fremd")));

        String view = controller.tokenLogin("jwt-othermandat", request, response);

        assertEquals("redirect:/login.html", view);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(securityContextRepository, never()).saveContext(any(), any(), any());
    }

    @Test
    void userNichtGefunden_redirectLogin() {
        when(apiTokenService.validateToken("jwt-valid")).thenReturn(Optional.of(result("default")));
        when(userRepository.findById(123L)).thenReturn(Optional.empty());

        String view = controller.tokenLogin("jwt-valid", request, response);

        assertEquals("redirect:/login.html", view);
        verify(securityContextRepository, never()).saveContext(any(), any(), any());
    }
}
