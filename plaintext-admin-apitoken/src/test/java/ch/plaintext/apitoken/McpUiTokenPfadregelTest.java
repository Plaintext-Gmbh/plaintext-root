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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Card 1492: one rule in root for WHERE a {@code ui:} token counts. Before, the central filter
 * rejected it on all its patterns, so schuetu could not give its tournament SPA token the prefix
 * (it also guards {@code /api/turnier/*}) and blocked it at {@code /mcp} by name instead.
 */
class McpUiTokenPfadregelTest {

    private static final List<String> TURNIER = List.of("/api/turnier/*");

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Runs a token named {@code name} against {@code pfad}; true = let through. */
    private static boolean durchgelassen(List<String> uiMuster, String name, String pfad) throws Exception {
        McpUserRoles rollen = mock(McpUserRoles.class);
        when(rollen.rolesForUser(any())).thenReturn(Set.of("user"));
        McpBearerTokenFilter filter = new McpBearerTokenFilter(
                t -> Optional.of(new McpBearerTokenFilter.ValidatedToken(1L, "m", "u@x.ch", "READ", null, name)),
                rollen, jti -> false);
        filter.setUiTokenUrlPatterns(uiMuster);
        return laeuft(filter, pfad);
    }

    private static boolean laeuft(McpBearerTokenFilter filter, String pfad) throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer t");
        when(request.getServletPath()).thenReturn(pfad);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        boolean[] lief = {false};
        FilterChain chain = (rq, rs) -> lief[0] = true;
        filter.doFilter(request, response, chain);
        if (!lief[0]) {
            verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        }
        return lief[0];
    }

    @Test
    @DisplayName("POSITIV: ui:-Token gilt auf einem freigegebenen Pfad")
    void uiTokenAufFreigegebenemPfad() throws Exception {
        assertTrue(durchgelassen(TURNIER, "ui:schuetu-turnier-ui", "/api/turnier/kommando"));
        assertTrue(durchgelassen(TURNIER, "ui:schuetu-turnier-ui", "/api/turnier"));
    }

    @Test
    @DisplayName("NEGATIV: ui:-Token am /mcp abgewiesen, auch wenn andere Pfade freigegeben sind")
    void uiTokenAmMcp() throws Exception {
        assertFalse(durchgelassen(TURNIER, "ui:schuetu-turnier-ui", "/mcp"));
        assertFalse(durchgelassen(TURNIER, "ui:schuetu-turnier-ui", "/mcp/message"));
    }

    @Test
    @DisplayName("NEGATIV: ui:-Token auf nicht freigegebener API abgewiesen")
    void uiTokenAufFremdemPfad() throws Exception {
        assertFalse(durchgelassen(TURNIER, "ui:schuetu-turnier-ui", "/api/juriwagen/x"));
        assertFalse(durchgelassen(TURNIER, "ui:schuetu-turnier-ui", "/api/turnierX/kommando"));
    }

    @Test
    @DisplayName("Ohne Freigabe gilt ui: nirgends (bisheriges Verhalten)")
    void ohneFreigabeNirgends() throws Exception {
        assertFalse(durchgelassen(List.of(), "ui:schuetu-turnier-ui", "/api/turnier/kommando"));
    }

    @Test
    @DisplayName("GEGENPROBE: ein normales Token gilt weiter auf allen Mustern")
    void normalesTokenUnberuehrt() throws Exception {
        assertTrue(durchgelassen(TURNIER, "mcpZorin01", "/mcp/message"));
        assertTrue(durchgelassen(TURNIER, null, "/api/juriwagen/x"));
    }

    private static FilterRegistrationBean<McpBearerTokenFilter> registrierung(List<String> uiMuster) {
        McpBearerTokenFilterProperties props = new McpBearerTokenFilterProperties();
        props.setValidation(McpBearerTokenFilterProperties.Validation.JWT);
        props.setUrlPatterns(List.of("/mcp/*", "/api/turnier/*"));
        props.setUiTokenUrlPatterns(uiMuster);
        JwtTokenService jwt = mock(JwtTokenService.class);
        when(jwt.validateToken(any())).thenReturn(Optional.of(new JwtTokenService.JwtValidationResult(
                1L, "m", "u@x.ch", "ui:schuetu-turnier-ui", java.time.Instant.now().plusSeconds(60), "READ", null)));
        McpUserRoles rollen = mock(McpUserRoles.class);
        when(rollen.rolesForUser(any())).thenReturn(Set.of("user"));
        return new McpBearerTokenFilterConfig().mcpBearerTokenFilterRegistration(props, jwt,
                mock(IApiTokenService.class), rollen, mock(ObjectProvider.class), mock(ObjectProvider.class));
    }

    @Test
    @DisplayName("Die Property kommt ueber die Registrierung beim Filter an")
    void propertyWirkt() throws Exception {
        McpBearerTokenFilter filter = registrierung(TURNIER).getFilter();
        assertTrue(laeuft(filter, "/api/turnier/kommando"));
        assertFalse(laeuft(filter, "/mcp/message"));
    }

    @Test
    @DisplayName("Ein Muster, das /mcp oeffnet, bricht den Start ab")
    void mcpNieFreigebbar() {
        for (String muster : List.of("/mcp/*", "/mcp", "/*")) {
            assertThrows(IllegalStateException.class, () -> registrierung(List.of(muster)), muster);
        }
    }

    @Test
    void passt() {
        assertTrue(McpBearerTokenFilter.passt("/api/turnier/a/b", "/api/turnier/*"));
        assertFalse(McpBearerTokenFilter.passt("/api/turnierx", "/api/turnier/*"));
        assertTrue(McpBearerTokenFilter.passt("/x", "/x"));
        assertFalse(McpBearerTokenFilter.passt("/x/y", "/x"));
    }
}
