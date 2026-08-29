/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extended tests for SpringSecurityProvider - role checking edge cases.
 */
class SpringSecurityProviderExtendedTest {

    private final SpringSecurityProvider provider = new SpringSecurityProvider();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void hasRole_shouldReturnFalse_whenNoAuthentication() {
        SecurityContextHolder.clearContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        SecurityContextHolder.setContext(context);

        assertFalse(provider.hasRole("ADMIN"));
    }

    @Test
    void hasRole_shouldReturnTrue_withROLEPrefix() {
        setupAuth(List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        assertTrue(provider.hasRole("ROLE_ADMIN"));
    }

    @Test
    void hasRole_shouldReturnTrue_withoutROLEPrefix() {
        setupAuth(List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        assertTrue(provider.hasRole("ADMIN"));
    }

    @Test
    void hasRole_shouldReturnFalse_whenRoleNotPresent() {
        setupAuth(List.of(new SimpleGrantedAuthority("ROLE_USER")));

        assertFalse(provider.hasRole("ADMIN"));
    }

    @Test
    void hasRole_shouldHandleDirectRoleWithoutPrefix() {
        setupAuth(List.of(new SimpleGrantedAuthority("ADMIN")));

        assertTrue(provider.hasRole("ADMIN"));
    }

    @Test
    void isSecurityEnabled_shouldReturnTrue() {
        assertTrue(provider.isSecurityEnabled());
    }

    @Test
    void hasRole_shouldReturnFalse_whenNotAuthenticated() {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("user", "pass");
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);

        assertFalse(provider.hasRole("ADMIN"));
    }

    private void setupAuth(List<SimpleGrantedAuthority> authorities) {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("user", "pass", authorities);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
    }
}
