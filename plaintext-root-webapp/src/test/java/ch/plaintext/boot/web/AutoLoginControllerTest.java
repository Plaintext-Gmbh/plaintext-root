/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.settings.ISetupConfigService;
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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.context.SecurityContextRepository;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Test class for AutoLoginController - security-critical auto-login functionality.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AutoLoginControllerTest {

    @Mock
    private UserDetailsService userDetailsService;

    @Mock
    private SecurityContextRepository securityContextRepository;

    @Mock
    private MyUserRepository userRepository;

    @Mock
    private ISetupConfigService setupConfigService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @InjectMocks
    private AutoLoginController autoLoginController;

    private MyUserEntity testUser;
    private UserDetails userDetails;

    @BeforeEach
    void setUp() {
        // Setup test user
        testUser = new MyUserEntity();
        testUser.setId(123L);
        testUser.setUsername("test@example.com");
        testUser.setPassword("encodedPassword");
        testUser.setAutologinKey("validKey123");

        // Setup user details with mandat authority
        List<GrantedAuthority> authorities = Arrays.asList(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_MYUSERID_123"),
                new SimpleGrantedAuthority("PROPERTY_MANDAT_default")
        );
        userDetails = new User("test@example.com", "encodedPassword", authorities);

        // Default: autologin enabled
        when(setupConfigService.isAutologinEnabled(anyString())).thenReturn(true);

        // Clear security context before each test
        SecurityContextHolder.clearContext();
    }

    // ==================== Successful Auto-Login Tests ====================

    @Test
    void autoLogin_shouldSucceed_whenValidKeyAndFeatureEnabled() {
        // Given: Auto-login is enabled
        when(setupConfigService.isAutologinEnabled(anyString())).thenReturn(true);

        // Mock repository to return test user for auto-login key
        when(userRepository.findByAutologinKey("validKey123")).thenReturn(testUser);

        // Mock repository findAll (for the debug code in controller)
        when(userRepository.findAll()).thenReturn(Collections.singletonList(testUser));

        // Mock userDetailsService
        when(userDetailsService.loadUserByUsername("test@example.com")).thenReturn(userDetails);

        // When
        String result = autoLoginController.autoLogin("validKey123", request, response);

        // Then
        assertEquals("redirect:/index.html", result);

        // Verify user was looked up by key
        verify(userRepository).findByAutologinKey("validKey123");

        // Verify user details were loaded
        verify(userDetailsService).loadUserByUsername("test@example.com");

        // Verify security context was saved
        verify(securityContextRepository).saveContext(any(SecurityContext.class), eq(request), eq(response));

        // Verify authentication was set
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertTrue(SecurityContextHolder.getContext().getAuthentication().isAuthenticated());
    }

    @Test
    void autoLogin_shouldSetCorrectAuthorities() {
        // Given
        when(setupConfigService.isAutologinEnabled(anyString())).thenReturn(true);
        when(userRepository.findByAutologinKey("validKey123")).thenReturn(testUser);
        when(userRepository.findAll()).thenReturn(Collections.singletonList(testUser));
        when(userDetailsService.loadUserByUsername("test@example.com")).thenReturn(userDetails);

        // When
        autoLoginController.autoLogin("validKey123", request, response);

        // Then
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication);
        assertEquals("test@example.com", ((UserDetails) authentication.getPrincipal()).getUsername());

        // Check authorities are correctly set
        var authorities = authentication.getAuthorities();
        assertTrue(authorities.stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_USER")));
        assertTrue(authorities.stream()
                .anyMatch(a -> a.getAuthority().equals("PROPERTY_MYUSERID_123")));
    }

    // ==================== Feature Disabled Tests ====================

    @Test
    void autoLogin_shouldRedirectToLogin_whenFeatureDisabled() {
        // Given: Auto-login is disabled for the user's mandat
        when(setupConfigService.isAutologinEnabled(anyString())).thenReturn(false);
        when(userRepository.findByAutologinKey("validKey123")).thenReturn(testUser);
        when(userDetailsService.loadUserByUsername("test@example.com")).thenReturn(userDetails);

        // When
        String result = autoLoginController.autoLogin("validKey123", request, response);

        // Then
        assertEquals("redirect:/login.html", result);

        // Verify user was looked up but authentication was not completed
        verify(userRepository).findByAutologinKey("validKey123");
        verify(securityContextRepository, never()).saveContext(any(), any(), any());

        // Verify no authentication was set
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    // ==================== Invalid Key Tests ====================

    @Test
    void autoLogin_shouldRedirectToLogin_whenKeyIsNull() {
        // Given
        when(setupConfigService.isAutologinEnabled(anyString())).thenReturn(true);

        // When
        String result = autoLoginController.autoLogin(null, request, response);

        // Then
        assertEquals("redirect:/login.html", result);

        // Verify no user lookup was performed
        verify(userRepository, never()).findByAutologinKey(any());
        verify(userDetailsService, never()).loadUserByUsername(any());
    }

    @Test
    void autoLogin_shouldRedirectToLogin_whenKeyIsEmpty() {
        // Given
        when(setupConfigService.isAutologinEnabled(anyString())).thenReturn(true);

        // When
        String result = autoLoginController.autoLogin("", request, response);

        // Then
        assertEquals("redirect:/login.html", result);

        // Verify no user lookup was performed
        verify(userRepository, never()).findByAutologinKey(any());
    }

    @Test
    void autoLogin_shouldRedirectToLogin_whenUserNotFoundForKey() {
        // Given
        when(setupConfigService.isAutologinEnabled(anyString())).thenReturn(true);
        when(userRepository.findAll()).thenReturn(Collections.singletonList(testUser));
        when(userRepository.findByAutologinKey("invalidKey")).thenReturn(null);

        // When
        String result = autoLoginController.autoLogin("invalidKey", request, response);

        // Then
        assertEquals("redirect:/login.html", result);

        // Verify user was looked up but not found
        verify(userRepository).findByAutologinKey("invalidKey");

        // Verify no authentication was performed
        verify(userDetailsService, never()).loadUserByUsername(any());
        verify(securityContextRepository, never()).saveContext(any(), any(), any());
    }

    // ==================== Error Handling Tests ====================

    @Test
    void autoLogin_shouldRedirectToLogin_whenUserDetailsServiceThrowsException() {
        // Given
        when(setupConfigService.isAutologinEnabled(anyString())).thenReturn(true);
        when(userRepository.findAll()).thenReturn(Collections.singletonList(testUser));
        when(userRepository.findByAutologinKey("validKey123")).thenReturn(testUser);
        when(userDetailsService.loadUserByUsername("test@example.com"))
                .thenThrow(new UsernameNotFoundException("User not found"));

        // When
        String result = autoLoginController.autoLogin("validKey123", request, response);

        // Then
        assertEquals("redirect:/login.html", result);

        // Verify security context was not saved
        verify(securityContextRepository, never()).saveContext(any(), any(), any());
    }

    @Test
    void autoLogin_shouldRedirectToLogin_whenRepositoryThrowsException() {
        // Given
        when(setupConfigService.isAutologinEnabled(anyString())).thenReturn(true);
        when(userRepository.findAll()).thenReturn(Collections.singletonList(testUser));
        when(userRepository.findByAutologinKey("validKey123"))
                .thenThrow(new RuntimeException("Database error"));

        // When
        String result = autoLoginController.autoLogin("validKey123", request, response);

        // Then
        assertEquals("redirect:/login.html", result);

        // Verify no authentication was performed
        verify(userDetailsService, never()).loadUserByUsername(any());
    }

    @Test
    void autoLogin_shouldRedirectToLogin_whenSecurityContextSaveThrowsException() {
        // Given
        when(setupConfigService.isAutologinEnabled(anyString())).thenReturn(true);
        when(userRepository.findAll()).thenReturn(Collections.singletonList(testUser));
        when(userRepository.findByAutologinKey("validKey123")).thenReturn(testUser);
        when(userDetailsService.loadUserByUsername("test@example.com")).thenReturn(userDetails);
        doThrow(new RuntimeException("Session error"))
                .when(securityContextRepository).saveContext(any(), any(), any());

        // When
        String result = autoLoginController.autoLogin("validKey123", request, response);

        // Then
        assertEquals("redirect:/login.html", result);
    }

    // ==================== Security Context Tests ====================

    @Test
    void autoLogin_shouldClearExistingSecurityContext() {
        // Given: Existing authentication in security context
        SecurityContext existingContext = SecurityContextHolder.createEmptyContext();
        existingContext.setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "olduser", "oldpass"
                )
        );
        SecurityContextHolder.setContext(existingContext);

        when(setupConfigService.isAutologinEnabled(anyString())).thenReturn(true);
        when(userRepository.findAll()).thenReturn(Collections.singletonList(testUser));
        when(userRepository.findByAutologinKey("validKey123")).thenReturn(testUser);
        when(userDetailsService.loadUserByUsername("test@example.com")).thenReturn(userDetails);

        // When
        autoLoginController.autoLogin("validKey123", request, response);

        // Then: New authentication should be set
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication);
        assertEquals("test@example.com", ((UserDetails) authentication.getPrincipal()).getUsername());
    }

    @Test
    void autoLogin_shouldCreateNewSecurityContext() {
        // Given
        when(setupConfigService.isAutologinEnabled(anyString())).thenReturn(true);
        when(userRepository.findAll()).thenReturn(Collections.singletonList(testUser));
        when(userRepository.findByAutologinKey("validKey123")).thenReturn(testUser);
        when(userDetailsService.loadUserByUsername("test@example.com")).thenReturn(userDetails);

        // When
        autoLoginController.autoLogin("validKey123", request, response);

        // Then: Verify a new context was created and saved
        verify(securityContextRepository).saveContext(any(SecurityContext.class), eq(request), eq(response));
    }

    // ==================== Integration Scenario Tests ====================

    @Test
    void autoLogin_shouldWorkEndToEnd_withCompleteFlow() {
        // Given: Complete setup
        when(setupConfigService.isAutologinEnabled(anyString())).thenReturn(true);

        MyUserEntity user = new MyUserEntity();
        user.setId(999L);
        user.setUsername("integration@test.com");
        user.setAutologinKey("integrationKey");

        UserDetails integrationUser = new User(
                "integration@test.com",
                "password",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );

        when(userRepository.findAll()).thenReturn(Collections.singletonList(user));
        when(userRepository.findByAutologinKey("integrationKey")).thenReturn(user);
        when(userDetailsService.loadUserByUsername("integration@test.com")).thenReturn(integrationUser);

        // When
        String result = autoLoginController.autoLogin("integrationKey", request, response);

        // Then
        assertEquals("redirect:/index.html", result);

        // Verify complete flow
        verify(userRepository).findByAutologinKey("integrationKey");
        verify(userDetailsService).loadUserByUsername("integration@test.com");
        verify(securityContextRepository).saveContext(any(), eq(request), eq(response));

        // Verify authentication
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertTrue(authentication.isAuthenticated());
        assertEquals("integration@test.com", ((UserDetails) authentication.getPrincipal()).getUsername());
    }

    @Test
    void autoLogin_shouldHandleMultipleConsecutiveCalls() {
        // Given
        when(setupConfigService.isAutologinEnabled(anyString())).thenReturn(true);
        when(userRepository.findAll()).thenReturn(Collections.singletonList(testUser));
        when(userRepository.findByAutologinKey("validKey123")).thenReturn(testUser);
        when(userDetailsService.loadUserByUsername("test@example.com")).thenReturn(userDetails);

        // When: First call
        String result1 = autoLoginController.autoLogin("validKey123", request, response);

        // Then
        assertEquals("redirect:/index.html", result1);

        // When: Second call (simulating repeated auto-login)
        String result2 = autoLoginController.autoLogin("validKey123", request, response);

        // Then
        assertEquals("redirect:/index.html", result2);

        // Verify both calls were processed
        verify(userRepository, times(2)).findByAutologinKey("validKey123");
        verify(userDetailsService, times(2)).loadUserByUsername("test@example.com");
    }

    // ==================== Edge Cases ====================

    @Test
    void autoLogin_shouldHandleWhitespaceKey() {
        // Given
        when(setupConfigService.isAutologinEnabled(anyString())).thenReturn(true);
        when(userRepository.findAll()).thenReturn(Collections.singletonList(testUser));
        when(userRepository.findByAutologinKey("   ")).thenReturn(null);

        // When
        String result = autoLoginController.autoLogin("   ", request, response);

        // Then: Whitespace is not considered empty by isEmpty(), so it proceeds to lookup
        assertEquals("redirect:/login.html", result);
        verify(userRepository).findByAutologinKey("   ");
    }

    @Test
    void autoLogin_shouldHandleVeryLongKey() {
        // Given: Very long key
        String longKey = "a".repeat(1000);
        when(setupConfigService.isAutologinEnabled(anyString())).thenReturn(true);
        when(userRepository.findAll()).thenReturn(Collections.singletonList(testUser));
        when(userRepository.findByAutologinKey(longKey)).thenReturn(testUser);
        when(userDetailsService.loadUserByUsername("test@example.com")).thenReturn(userDetails);

        // When
        String result = autoLoginController.autoLogin(longKey, request, response);

        // Then
        assertEquals("redirect:/index.html", result);
        verify(userRepository).findByAutologinKey(longKey);
    }

    @Test
    void autoLogin_shouldHandleSpecialCharactersInKey() {
        // Given: Key with special characters
        String specialKey = "key!@#$%^&*()_+-=[]{}|;':\",./<>?";
        when(setupConfigService.isAutologinEnabled(anyString())).thenReturn(true);
        when(userRepository.findAll()).thenReturn(Collections.singletonList(testUser));
        when(userRepository.findByAutologinKey(specialKey)).thenReturn(testUser);
        when(userDetailsService.loadUserByUsername("test@example.com")).thenReturn(userDetails);

        // When
        String result = autoLoginController.autoLogin(specialKey, request, response);

        // Then
        assertEquals("redirect:/index.html", result);
        verify(userRepository).findByAutologinKey(specialKey);
    }
}
