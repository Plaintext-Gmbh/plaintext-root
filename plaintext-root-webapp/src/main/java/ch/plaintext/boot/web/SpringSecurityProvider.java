/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import ch.plaintext.boot.menu.SecurityProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Spring Security implementation of SecurityProvider for menu role checking
 */
@Component
@Slf4j
public class SpringSecurityProvider implements SecurityProvider {

    @Override
    public boolean hasRole(String role) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            log.debug("No authenticated user found");
            return false;
        }

        // Check for the role with and without ROLE_ prefix
        String roleWithPrefix = role.startsWith("ROLE_") ? role : "ROLE_" + role;
        String roleWithoutPrefix = role.startsWith("ROLE_") ? role.substring(5) : role;

        boolean hasRole = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(authority ->
                authority.equals(roleWithPrefix) ||
                authority.equals(roleWithoutPrefix)
            );

        log.debug("User {} has role {}: {}",
            authentication.getName(), role, hasRole);

        return hasRole;
    }

    @Override
    public boolean isSecurityEnabled() {
        return true;
    }
}
