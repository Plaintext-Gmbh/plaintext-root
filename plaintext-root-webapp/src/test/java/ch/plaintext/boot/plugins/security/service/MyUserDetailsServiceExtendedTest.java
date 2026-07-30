/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.service;

import ch.plaintext.boot.plugins.security.lockout.AccountLockoutService;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Extended tests for MyUserDetailsService - user loading with roles,
 * startpage, mandat, and user ID authority.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MyUserDetailsServiceExtendedTest {

    @Mock
    private MyUserRepository userRepository;

    @Mock
    private AccountLockoutService lockoutService;

    @InjectMocks
    private MyUserDetailsService service;

    @Test
    void loadUserByUsername_shouldThrow_whenUserNotFound() {
        when(userRepository.findByUsername("unknown")).thenReturn(null);

        assertThrows(UsernameNotFoundException.class,
                () -> service.loadUserByUsername("unknown"));
    }

    @Test
    void loadUserByUsername_shouldReturnUserDetails_withRoles() {
        MyUserEntity user = new MyUserEntity();
        user.setId(1L);
        user.setUsername("test@test.com");
        user.setPassword("encoded");
        user.addRole("admin");
        user.addRole("user");

        when(userRepository.findByUsername("test@test.com")).thenReturn(user);

        UserDetails details = service.loadUserByUsername("test@test.com");

        assertEquals("test@test.com", details.getUsername());
        assertEquals("encoded", details.getPassword());
        assertTrue(details.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
        assertTrue(details.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_USER")));
    }

    @Test
    void loadUserByUsername_shouldAddUserIdAuthority() {
        MyUserEntity user = new MyUserEntity();
        user.setId(42L);
        user.setUsername("test@test.com");
        user.setPassword("encoded");
        user.addRole("user");

        when(userRepository.findByUsername("test@test.com")).thenReturn(user);

        UserDetails details = service.loadUserByUsername("test@test.com");

        assertTrue(details.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("PROPERTY_MYUSERID_42")));
    }

    @Test
    void loadUserByUsername_shouldAddStartpageAuthority_whenConfigured() {
        MyUserEntity user = new MyUserEntity();
        user.setId(1L);
        user.setUsername("test@test.com");
        user.setPassword("encoded");
        user.addRole("user");
        user.setStartpage("dashboard.html");

        when(userRepository.findByUsername("test@test.com")).thenReturn(user);

        UserDetails details = service.loadUserByUsername("test@test.com");

        assertTrue(details.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("PROPERTY_STARTPAGE_dashboard.html")));
    }

    @Test
    void loadUserByUsername_shouldNotAddStartpage_whenEmpty() {
        MyUserEntity user = new MyUserEntity();
        user.setId(1L);
        user.setUsername("test@test.com");
        user.setPassword("encoded");
        user.addRole("user");
        user.setStartpage("");

        when(userRepository.findByUsername("test@test.com")).thenReturn(user);

        UserDetails details = service.loadUserByUsername("test@test.com");

        assertFalse(details.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().startsWith("PROPERTY_STARTPAGE_")));
    }

    @Test
    void loadUserByUsername_shouldNotAddStartpage_whenNull() {
        MyUserEntity user = new MyUserEntity();
        user.setId(1L);
        user.setUsername("test@test.com");
        user.setPassword("encoded");
        user.addRole("user");
        user.setStartpage(null);

        when(userRepository.findByUsername("test@test.com")).thenReturn(user);

        UserDetails details = service.loadUserByUsername("test@test.com");

        assertFalse(details.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().startsWith("PROPERTY_STARTPAGE_")));
    }

    @Test
    void loadUserByUsername_shouldHandleMandatRole() {
        MyUserEntity user = new MyUserEntity();
        user.setId(1L);
        user.setUsername("test@test.com");
        user.setPassword("encoded");
        user.addRole("PROPERTY_MANDAT_dev");
        user.addRole("user");

        when(userRepository.findByUsername("test@test.com")).thenReturn(user);

        UserDetails details = service.loadUserByUsername("test@test.com");

        // Mandat role should be added without ROLE_ prefix
        assertTrue(details.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("PROPERTY_MANDAT_DEV")));
        // Regular role should have ROLE_ prefix
        assertTrue(details.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_USER")));
    }
}
