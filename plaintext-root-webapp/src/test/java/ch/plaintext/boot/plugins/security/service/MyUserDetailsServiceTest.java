/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.service;

import ch.plaintext.boot.plugins.security.lockout.AccountLockoutService;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class for MyUserDetailsService - Spring Security UserDetailsService implementation.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MyUserDetailsServiceTest {

    @Mock
    private MyUserRepository userRepository;

    @Mock
    private AccountLockoutService lockoutService;

    @InjectMocks
    private MyUserDetailsService userDetailsService;

    private MyUserEntity testUser;

    @BeforeEach
    void setUp() {
        testUser = new MyUserEntity();
        testUser.setId(123L);
        testUser.setUsername("test@example.com");
        testUser.setPassword("encodedPassword123");
    }

    // ==================== loadUserByUsername() - Happy Path Tests ====================

    @Test
    void loadUserByUsername_shouldLoadUserWithBasicRoles() {
        // Given: User with basic roles
        testUser.setRoles(new HashSet<>(Arrays.asList("user", "admin")));
        when(userRepository.findByUsername("test@example.com")).thenReturn(testUser);

        // When
        UserDetails userDetails = userDetailsService.loadUserByUsername("test@example.com");

        // Then
        assertNotNull(userDetails);
        assertEquals("test@example.com", userDetails.getUsername());
        assertEquals("encodedPassword123", userDetails.getPassword());

        List<String> authorities = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        assertTrue(authorities.contains("ROLE_USER"));
        assertTrue(authorities.contains("ROLE_ADMIN"));
        assertTrue(authorities.contains("PROPERTY_MYUSERID_123"));
        assertEquals(3, authorities.size()); // ROLE_USER, ROLE_ADMIN, PROPERTY_MYUSERID_123
    }

    @Test
    void loadUserByUsername_shouldAddMandatRoleWithoutRolePrefix() {
        // Given: User with mandat role
        testUser.setRoles(new HashSet<>(Arrays.asList("user", "property_mandat_dev")));
        when(userRepository.findByUsername("test@example.com")).thenReturn(testUser);

        // When
        UserDetails userDetails = userDetailsService.loadUserByUsername("test@example.com");

        // Then
        List<String> authorities = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        assertTrue(authorities.contains("ROLE_USER"));
        assertTrue(authorities.contains("PROPERTY_MANDAT_DEV")); // No ROLE_ prefix for mandat
        assertTrue(authorities.contains("PROPERTY_MYUSERID_123"));
        assertEquals(3, authorities.size());
    }

    @Test
    void loadUserByUsername_shouldAddStartpagePropertyWhenPresent() {
        // Given: User with startpage
        testUser.setRoles(new HashSet<>(Arrays.asList("user")));
        testUser.setStartpage("/dashboard");
        when(userRepository.findByUsername("test@example.com")).thenReturn(testUser);

        // When
        UserDetails userDetails = userDetailsService.loadUserByUsername("test@example.com");

        // Then
        List<String> authorities = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        assertTrue(authorities.contains("ROLE_USER"));
        assertTrue(authorities.contains("PROPERTY_MYUSERID_123"));
        assertTrue(authorities.contains("PROPERTY_STARTPAGE_/dashboard"));
        assertEquals(3, authorities.size());
    }

    @Test
    void loadUserByUsername_shouldNotAddStartpagePropertyWhenNull() {
        // Given: User without startpage
        testUser.setRoles(new HashSet<>(Arrays.asList("user")));
        testUser.setStartpage(null);
        when(userRepository.findByUsername("test@example.com")).thenReturn(testUser);

        // When
        UserDetails userDetails = userDetailsService.loadUserByUsername("test@example.com");

        // Then
        List<String> authorities = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        assertFalse(authorities.stream().anyMatch(a -> a.contains("STARTPAGE")));
        assertEquals(2, authorities.size()); // Only ROLE_USER and PROPERTY_MYUSERID_123
    }

    @Test
    void loadUserByUsername_shouldNotAddStartpagePropertyWhenEmpty() {
        // Given: User with empty startpage
        testUser.setRoles(new HashSet<>(Arrays.asList("user")));
        testUser.setStartpage("");
        when(userRepository.findByUsername("test@example.com")).thenReturn(testUser);

        // When
        UserDetails userDetails = userDetailsService.loadUserByUsername("test@example.com");

        // Then
        List<String> authorities = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        assertFalse(authorities.stream().anyMatch(a -> a.contains("STARTPAGE")));
        assertEquals(2, authorities.size());
    }

    @Test
    void loadUserByUsername_shouldHandleUserWithNoRoles() {
        // Given: User with no roles
        testUser.setRoles(Collections.emptySet());
        when(userRepository.findByUsername("test@example.com")).thenReturn(testUser);

        // When
        UserDetails userDetails = userDetailsService.loadUserByUsername("test@example.com");

        // Then
        List<String> authorities = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        // Should still have the user ID property
        assertTrue(authorities.contains("PROPERTY_MYUSERID_123"));
        assertEquals(1, authorities.size());
    }

    @Test
    void loadUserByUsername_shouldConvertRolesToUppercase() {
        // Given: User with lowercase roles
        testUser.setRoles(new HashSet<>(Arrays.asList("admin", "user", "developer")));
        when(userRepository.findByUsername("test@example.com")).thenReturn(testUser);

        // When
        UserDetails userDetails = userDetailsService.loadUserByUsername("test@example.com");

        // Then
        List<String> authorities = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        assertTrue(authorities.contains("ROLE_ADMIN"));
        assertTrue(authorities.contains("ROLE_USER"));
        assertTrue(authorities.contains("ROLE_DEVELOPER"));
        assertFalse(authorities.contains("ROLE_admin")); // Lowercase should not exist
    }

    // ==================== loadUserByUsername() - Complex Role Tests ====================

    @Test
    void loadUserByUsername_shouldHandleMultipleMandatRoles() {
        // Given: User with multiple mandat roles
        testUser.setRoles(new HashSet<>(Arrays.asList(
                "user",
                "property_mandat_dev",
                "property_mandat_production"
        )));
        when(userRepository.findByUsername("test@example.com")).thenReturn(testUser);

        // When
        UserDetails userDetails = userDetailsService.loadUserByUsername("test@example.com");

        // Then
        List<String> authorities = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        assertTrue(authorities.contains("ROLE_USER"));
        assertTrue(authorities.contains("PROPERTY_MANDAT_DEV"));
        assertTrue(authorities.contains("PROPERTY_MANDAT_PRODUCTION"));
        assertTrue(authorities.contains("PROPERTY_MYUSERID_123"));
    }

    @Test
    void loadUserByUsername_shouldHandleMixedCaseMandat() {
        // Given: User with mixed case mandat
        testUser.setRoles(new HashSet<>(Arrays.asList("PROPERTY_MANDAT_Production")));
        when(userRepository.findByUsername("test@example.com")).thenReturn(testUser);

        // When
        UserDetails userDetails = userDetailsService.loadUserByUsername("test@example.com");

        // Then
        List<String> authorities = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        assertTrue(authorities.contains("PROPERTY_MANDAT_PRODUCTION"));
    }

    @Test
    void loadUserByUsername_shouldHandleMandatSubstring() {
        // Given: Role that contains "mandat" as substring
        testUser.setRoles(new HashSet<>(Arrays.asList("mandatory_user")));
        when(userRepository.findByUsername("test@example.com")).thenReturn(testUser);

        // When
        UserDetails userDetails = userDetailsService.loadUserByUsername("test@example.com");

        // Then
        List<String> authorities = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        // Should be treated as mandat role (contains "mandat")
        assertTrue(authorities.contains("MANDATORY_USER"));
        assertFalse(authorities.contains("ROLE_MANDATORY_USER"));
    }

    @Test
    void loadUserByUsername_shouldHandleComplexUserWithAllProperties() {
        // Given: User with all possible properties
        testUser.setRoles(new HashSet<>(Arrays.asList(
                "admin",
                "user",
                "developer",
                "property_mandat_production"
        )));
        testUser.setStartpage("/admin/dashboard");
        when(userRepository.findByUsername("test@example.com")).thenReturn(testUser);

        // When
        UserDetails userDetails = userDetailsService.loadUserByUsername("test@example.com");

        // Then
        List<String> authorities = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        // Verify all authorities are present
        assertTrue(authorities.contains("ROLE_ADMIN"));
        assertTrue(authorities.contains("ROLE_USER"));
        assertTrue(authorities.contains("ROLE_DEVELOPER"));
        assertTrue(authorities.contains("PROPERTY_MANDAT_PRODUCTION"));
        assertTrue(authorities.contains("PROPERTY_MYUSERID_123"));
        assertTrue(authorities.contains("PROPERTY_STARTPAGE_/admin/dashboard"));
        assertEquals(6, authorities.size());
    }

    // ==================== loadUserByUsername() - Error Cases ====================

    @Test
    void loadUserByUsername_shouldThrowExceptionForPasswordlessUser() {
        // Given: User is marked as passwordless (OIDC-only)
        testUser.setPasswordless(true);
        testUser.setRoles(new HashSet<>(Arrays.asList("user")));
        when(userRepository.findByUsername("test@example.com")).thenReturn(testUser);

        // When & Then
        UsernameNotFoundException exception = assertThrows(
                UsernameNotFoundException.class,
                () -> userDetailsService.loadUserByUsername("test@example.com")
        );

        assertTrue(exception.getMessage().contains("OIDC-only"));
    }

    @Test
    void loadUserByUsername_shouldAllowNonPasswordlessUser() {
        // Given: User is not passwordless
        testUser.setPasswordless(false);
        testUser.setRoles(new HashSet<>(Arrays.asList("user")));
        when(userRepository.findByUsername("test@example.com")).thenReturn(testUser);

        // When
        UserDetails userDetails = userDetailsService.loadUserByUsername("test@example.com");

        // Then
        assertNotNull(userDetails);
        assertEquals("test@example.com", userDetails.getUsername());
    }

    @Test
    void loadUserByUsername_shouldThrowExceptionWhenUserNotFound() {
        // Given: User doesn't exist
        when(userRepository.findByUsername("nonexistent@example.com")).thenReturn(null);

        // When & Then
        UsernameNotFoundException exception = assertThrows(
                UsernameNotFoundException.class,
                () -> userDetailsService.loadUserByUsername("nonexistent@example.com")
        );

        assertEquals("MyUserEntity not found", exception.getMessage());
    }

    @Test
    void loadUserByUsername_shouldThrowExceptionForNullUsername() {
        // Given: Null username lookup returns null
        when(userRepository.findByUsername(null)).thenReturn(null);

        // When & Then
        assertThrows(
                UsernameNotFoundException.class,
                () -> userDetailsService.loadUserByUsername(null)
        );
    }

    @Test
    void loadUserByUsername_shouldThrowExceptionForEmptyUsername() {
        // Given: Empty username lookup returns null
        when(userRepository.findByUsername("")).thenReturn(null);

        // When & Then
        assertThrows(
                UsernameNotFoundException.class,
                () -> userDetailsService.loadUserByUsername("")
        );
    }

    // ==================== Integration Tests ====================

    @Test
    void loadUserByUsername_shouldCallRepositoryOnce() {
        // Given
        testUser.setRoles(new HashSet<>(Arrays.asList("user")));
        when(userRepository.findByUsername("test@example.com")).thenReturn(testUser);

        // When
        userDetailsService.loadUserByUsername("test@example.com");

        // Then
        verify(userRepository, times(1)).findByUsername("test@example.com");
    }

    @Test
    void loadUserByUsername_shouldUseCaseSensitiveUsernameForLookup() {
        // Given: Repository is called with exact case
        testUser.setRoles(new HashSet<>(Arrays.asList("user")));
        when(userRepository.findByUsername("Test@Example.COM")).thenReturn(testUser);

        // When
        UserDetails userDetails = userDetailsService.loadUserByUsername("Test@Example.COM");

        // Then
        verify(userRepository).findByUsername("Test@Example.COM");
        assertEquals("test@example.com", userDetails.getUsername()); // Returns user's actual username
    }

    @Test
    void loadUserByUsername_shouldHandleSpecialCharactersInStartpage() {
        // Given: Startpage with special characters
        testUser.setRoles(new HashSet<>(Arrays.asList("user")));
        testUser.setStartpage("/admin/dashboard?tab=1&section=overview");
        when(userRepository.findByUsername("test@example.com")).thenReturn(testUser);

        // When
        UserDetails userDetails = userDetailsService.loadUserByUsername("test@example.com");

        // Then
        List<String> authorities = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        assertTrue(authorities.contains("PROPERTY_STARTPAGE_/admin/dashboard?tab=1&section=overview"));
    }

    @Test
    void loadUserByUsername_shouldPreserveUserIdInAuthorities() {
        // Given: User with various IDs
        testUser.setId(999999L);
        testUser.setRoles(new HashSet<>(Arrays.asList("user")));
        when(userRepository.findByUsername("test@example.com")).thenReturn(testUser);

        // When
        UserDetails userDetails = userDetailsService.loadUserByUsername("test@example.com");

        // Then
        List<String> authorities = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        assertTrue(authorities.contains("PROPERTY_MYUSERID_999999"));
    }

    @Test
    void loadUserByUsername_shouldHandleRoleWithSpaces() {
        // Given: Role with spaces (edge case)
        testUser.setRoles(new HashSet<>(Arrays.asList("super admin")));
        when(userRepository.findByUsername("test@example.com")).thenReturn(testUser);

        // When
        UserDetails userDetails = userDetailsService.loadUserByUsername("test@example.com");

        // Then
        List<String> authorities = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        assertTrue(authorities.contains("ROLE_SUPER ADMIN")); // Spring Security handles this
    }

    // ==================== mustChangePassword (Karte 306) ====================

    @Test
    void loadUserByUsername_shouldAddMustChangePasswordAuthority_whenFlagSet() {
        testUser.setRoles(new HashSet<>(Arrays.asList("user")));
        testUser.setMustChangePassword(true);
        when(userRepository.findByUsername("test@example.com")).thenReturn(testUser);

        UserDetails userDetails = userDetailsService.loadUserByUsername("test@example.com");

        List<String> authorities = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        assertTrue(authorities.contains(MyUserDetailsService.MUST_CHANGE_PASSWORD_AUTHORITY),
                "Bei mustChangePassword=true muss die Redirect-Authority gesetzt sein");
    }

    @Test
    void loadUserByUsername_shouldNotAddMustChangePasswordAuthority_whenFlagUnset() {
        testUser.setRoles(new HashSet<>(Arrays.asList("user")));
        testUser.setMustChangePassword(false);
        when(userRepository.findByUsername("test@example.com")).thenReturn(testUser);

        UserDetails userDetails = userDetailsService.loadUserByUsername("test@example.com");

        List<String> authorities = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        assertFalse(authorities.contains(MyUserDetailsService.MUST_CHANGE_PASSWORD_AUTHORITY),
                "Ohne Flag darf keine erzwungene Passwortwechsel-Authority entstehen");
    }

    // ==================== Account Lockout Integration ====================

    @Test
    void loadUserByUsername_shouldReturnUnlockedUserWhenLockoutServiceReportsClean() {
        testUser.setRoles(new HashSet<>(Arrays.asList("user")));
        when(userRepository.findByUsername("test@example.com")).thenReturn(testUser);
        when(lockoutService.isLocked("test@example.com")).thenReturn(false);

        UserDetails userDetails = userDetailsService.loadUserByUsername("test@example.com");

        assertTrue(userDetails.isAccountNonLocked());
    }

    @Test
    void loadUserByUsername_shouldReturnLockedUserWhenLockoutServiceReportsLocked() {
        testUser.setRoles(new HashSet<>(Arrays.asList("user")));
        when(userRepository.findByUsername("test@example.com")).thenReturn(testUser);
        when(lockoutService.isLocked("test@example.com")).thenReturn(true);

        UserDetails userDetails = userDetailsService.loadUserByUsername("test@example.com");

        assertFalse(userDetails.isAccountNonLocked(),
                "Spring Security raises LockedException when accountNonLocked is false");
    }
}
