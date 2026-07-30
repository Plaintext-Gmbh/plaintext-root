/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.menuesteuerung.model.MandateMenuConfig;
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

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Extended tests for PlaintextSecurityImpl - covering getStartpageOrDefault,
 * getUsersForMandat, logout, and getAllMandate with menu configs.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlaintextSecurityImplExtendedTest {

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

    // ==================== getStartpageOrDefault() Tests ====================

    @Test
    void getStartpageOrDefault_shouldReturnDefault_whenNoAuth() {
        when(securityContext.getAuthentication()).thenReturn(null);

        String result = security.getStartpageOrDefault();

        assertEquals("/index.html?faces-redirect=true", result);
    }

    @Test
    void getStartpageOrDefault_shouldReturnDefault_whenNoStartpage() {
        Authentication auth = new UsernamePasswordAuthenticationToken("user", "pass",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
        when(securityContext.getAuthentication()).thenReturn(auth);

        String result = security.getStartpageOrDefault();

        assertEquals("/index.html?faces-redirect=true", result);
    }

    @Test
    void getStartpageOrDefault_shouldReturnConfiguredStartpage() {
        Authentication auth = new UsernamePasswordAuthenticationToken("user", "pass",
                Arrays.asList(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("PROPERTY_STARTPAGE_dashboard.html")
                ));
        when(securityContext.getAuthentication()).thenReturn(auth);

        String result = security.getStartpageOrDefault();

        assertEquals("/dashboard.html?faces-redirect=true", result);
    }

    @Test
    void getStartpageOrDefault_shouldAddXhtmlExtension_whenMissing() {
        Authentication auth = new UsernamePasswordAuthenticationToken("user", "pass",
                Arrays.asList(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("PROPERTY_STARTPAGE_kontakte")
                ));
        when(securityContext.getAuthentication()).thenReturn(auth);

        String result = security.getStartpageOrDefault();

        assertEquals("/kontakte.xhtml?faces-redirect=true", result);
    }

    @Test
    void getStartpageOrDefault_shouldAddLeadingSlash() {
        Authentication auth = new UsernamePasswordAuthenticationToken("user", "pass",
                Arrays.asList(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("PROPERTY_STARTPAGE_kontakte.html")
                ));
        when(securityContext.getAuthentication()).thenReturn(auth);

        String result = security.getStartpageOrDefault();

        assertTrue(result.startsWith("/"));
    }

    @Test
    void getStartpageOrDefault_shouldKeepExistingSlash() {
        Authentication auth = new UsernamePasswordAuthenticationToken("user", "pass",
                Arrays.asList(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("PROPERTY_STARTPAGE_/kontakte.xhtml")
                ));
        when(securityContext.getAuthentication()).thenReturn(auth);

        String result = security.getStartpageOrDefault();

        assertEquals("/kontakte.xhtml?faces-redirect=true", result);
    }

    // ==================== getUsersForMandat() Tests ====================

    @Test
    void getUsersForMandat_shouldReturnMatchingUsers() {
        MyUserEntity user1 = new MyUserEntity();
        user1.setUsername("user1@test.com");
        user1.setMandat("production");

        MyUserEntity user2 = new MyUserEntity();
        user2.setUsername("user2@test.com");
        user2.setMandat("production");

        MyUserEntity user3 = new MyUserEntity();
        user3.setUsername("user3@test.com");
        user3.setMandat("dev");

        when(userRepository.findAll()).thenReturn(Arrays.asList(user1, user2, user3));

        List<String> result = security.getUsersForMandat("production");

        assertEquals(2, result.size());
        assertTrue(result.contains("user1@test.com"));
        assertTrue(result.contains("user2@test.com"));
    }

    @Test
    void getUsersForMandat_shouldReturnEmpty_whenNoMatch() {
        when(userRepository.findAll()).thenReturn(Collections.emptyList());

        List<String> result = security.getUsersForMandat("nonexistent");

        assertTrue(result.isEmpty());
    }

    @Test
    void getUsersForMandat_shouldReturnEmpty_forNull() {
        List<String> result = security.getUsersForMandat(null);

        assertTrue(result.isEmpty());
    }

    @Test
    void getUsersForMandat_shouldReturnEmpty_forEmpty() {
        List<String> result = security.getUsersForMandat("");

        assertTrue(result.isEmpty());
    }

    @Test
    void getUsersForMandat_shouldReturnEmpty_forWhitespace() {
        List<String> result = security.getUsersForMandat("  ");

        assertTrue(result.isEmpty());
    }

    @Test
    void getUsersForMandat_shouldBeCaseInsensitive() {
        MyUserEntity user = new MyUserEntity();
        user.setUsername("user@test.com");
        user.setMandat("PRODUCTION");

        when(userRepository.findAll()).thenReturn(Collections.singletonList(user));

        List<String> result = security.getUsersForMandat("production");

        assertEquals(1, result.size());
    }

    @Test
    void getUsersForMandat_shouldHandleDatabaseError() {
        when(userRepository.findAll()).thenThrow(new RuntimeException("DB error"));

        List<String> result = security.getUsersForMandat("production");

        assertTrue(result.isEmpty());
    }

    // ==================== logout() Tests ====================

    @Test
    void logout_shouldReturnLoginRedirect() {
        Authentication auth = new UsernamePasswordAuthenticationToken("user", "pass",
                Collections.emptyList());
        when(securityContext.getAuthentication()).thenReturn(auth);

        String result = security.logout();

        assertEquals("/login.html?faces-redirect=true", result);
    }

    // ==================== getAllMandate() with MandateMenuConfig Tests ====================

    @Test
    void getAllMandate_shouldIncludeMandateFromMenuConfigs() {
        MyUserEntity user = new MyUserEntity();
        user.setMandat("dev");
        when(userRepository.findAll()).thenReturn(Collections.singletonList(user));

        MandateMenuConfig config = mock(MandateMenuConfig.class);
        when(config.getMandateName()).thenReturn("production");
        when(mandateMenuConfigRepository.findAll()).thenReturn(Collections.singletonList(config));

        Set<String> result = security.getAllMandate();

        assertTrue(result.contains("dev"));
        assertTrue(result.contains("production"));
    }

    @Test
    void getAllMandate_shouldIgnoreNullMandateInMenuConfigs() {
        when(userRepository.findAll()).thenReturn(Collections.emptyList());

        MandateMenuConfig config = mock(MandateMenuConfig.class);
        when(config.getMandateName()).thenReturn(null);
        when(mandateMenuConfigRepository.findAll()).thenReturn(Collections.singletonList(config));

        Set<String> result = security.getAllMandate();

        assertEquals(1, result.size());
        assertTrue(result.contains("default"));
    }

    // ==================== isImpersonating() and related Tests ====================

    @Test
    void isImpersonating_shouldReturnFalse_whenNoSession() {
        // Without request context, getCurrentSession() returns null
        assertFalse(security.isImpersonating());
    }

    @Test
    void getOriginalUserId_shouldReturnNull_whenNoSession() {
        assertNull(security.getOriginalUserId());
    }

    @Test
    void stopImpersonation_shouldNotThrow_whenNoSession() {
        assertDoesNotThrow(() -> security.stopImpersonation());
    }

    @Test
    void startImpersonation_shouldNotThrow_withNullUserId() {
        assertDoesNotThrow(() -> security.startImpersonation(null));
    }
}
