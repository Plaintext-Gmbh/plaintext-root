/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.settings.ISetupConfigService;
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
import org.springframework.security.web.context.SecurityContextRepository;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Extended tests for AutoLoginController covering startpage redirect,
 * event publishing, and base URL extraction.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AutoLoginControllerExtendedTest {

    @Mock
    private UserDetailsService userDetailsService;

    @Mock
    private SecurityContextRepository securityContextRepository;

    @Mock
    private MyUserRepository userRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private ISetupConfigService setupConfigService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @InjectMocks
    private AutoLoginController controller;

    @BeforeEach
    void setUp() {
        when(setupConfigService.isAutologinEnabled(anyString())).thenReturn(true);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void autoLogin_shouldRedirectToStartpage_whenConfigured() {
        MyUserEntity user = new MyUserEntity();
        user.setId(1L);
        user.setUsername("user@test.com");
        user.setAutologinKey("key123");
        user.setStartpage("dashboard.html");

        List<GrantedAuthority> authorities = Arrays.asList(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_MYUSERID_1"),
                new SimpleGrantedAuthority("PROPERTY_MANDAT_dev")
        );
        UserDetails userDetails = new User("user@test.com", "pass", authorities);

        when(userRepository.findByAutologinKey("key123")).thenReturn(user);
        when(userDetailsService.loadUserByUsername("user@test.com")).thenReturn(userDetails);

        String result = controller.autoLogin("key123", request, response);

        assertEquals("redirect:/dashboard.html", result);
    }

    @Test
    void autoLogin_shouldRedirectToIndex_whenNoStartpage() {
        MyUserEntity user = new MyUserEntity();
        user.setId(1L);
        user.setUsername("user@test.com");
        user.setAutologinKey("key123");
        user.setStartpage("");

        List<GrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_MYUSERID_1")
        );
        UserDetails userDetails = new User("user@test.com", "pass", authorities);

        when(userRepository.findByAutologinKey("key123")).thenReturn(user);
        when(userDetailsService.loadUserByUsername("user@test.com")).thenReturn(userDetails);

        String result = controller.autoLogin("key123", request, response);

        assertEquals("redirect:/index.html", result);
    }

    @Test
    void autoLogin_shouldPublishLoginEvent() {
        MyUserEntity user = new MyUserEntity();
        user.setId(1L);
        user.setUsername("user@test.com");
        user.setAutologinKey("key123");

        List<GrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_MYUSERID_1"),
                new SimpleGrantedAuthority("PROPERTY_MANDAT_dev")
        );
        UserDetails userDetails = new User("user@test.com", "pass", authorities);

        when(userRepository.findByAutologinKey("key123")).thenReturn(user);
        when(userDetailsService.loadUserByUsername("user@test.com")).thenReturn(userDetails);
        when(request.getScheme()).thenReturn("https");
        when(request.getServerName()).thenReturn("example.com");
        when(request.getServerPort()).thenReturn(443);

        controller.autoLogin("key123", request, response);

        verify(eventPublisher).publishEvent(any());
    }

    @Test
    void autoLogin_shouldHandleEventPublishFailure() {
        MyUserEntity user = new MyUserEntity();
        user.setId(1L);
        user.setUsername("user@test.com");
        user.setAutologinKey("key123");

        List<GrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_MYUSERID_1")
        );
        UserDetails userDetails = new User("user@test.com", "pass", authorities);

        when(userRepository.findByAutologinKey("key123")).thenReturn(user);
        when(userDetailsService.loadUserByUsername("user@test.com")).thenReturn(userDetails);
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("localhost");
        when(request.getServerPort()).thenReturn(8080);
        doThrow(new RuntimeException("event error")).when(eventPublisher).publishEvent(any());

        // Should still succeed despite event publish failure
        String result = controller.autoLogin("key123", request, response);
        assertEquals("redirect:/index.html", result);
    }

    @Test
    void autoLogin_shouldExtractBaseUrl_withForwardedHeaders() {
        MyUserEntity user = new MyUserEntity();
        user.setId(1L);
        user.setUsername("user@test.com");
        user.setAutologinKey("key123");

        List<GrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_MYUSERID_1")
        );
        UserDetails userDetails = new User("user@test.com", "pass", authorities);

        when(userRepository.findByAutologinKey("key123")).thenReturn(user);
        when(userDetailsService.loadUserByUsername("user@test.com")).thenReturn(userDetails);

        // Set up forwarded headers
        when(request.getHeader("X-Forwarded-Proto")).thenReturn("https");
        when(request.getHeader("X-Forwarded-Host")).thenReturn("app.example.com");
        when(request.getHeader("X-Forwarded-Port")).thenReturn("443");
        when(request.getServerPort()).thenReturn(8080);

        controller.autoLogin("key123", request, response);

        verify(eventPublisher).publishEvent(any());
    }

    @Test
    void autoLogin_shouldExtractBaseUrl_withInvalidForwardedPort() {
        MyUserEntity user = new MyUserEntity();
        user.setId(1L);
        user.setUsername("user@test.com");
        user.setAutologinKey("key123");

        List<GrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_MYUSERID_1")
        );
        UserDetails userDetails = new User("user@test.com", "pass", authorities);

        when(userRepository.findByAutologinKey("key123")).thenReturn(user);
        when(userDetailsService.loadUserByUsername("user@test.com")).thenReturn(userDetails);

        when(request.getHeader("X-Forwarded-Port")).thenReturn("not-a-number");
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("localhost");
        when(request.getServerPort()).thenReturn(8080);

        // Should handle gracefully
        String result = controller.autoLogin("key123", request, response);
        assertEquals("redirect:/index.html", result);
    }

    @Test
    void autoLogin_shouldExtractMandat_defaultWhenMissing() {
        MyUserEntity user = new MyUserEntity();
        user.setId(1L);
        user.setUsername("user@test.com");
        user.setAutologinKey("key123");

        // No PROPERTY_MANDAT authority
        List<GrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_MYUSERID_1")
        );
        UserDetails userDetails = new User("user@test.com", "pass", authorities);

        when(userRepository.findByAutologinKey("key123")).thenReturn(user);
        when(userDetailsService.loadUserByUsername("user@test.com")).thenReturn(userDetails);
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("localhost");
        when(request.getServerPort()).thenReturn(80);

        controller.autoLogin("key123", request, response);

        // Should not throw, default mandat used
        verify(eventPublisher).publishEvent(any());
    }

    @Test
    void autoLogin_shouldExtractUserId_negativeOne_whenMissing() {
        MyUserEntity user = new MyUserEntity();
        user.setId(1L);
        user.setUsername("user@test.com");
        user.setAutologinKey("key123");

        // No PROPERTY_MYUSERID authority
        List<GrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority("ROLE_USER")
        );
        UserDetails userDetails = new User("user@test.com", "pass", authorities);

        when(userRepository.findByAutologinKey("key123")).thenReturn(user);
        when(userDetailsService.loadUserByUsername("user@test.com")).thenReturn(userDetails);
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("localhost");
        when(request.getServerPort()).thenReturn(80);

        controller.autoLogin("key123", request, response);

        verify(eventPublisher).publishEvent(any());
    }

    @Test
    void autoLogin_shouldHandleInvalidUserIdInAuthority() {
        MyUserEntity user = new MyUserEntity();
        user.setId(1L);
        user.setUsername("user@test.com");
        user.setAutologinKey("key123");

        List<GrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("PROPERTY_MYUSERID_not_a_number")
        );
        UserDetails userDetails = new User("user@test.com", "pass", authorities);

        when(userRepository.findByAutologinKey("key123")).thenReturn(user);
        when(userDetailsService.loadUserByUsername("user@test.com")).thenReturn(userDetails);
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("localhost");
        when(request.getServerPort()).thenReturn(80);

        String result = controller.autoLogin("key123", request, response);

        assertEquals("redirect:/index.html", result);
    }
}
