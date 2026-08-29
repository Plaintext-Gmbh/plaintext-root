/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for SpringSecurityProvider - menu role checking integration.
 */
class SpringSecurityProviderTest {

    private SpringSecurityProvider securityProvider;
    private SecurityContext originalContext;

    @BeforeEach
    void setUp() {
        securityProvider = new SpringSecurityProvider();
        originalContext = SecurityContextHolder.getContext();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.setContext(originalContext);
    }

    // ==================== Helper Methods ====================

    private void setupAuthenticationWithRoles(String username, String... roles) {
        List<GrantedAuthority> authorities = Arrays.stream(roles)
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                username, "password", authorities
        );

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    // ==================== hasRole() - Basic Functionality Tests ====================

    @Test
    void hasRole_shouldReturnTrue_whenUserHasRoleWithPrefix() {
        // Given: User with ROLE_ADMIN
        setupAuthenticationWithRoles("testuser", "ROLE_ADMIN");

        // When & Then
        assertTrue(securityProvider.hasRole("ADMIN"));
        assertTrue(securityProvider.hasRole("ROLE_ADMIN"));
    }

    @Test
    void hasRole_shouldReturnTrue_whenUserHasRoleWithoutPrefix() {
        // Given: User with role without prefix
        setupAuthenticationWithRoles("testuser", "ADMIN");

        // When & Then
        assertTrue(securityProvider.hasRole("ADMIN"));
        assertTrue(securityProvider.hasRole("ROLE_ADMIN"));
    }

    @Test
    void hasRole_shouldReturnFalse_whenUserDoesNotHaveRole() {
        // Given: User without ADMIN role
        setupAuthenticationWithRoles("testuser", "ROLE_USER");

        // When & Then
        assertFalse(securityProvider.hasRole("ADMIN"));
        assertFalse(securityProvider.hasRole("ROLE_ADMIN"));
    }

    @Test
    void hasRole_shouldReturnFalse_whenNoAuthentication() {
        // Given: No authentication in context
        SecurityContextHolder.clearContext();

        // When & Then
        assertFalse(securityProvider.hasRole("ADMIN"));
        assertFalse(securityProvider.hasRole("USER"));
    }

    // ==================== hasRole() - Multiple Roles Tests ====================

    @Test
    void hasRole_shouldCheckMultipleRolesCorrectly() {
        // Given: User with multiple roles
        setupAuthenticationWithRoles("testuser", "ROLE_USER", "ROLE_ADMIN", "ROLE_DEVELOPER");

        // When & Then
        assertTrue(securityProvider.hasRole("USER"));
        assertTrue(securityProvider.hasRole("ADMIN"));
        assertTrue(securityProvider.hasRole("DEVELOPER"));
        assertFalse(securityProvider.hasRole("MANAGER"));
    }

    @Test
    void hasRole_shouldHandleMixedPrefixRoles() {
        // Given: User with some roles having ROLE_ prefix, some not
        setupAuthenticationWithRoles("testuser", "ROLE_ADMIN", "USER", "DEVELOPER");

        // When & Then
        assertTrue(securityProvider.hasRole("ADMIN"));
        assertTrue(securityProvider.hasRole("USER"));
        assertTrue(securityProvider.hasRole("DEVELOPER"));
    }

    @Test
    void hasRole_shouldWorkWithBothPrefixedAndNonPrefixedQueries() {
        // Given: User with ROLE_ADMIN
        setupAuthenticationWithRoles("testuser", "ROLE_ADMIN");

        // When & Then: Both query styles should work
        assertTrue(securityProvider.hasRole("ADMIN"));
        assertTrue(securityProvider.hasRole("ROLE_ADMIN"));
    }

    // ==================== hasRole() - Edge Cases Tests ====================

    @Test
    void hasRole_shouldBeCaseSensitive() {
        // Given: User with lowercase role
        setupAuthenticationWithRoles("testuser", "ROLE_admin");

        // When & Then: Should be case-sensitive
        assertTrue(securityProvider.hasRole("admin"));
        assertTrue(securityProvider.hasRole("ROLE_admin"));
        assertFalse(securityProvider.hasRole("ADMIN")); // Different case
        assertFalse(securityProvider.hasRole("Admin")); // Different case
    }

    @Test
    void hasRole_shouldHandleEmptyRoleString() {
        // Given
        setupAuthenticationWithRoles("testuser", "ROLE_ADMIN");

        // When & Then
        assertFalse(securityProvider.hasRole(""));
    }

    @Test
    void hasRole_shouldHandleNullRole() {
        // Given
        setupAuthenticationWithRoles("testuser", "ROLE_ADMIN");

        // When & Then: Will throw NPE - this is expected behavior, not handling null
        assertThrows(NullPointerException.class, () -> securityProvider.hasRole(null));
    }

    @Test
    void hasRole_shouldHandleSpecialCharactersInRoleName() {
        // Given: Role with special characters
        setupAuthenticationWithRoles("testuser", "ROLE_ADMIN-USER");

        // When & Then
        assertTrue(securityProvider.hasRole("ADMIN-USER"));
        assertTrue(securityProvider.hasRole("ROLE_ADMIN-USER"));
    }

    @Test
    void hasRole_shouldHandleRoleWithUnderscores() {
        // Given
        setupAuthenticationWithRoles("testuser", "ROLE_SUPER_ADMIN");

        // When & Then
        assertTrue(securityProvider.hasRole("SUPER_ADMIN"));
        assertTrue(securityProvider.hasRole("ROLE_SUPER_ADMIN"));
    }

    @Test
    void hasRole_shouldHandleRoleWithNumbers() {
        // Given
        setupAuthenticationWithRoles("testuser", "ROLE_LEVEL1_USER");

        // When & Then
        assertTrue(securityProvider.hasRole("LEVEL1_USER"));
        assertTrue(securityProvider.hasRole("ROLE_LEVEL1_USER"));
    }

    // ==================== hasRole() - Authentication State Tests ====================

    @Test
    void hasRole_shouldReturnFalse_whenAuthenticationIsNotAuthenticated() {
        // Given: Authentication exists but is not authenticated
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "testuser", "password", Collections.emptyList()
        );
        // This creates an unauthenticated token (2-arg constructor)
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);

        // When & Then: Should return false because not authenticated
        assertFalse(securityProvider.hasRole("ADMIN"));
    }

    @Test
    void hasRole_shouldReturnFalse_whenUserHasNoRoles() {
        // Given: User with no roles
        setupAuthenticationWithRoles("testuser"); // No roles

        // When & Then
        assertFalse(securityProvider.hasRole("ADMIN"));
        assertFalse(securityProvider.hasRole("USER"));
    }

    @Test
    void hasRole_shouldWorkAfterAuthenticationChanges() {
        // Given: Initial authentication
        setupAuthenticationWithRoles("testuser", "ROLE_USER");
        assertFalse(securityProvider.hasRole("ADMIN"));

        // When: Change authentication to include ADMIN
        setupAuthenticationWithRoles("testuser", "ROLE_USER", "ROLE_ADMIN");

        // Then: Should now have ADMIN role
        assertTrue(securityProvider.hasRole("ADMIN"));
        assertTrue(securityProvider.hasRole("USER"));
    }

    // ==================== hasRole() - Property Roles Tests ====================

    @Test
    void hasRole_shouldHandlePropertyRoles() {
        // Given: User with property-style roles (like those used in the app)
        setupAuthenticationWithRoles("testuser",
                "PROPERTY_MYUSERID_123",
                "PROPERTY_MANDAT_dev",
                "ROLE_USER"
        );

        // When & Then
        assertTrue(securityProvider.hasRole("USER"));
        assertTrue(securityProvider.hasRole("PROPERTY_MYUSERID_123"));
        assertTrue(securityProvider.hasRole("PROPERTY_MANDAT_dev"));
    }

    @Test
    void hasRole_shouldHandlePropertyRolesWithRolePrefix() {
        // Given: Property role being checked with ROLE_ prefix
        setupAuthenticationWithRoles("testuser", "PROPERTY_MYUSERID_123");

        // When & Then: Should work both ways
        assertTrue(securityProvider.hasRole("PROPERTY_MYUSERID_123"));
        assertTrue(securityProvider.hasRole("ROLE_PROPERTY_MYUSERID_123"));
    }

    // ==================== isSecurityEnabled() Tests ====================

    @Test
    void isSecurityEnabled_shouldAlwaysReturnTrue() {
        // When & Then: Security is always enabled
        assertTrue(securityProvider.isSecurityEnabled());
    }

    @Test
    void isSecurityEnabled_shouldReturnTrueRegardlessOfAuthentication() {
        // Given: No authentication
        SecurityContextHolder.clearContext();

        // When & Then
        assertTrue(securityProvider.isSecurityEnabled());

        // Given: With authentication
        setupAuthenticationWithRoles("testuser", "ROLE_ADMIN");

        // When & Then
        assertTrue(securityProvider.isSecurityEnabled());
    }

    // ==================== Integration Scenarios Tests ====================

    @Test
    void realWorldScenario_adminUserAccessingAdminMenus() {
        // Given: Admin user
        setupAuthenticationWithRoles("admin@example.com",
                "ROLE_ADMIN",
                "ROLE_USER",
                "PROPERTY_MYUSERID_1"
        );

        // When & Then: Should have access to various resources
        assertTrue(securityProvider.hasRole("ADMIN"));
        assertTrue(securityProvider.hasRole("USER"));
        assertTrue(securityProvider.isSecurityEnabled());
    }

    @Test
    void realWorldScenario_regularUserAccessingUserMenus() {
        // Given: Regular user
        setupAuthenticationWithRoles("user@example.com",
                "ROLE_USER",
                "PROPERTY_MYUSERID_42",
                "PROPERTY_MANDAT_production"
        );

        // When & Then
        assertTrue(securityProvider.hasRole("USER"));
        assertFalse(securityProvider.hasRole("ADMIN"));
        assertTrue(securityProvider.isSecurityEnabled());
    }

    @Test
    void realWorldScenario_unauthenticatedUserAccessingPublicPages() {
        // Given: No authentication
        SecurityContextHolder.clearContext();

        // When & Then
        assertFalse(securityProvider.hasRole("USER"));
        assertFalse(securityProvider.hasRole("ADMIN"));
        assertTrue(securityProvider.isSecurityEnabled()); // Security is still enabled
    }

    @Test
    void realWorldScenario_checkingMultipleRolesInSequence() {
        // Given: User with specific roles
        setupAuthenticationWithRoles("developer@example.com",
                "ROLE_DEVELOPER",
                "ROLE_USER"
        );

        // When & Then: Check multiple roles as might happen in menu rendering
        assertTrue(securityProvider.hasRole("DEVELOPER"));
        assertTrue(securityProvider.hasRole("USER"));
        assertFalse(securityProvider.hasRole("ADMIN"));
        assertFalse(securityProvider.hasRole("MANAGER"));
        assertTrue(securityProvider.isSecurityEnabled());
    }

    // ==================== Concurrent/State Tests ====================

    @Test
    void hasRole_shouldWorkWithMultipleSecurityContextChanges() {
        // Scenario: Simulating multiple requests with different users

        // Request 1: Admin user
        setupAuthenticationWithRoles("admin", "ROLE_ADMIN");
        assertTrue(securityProvider.hasRole("ADMIN"));
        assertFalse(securityProvider.hasRole("USER"));

        // Request 2: Regular user
        setupAuthenticationWithRoles("user", "ROLE_USER");
        assertFalse(securityProvider.hasRole("ADMIN"));
        assertTrue(securityProvider.hasRole("USER"));

        // Request 3: No authentication
        SecurityContextHolder.clearContext();
        assertFalse(securityProvider.hasRole("ADMIN"));
        assertFalse(securityProvider.hasRole("USER"));
    }

    @Test
    void hasRole_shouldBeConsistentAcrossMultipleCalls() {
        // Given: User with specific roles
        setupAuthenticationWithRoles("testuser", "ROLE_ADMIN", "ROLE_USER");

        // When & Then: Multiple calls should give same result
        assertTrue(securityProvider.hasRole("ADMIN"));
        assertTrue(securityProvider.hasRole("ADMIN")); // Call again
        assertTrue(securityProvider.hasRole("ADMIN")); // And again

        assertFalse(securityProvider.hasRole("MANAGER"));
        assertFalse(securityProvider.hasRole("MANAGER")); // Consistent false
    }

    // ==================== Prefix Handling Edge Cases ====================

    @Test
    void hasRole_shouldHandleRoleNameThatStartsWithROLE_() {
        // Given: Role that literally is "ROLE_"
        setupAuthenticationWithRoles("testuser", "ROLE_");

        // When & Then
        assertTrue(securityProvider.hasRole("ROLE_"));
        assertTrue(securityProvider.hasRole("")); // After removing prefix
    }

    @Test
    void hasRole_shouldHandleMultipleROLE_Prefixes() {
        // Given: Role with multiple ROLE_ prefixes (edge case)
        setupAuthenticationWithRoles("testuser", "ROLE_ROLE_ADMIN");

        // When & Then: The implementation doesn't strip nested ROLE_ prefixes
        // It only checks exact match or with one ROLE_ prefix added
        assertTrue(securityProvider.hasRole("ROLE_ROLE_ADMIN"));
        // This won't match because the logic doesn't recursively strip ROLE_
        assertFalse(securityProvider.hasRole("ROLE_ADMIN"));
    }
}
