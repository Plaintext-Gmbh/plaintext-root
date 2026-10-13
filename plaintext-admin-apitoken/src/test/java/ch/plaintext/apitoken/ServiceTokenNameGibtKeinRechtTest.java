/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.apitoken;

import ch.plaintext.McpUserRoles;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Card 1534: the token name in {@code service-token-schreib-rollen} is only an upper bound, never a
 * right. A user who issues himself a token under a service name (as schuetu uses for its turnier-ui)
 * keeps READ; only the server-minted token with a WRITE claim writes.
 */
class ServiceTokenNameGibtKeinRechtTest {

    /** Test-only service name, configured like schuetu configures its turnier-ui token. */
    private static final String DIENST = "test-dienst-ui";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static ApiTokenScopeDeckel deckel() {
        ApiTokenScopeDeckel deckel = new ApiTokenScopeDeckel();
        deckel.setServiceTokenSchreibRollen(Map.of(DIENST, List.of("eintragen", "kontrollierer")));
        return deckel;
    }

    /** Runs the real filter (DATABASE strategy) for a token named {@link #DIENST} owned by an "eintragen" user. */
    private static Authentication lauf(String claim) throws Exception {
        IApiTokenService apiTokenService = mock(IApiTokenService.class);
        when(apiTokenService.validateToken("tok")).thenReturn(Optional.of(new IApiTokenService.ApiTokenValidationResult(
                5L, "default", "e@x.ch", DIENST, Instant.now().plusSeconds(60), claim)));
        McpUserRoles roles = mock(McpUserRoles.class);
        when(roles.rolesForUser(any())).thenReturn(Set.of("eintragen", "user"));
        McpBearerTokenFilter filter = McpBearerTokenFilter.withRevocationCheck(apiTokenService, roles);
        filter.setScopeDeckel(deckel());

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer tok");
        AtomicReference<Authentication> waehrendKette = new AtomicReference<>();
        FilterChain chain = (rq, rs) -> waehrendKette.set(SecurityContextHolder.getContext().getAuthentication());
        filter.doFilter(request, mock(HttpServletResponse.class), chain);

        assertNotNull(waehrendKette.get(), "token passes the filter");
        return waehrendKette.get();
    }

    private static boolean hat(Authentication auth, String authority) {
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(authority));
    }

    @Test
    void serverGemintetesServiceToken_schreibt() throws Exception {
        assertTrue(hat(lauf("WRITE"), "SCOPE_WRITE"), "Positivkontrolle: Service-Token mit WRITE-Claim");
    }

    @Test
    void selbstAusgestelltesGleichnamigesToken_bleibtRead() throws Exception {
        assertEquals(List.of("READ"), deckel().waehlbareScopes(Set.of("ROLE_EINTRAGEN", "ROLE_USER")),
                "UI: für eintragen ist nur READ wählbar, die Namens-Ausnahme zählt dort nicht");

        Authentication auth = lauf("READ");
        assertTrue(hat(auth, "SCOPE_READ"));
        assertFalse(hat(auth, "SCOPE_WRITE"), "der Name allein darf kein WRITE geben");
    }

    @Test
    void gleichnamigesTokenOhneClaim_bleibtRead() throws Exception {
        // regenerateToken stellt ohne scope-Claim aus -> fail-closed READ
        assertFalse(hat(lauf(null), "SCOPE_WRITE"));
    }
}
