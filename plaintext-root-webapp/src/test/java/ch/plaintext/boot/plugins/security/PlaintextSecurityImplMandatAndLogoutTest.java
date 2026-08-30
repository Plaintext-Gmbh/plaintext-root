/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.menuesteuerung.persistence.MandateMenuConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Additional tests for PlaintextSecurityImpl covering edge cases in
 * getMandat, setMandat, getId, getUser, getStartpageOrDefault, logout.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlaintextSecurityImplMandatAndLogoutTest {

    @Mock
    private MyUserRepository userRepository;

    @Mock
    private MandateMenuConfigRepository mandateMenuConfigRepository;

    @Mock
    private SecurityContext securityContext;

    @InjectMocks
    private PlaintextSecurityImpl security;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.setContext(securityContext);
        when(mandateMenuConfigRepository.findAll()).thenReturn(Collections.emptyList());
        // Trigger init
        try {
            java.lang.reflect.Method initMethod = PlaintextSecurityImpl.class.getDeclaredMethod("init");
            initMethod.setAccessible(true);
            initMethod.invoke(security);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ==================== setMandat edge cases ====================

    @Test
    void setMandat_shouldHandleInvalidUserId() {
        // User with no MYUSERID role -> getId() returns -1
        List<SimpleGrantedAuthority> authorities = new ArrayList<>(
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        Authentication auth = new UsernamePasswordAuthenticationToken("user", "pass", authorities);
        when(securityContext.getAuthentication()).thenReturn(auth);

        security.setMandat("test");

        // Should update context but not persist (userId <= 0)
        verify(userRepository, never()).findById(anyLong());
        verify(userRepository, never()).save(any());
    }

    @Test
    void setMandat_persistiertSecurityContextInDieSession() {
        // Regression: in Spring Security 6 a context that is only set in the holder is no longer
        // saved automatically -> the tenant switch has to go into the HTTP session explicitly.
        List<SimpleGrantedAuthority> authorities = new ArrayList<>(List.of(
                new SimpleGrantedAuthority("PROPERTY_MYUSERID_1"),
                new SimpleGrantedAuthority("PROPERTY_MANDAT_home")));
        Authentication auth = new UsernamePasswordAuthenticationToken("user", "pass", authorities);
        when(securityContext.getAuthentication()).thenReturn(auth);
        MyUserEntity user = new MyUserEntity();
        user.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        when(request.getSession(true)).thenReturn(session);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            security.setMandat("prod");

            // The switched context has to lie in the session under the Spring Security key.
            verify(session).setAttribute(
                    eq(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY), any());
            // ...and the holder shows the new tenant immediately.
            assertEquals("prod", security.getMandat());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void setMandat_shouldHandleExceptionGracefully() {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>(
                List.of(new SimpleGrantedAuthority("PROPERTY_MYUSERID_1"))
        );
        Authentication auth = new UsernamePasswordAuthenticationToken("user", "pass", authorities);
        when(securityContext.getAuthentication()).thenReturn(auth);
        when(userRepository.findById(1L)).thenThrow(new RuntimeException("DB error"));

        // Should not throw
        assertDoesNotThrow(() -> security.setMandat("test"));
    }

    // ==================== getUser edge cases ====================

    @Test
    void getUser_shouldReturnSystem_whenNotAuthenticated() {
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(false);
        when(securityContext.getAuthentication()).thenReturn(auth);

        assertEquals("SYSTEM", security.getUser());
    }

    @Test
    void getUser_shouldReturnUsername_whenAuthenticated() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "user@example.com", "pass", Collections.emptyList());
        when(securityContext.getAuthentication()).thenReturn(auth);

        assertEquals("user@example.com", security.getUser());
    }

    // ==================== getId edge cases ====================

    @Test
    void getId_shouldReturnMinusOne_whenNoDigitsInRole() {
        List<SimpleGrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority("PROPERTY_MYUSERID_abc")
        );
        Authentication auth = new UsernamePasswordAuthenticationToken("user", "pass", authorities);
        when(securityContext.getAuthentication()).thenReturn(auth);

        // "abc" has no digits -> empty string -> -1
        assertEquals(-1L, security.getId());
    }

    // ==================== getStartpageOrDefault edge cases ====================

    @Test
    void getStartpageOrDefault_shouldReturnDefault_whenAuthoritiesNull() {
        Authentication auth = mock(Authentication.class);
        when(auth.getAuthorities()).thenReturn(null);
        when(securityContext.getAuthentication()).thenReturn(auth);

        assertEquals("/index.html?faces-redirect=true", security.getStartpageOrDefault());
    }

    @Test
    void getStartpageOrDefault_shouldHandleXhtmlExtension() {
        Authentication auth = new UsernamePasswordAuthenticationToken("user", "pass",
                List.of(new SimpleGrantedAuthority("PROPERTY_STARTPAGE_kontakte.xhtml")));
        when(securityContext.getAuthentication()).thenReturn(auth);

        String result = security.getStartpageOrDefault();

        assertEquals("/kontakte.xhtml?faces-redirect=true", result);
    }

    @Test
    void getStartpageOrDefault_shouldAddXhtmlExtension_whenNoExtension() {
        Authentication auth = new UsernamePasswordAuthenticationToken("user", "pass",
                List.of(new SimpleGrantedAuthority("PROPERTY_STARTPAGE_dashboard")));
        when(securityContext.getAuthentication()).thenReturn(auth);

        String result = security.getStartpageOrDefault();

        assertEquals("/dashboard.xhtml?faces-redirect=true", result);
    }

    @Test
    void getStartpageOrDefault_shouldAddLeadingSlash_whenMissing() {
        Authentication auth = new UsernamePasswordAuthenticationToken("user", "pass",
                List.of(new SimpleGrantedAuthority("PROPERTY_STARTPAGE_kontakte.html")));
        when(securityContext.getAuthentication()).thenReturn(auth);

        String result = security.getStartpageOrDefault();

        assertTrue(result.startsWith("/"));
    }

    @Test
    void getStartpageOrDefault_shouldReturnDefault_whenStartpageEmpty() {
        Authentication auth = new UsernamePasswordAuthenticationToken("user", "pass",
                List.of(new SimpleGrantedAuthority("PROPERTY_STARTPAGE_")));
        when(securityContext.getAuthentication()).thenReturn(auth);

        String result = security.getStartpageOrDefault();

        assertEquals("/index.html?faces-redirect=true", result);
    }

    // ==================== logout edge cases ====================

    @Test
    void logout_shouldReturnLoginRedirect_evenOnError() {
        when(securityContext.getAuthentication()).thenThrow(new RuntimeException("error"));

        String result = security.logout();

        assertEquals("/login.html?faces-redirect=true", result);
    }

    // ==================== getMandatForUser edge cases ====================

    @Test
    void getMandatForUser_shouldReturnMandat_whenUserExists() {
        MyUserEntity user = new MyUserEntity();
        user.setId(1L);
        user.setMandat("prod");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertEquals("prod", security.getMandatForUser(1L));
    }

    // ==================== ifGranted edge cases ====================

    @Test
    void ifGranted_shouldHandleRoleWithPrefix() {
        Authentication auth = new UsernamePasswordAuthenticationToken("user", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_admin")));
        when(securityContext.getAuthentication()).thenReturn(auth);

        assertTrue(security.ifGranted("ROLE_admin"));
    }

    @Test
    void ifGranted_shouldHandleRoleWithoutPrefix() {
        Authentication auth = new UsernamePasswordAuthenticationToken("user", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_admin")));
        when(securityContext.getAuthentication()).thenReturn(auth);

        assertTrue(security.ifGranted("admin"));
    }
}
