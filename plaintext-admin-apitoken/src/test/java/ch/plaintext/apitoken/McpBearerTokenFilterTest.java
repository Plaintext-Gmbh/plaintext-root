/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) eMad, 2026.
 */
package ch.plaintext.apitoken;

import ch.plaintext.McpUserRoles;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests für den kanonischen {@link McpBearerTokenFilter} (Zentralisierung der bisherigen
 * app-/iot-/schuetu-Kopien). Der MCP-Endpoint darf NICHT „open by default" sein:
 * <ul>
 *   <li>fehlender/leerer/ungültiger Bearer-Token → 401, Filterkette wird NICHT fortgesetzt;</li>
 *   <li>gültiger Token → Authentication mit Token- und echten User-Rollen während der Kette;</li>
 *   <li>SecurityContext-Hygiene: kein In-place-Mutieren eines Session-Contexts (schuetu-
 *       Regression) und sauberes Zurücksetzen im {@code finally} (iot-N1-Regression);</li>
 *   <li>Registrierung: greift per Default NUR auf {@code /mcp/*} — Nicht-MCP-Pfade bleiben
 *       unangetastet.</li>
 * </ul>
 */
class McpBearerTokenFilterTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ Helpers

    private static McpBearerTokenFilter jwtFilter(JwtTokenService jwt, McpUserRoles roles) {
        return McpBearerTokenFilter.jwtOnly(jwt, roles);
    }

    private static JwtTokenService jwtValidating(String token, long userId, String mandat, String email) {
        return jwtValidating(token, userId, mandat, email, null, null);
    }

    private static JwtTokenService jwtValidating(String token, long userId, String mandat, String email,
                                                   String scope, String jti) {
        JwtTokenService jwt = mock(JwtTokenService.class);
        when(jwt.validateToken(token)).thenReturn(Optional.of(new JwtTokenService.JwtValidationResult(
                userId, mandat, email, "test-token", Instant.now().plusSeconds(60), scope, jti)));
        return jwt;
    }

    private static HttpServletRequest requestWithAuth(String authHeader) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn(authHeader);
        return request;
    }

    private static HttpServletResponse responseWithWriter(StringWriter sink) throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(sink));
        return response;
    }

    private static boolean hasAuthority(Authentication auth, String authority) {
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(authority));
    }

    private void assertBlocked(String authHeader) throws Exception {
        JwtTokenService jwt = mock(JwtTokenService.class);
        when(jwt.validateToken(any())).thenReturn(Optional.empty());

        HttpServletRequest request = requestWithAuth(authHeader);
        StringWriter sink = new StringWriter();
        HttpServletResponse response = responseWithWriter(sink);
        FilterChain chain = mock(FilterChain.class);

        jwtFilter(jwt, mock(McpUserRoles.class)).doFilter(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(response).setContentType("application/json");
        verify(chain, never()).doFilter(any(), any());   // NICHT durchgelassen
        assertTrue(sink.toString().contains("Unauthorized"), "401-JSON-Body wird geschrieben");
        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "Ohne gültiges Token wird keine Authentication gesetzt");
    }

    // ------------------------------------------------------------------ Blocken

    @Test
    void fehlendesToken_wird401_undNichtDurchgelassen() throws Exception {
        assertBlocked(null);
    }

    @Test
    void leererHeader_wird401_undNichtDurchgelassen() throws Exception {
        assertBlocked("");
    }

    @Test
    void headerOhneBearerPrefix_wird401_undNichtDurchgelassen() throws Exception {
        assertBlocked("Basic dXNlcjpwdw==");
    }

    @Test
    void ungueltigesToken_wird401_undNichtDurchgelassen() throws Exception {
        assertBlocked("Bearer falsch");
    }

    // ------------------------------------------------------------------ Gültiger Token

    @Test
    void gueltigesToken_setztAuthentication_undReichtDurch() throws Exception {
        JwtTokenService jwt = jwtValidating("gueltig", 42L, "default", "mcp@plaintext.ch");

        HttpServletRequest request = requestWithAuth("Bearer gueltig");
        HttpServletResponse response = mock(HttpServletResponse.class);
        AtomicReference<Authentication> waehrendKette = new AtomicReference<>();
        FilterChain chain = (rq, rs) -> waehrendKette.set(SecurityContextHolder.getContext().getAuthentication());

        jwtFilter(jwt, mock(McpUserRoles.class)).doFilter(request, response, chain);

        verify(response, never()).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        Authentication auth = waehrendKette.get();
        assertNotNull(auth, "Bei gültigem Token ist während der Filterkette eine Authentication gesetzt");
        assertEquals("mcp@plaintext.ch", auth.getName());
        assertTrue(hasAuthority(auth, "ROLE_USER"));
        assertTrue(hasAuthority(auth, "PROPERTY_MYUSERID_42"));
        assertTrue(hasAuthority(auth, "PROPERTY_MANDAT_default"));
        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "Nach chain.doFilter wird der SecurityContext im finally zurückgesetzt");
    }

    @Test
    void echteUserRollen_werdenAlsAuthoritiesGemappt_nullBlankIgnoriert() throws Exception {
        JwtTokenService jwt = jwtValidating("gueltig", 7L, "mandat1", "root@plaintext.ch");
        // Mischung: normale Rolle (→ ROLE_-Präfix + uppercase), PROPERTY_-Rolle (bleibt ohne
        // ROLE_-Präfix), null und blank (werden ignoriert).
        McpUserRoles roles = mock(McpUserRoles.class);
        when(roles.rolesForUser(7L)).thenReturn(new LinkedHashSet<>(
                Arrays.asList("root", "PROPERTY_MANDAT_extra", null, "  ")));

        HttpServletRequest request = requestWithAuth("Bearer gueltig");
        HttpServletResponse response = mock(HttpServletResponse.class);
        AtomicReference<Authentication> waehrendKette = new AtomicReference<>();
        FilterChain chain = (rq, rs) -> waehrendKette.set(SecurityContextHolder.getContext().getAuthentication());

        jwtFilter(jwt, roles).doFilter(request, response, chain);

        Authentication auth = waehrendKette.get();
        assertNotNull(auth);
        List<String> namen = auth.getAuthorities().stream().map(Object::toString).toList();
        assertTrue(namen.contains("ROLE_ROOT"), "normale Rolle bekommt ROLE_-Präfix und uppercase: " + namen);
        assertTrue(namen.contains("PROPERTY_MANDAT_EXTRA"), "PROPERTY_-Rollen bleiben ohne ROLE_-Präfix: " + namen);
        assertTrue(namen.contains("PROPERTY_MYUSERID_7"));
        assertTrue(namen.contains("PROPERTY_MANDAT_mandat1"), "Basis-Mandat-Authority behält Original-Case: " + namen);
        assertEquals(6, namen.size(),
                "null/blank ignoriert; erwartet ROLE_USER + 2 PROPERTY-Basis + 1 SCOPE_READ (fehlender Claim "
                        + "= READ, fail-closed seit Karte 312) + 2 gemappte: " + namen);
    }

    // ------------------------------------------------------------------ SecurityContext-Hygiene

    /**
     * schuetu-Regression: SPAs pollen die Token-REST-API mit Session-Cookie des eingeloggten
     * Users PLUS Bearer-Token. Der Filter darf den (aus der Session geladenen) SecurityContext
     * NICHT in-place mutieren, sonst klaut er dem User seine echten Rollen.
     */
    @Test
    void bestehenderSessionContext_wirdNichtMutiert_undDanachRestauriert() throws Exception {
        JwtTokenService jwt = jwtValidating("tok", 11L, "default", "token@plaintext.ch");

        SecurityContext sessionCtx = SecurityContextHolder.createEmptyContext();
        Authentication userAuth = new UsernamePasswordAuthenticationToken(
                "user@plaintext.ch", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        sessionCtx.setAuthentication(userAuth);
        SecurityContextHolder.setContext(sessionCtx);

        HttpServletRequest request = requestWithAuth("Bearer tok");
        HttpServletResponse response = mock(HttpServletResponse.class);
        AtomicReference<Authentication> waehrendKette = new AtomicReference<>();
        FilterChain chain = (rq, rs) -> waehrendKette.set(SecurityContextHolder.getContext().getAuthentication());

        jwtFilter(jwt, mock(McpUserRoles.class)).doFilter(request, response, chain);

        // Während des Requests galt die Token-Authentication, nicht die Session-Rollen.
        assertNotNull(waehrendKette.get());
        assertTrue(hasAuthority(waehrendKette.get(), "ROLE_USER"));
        assertFalse(hasAuthority(waehrendKette.get(), "ROLE_ADMIN"));

        // Nach dem Request: ursprünglicher Session-Context unverändert (keine In-place-Mutation).
        assertSame(userAuth, SecurityContextHolder.getContext().getAuthentication());
        assertTrue(hasAuthority(sessionCtx.getAuthentication(), "ROLE_ADMIN"));
        assertFalse(hasAuthority(sessionCtx.getAuthentication(), "ROLE_USER"));
    }

    /** iot-N1-Regression: auch wenn die Kette wirft, wird der Context im finally zurückgesetzt. */
    @Test
    void securityContext_wirdAuchBeiFehlerInDerKetteImFinallyZurueckgesetzt() throws Exception {
        JwtTokenService jwt = jwtValidating("tok", 1L, "default", "token@plaintext.ch");

        HttpServletRequest request = requestWithAuth("Bearer tok");
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        doThrow(new ServletException("boom")).when(chain).doFilter(request, response);

        McpBearerTokenFilter filter = jwtFilter(jwt, mock(McpUserRoles.class));

        assertThrows(ServletException.class, () -> filter.doFilter(request, response, chain));
        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "Auch wenn die Kette wirft, wird der SecurityContext im finally zurückgesetzt");
    }

    // ------------------------------------------------------------------ Scope-Authorities (Task 006)

    private Authentication runAndCaptureAuth(McpBearerTokenFilter filter, String bearer) throws Exception {
        HttpServletRequest request = requestWithAuth(bearer);
        HttpServletResponse response = mock(HttpServletResponse.class);
        AtomicReference<Authentication> waehrendKette = new AtomicReference<>();
        FilterChain chain = (rq, rs) -> waehrendKette.set(SecurityContextHolder.getContext().getAuthentication());
        filter.doFilter(request, response, chain);
        return waehrendKette.get();
    }

    @Test
    void scopeRead_bekommtNurScopeRead() throws Exception {
        JwtTokenService jwt = jwtValidating("tok", 1L, "default", "u@x.ch", "READ", "jti-1");
        Authentication auth = runAndCaptureAuth(jwtFilter(jwt, mock(McpUserRoles.class)), "Bearer tok");

        assertTrue(hasAuthority(auth, "SCOPE_READ"));
        assertFalse(hasAuthority(auth, "SCOPE_EINTRAGEN"));
        assertFalse(hasAuthority(auth, "SCOPE_ADMIN"));
    }

    @Test
    void scopeEintragen_bekommtReadUndEintragen_keinAdmin() throws Exception {
        JwtTokenService jwt = jwtValidating("tok", 1L, "default", "u@x.ch", "EINTRAGEN", "jti-2");
        Authentication auth = runAndCaptureAuth(jwtFilter(jwt, mock(McpUserRoles.class)), "Bearer tok");

        assertTrue(hasAuthority(auth, "SCOPE_READ"));
        assertTrue(hasAuthority(auth, "SCOPE_EINTRAGEN"));
        assertFalse(hasAuthority(auth, "SCOPE_ADMIN"));
    }

    @Test
    void scopeAdmin_bekommtAlleDrei() throws Exception {
        JwtTokenService jwt = jwtValidating("tok", 1L, "default", "u@x.ch", "ADMIN", "jti-3");
        Authentication auth = runAndCaptureAuth(jwtFilter(jwt, mock(McpUserRoles.class)), "Bearer tok");

        assertTrue(hasAuthority(auth, "SCOPE_READ"));
        assertTrue(hasAuthority(auth, "SCOPE_EINTRAGEN"));
        assertTrue(hasAuthority(auth, "SCOPE_ADMIN"));
    }

    /**
     * Karte 312 (H-7): Ein fehlender {@code scope}-Claim darf NICHT mehr ADMIN bedeuten. Da die
     * Token-Ausstellung bis dahin gar keinen Scope vergab, war zuvor faktisch jeder API-Token ein
     * Vollzugriffs-Token — ein Mitglied konnte sich in der UI ein Token erzeugen und damit über MCP
     * destruktive Tools aufrufen.
     */
    @Test
    void fehlenderScopeClaim_giltNurNochAlsRead_failClosed() throws Exception {
        JwtTokenService jwt = jwtValidating("tok", 1L, "default", "u@x.ch", null, null);
        Authentication auth = runAndCaptureAuth(jwtFilter(jwt, mock(McpUserRoles.class)), "Bearer tok");

        assertTrue(hasAuthority(auth, "SCOPE_READ"));
        assertFalse(hasAuthority(auth, "SCOPE_EINTRAGEN"), "fehlender Claim darf kein Schreibrecht geben");
        assertFalse(hasAuthority(auth, "SCOPE_ADMIN"), "fehlender Claim darf kein ADMIN geben");
    }

    /**
     * Der Migrations-Opt-out {@code legacy-scope-admin=true} holt das Alt-Verhalten befristet zurück,
     * damit eine Instanz mit noch nicht erneuerten Tokens nicht schlagartig auf Lesezugriff fällt.
     */
    @Test
    void fehlenderScopeClaim_mitLegacyFlag_giltWeiterhinAlsAdmin() throws Exception {
        JwtTokenService jwt = jwtValidating("tok", 1L, "default", "u@x.ch", null, null);
        McpBearerTokenFilter filter = jwtFilter(jwt, mock(McpUserRoles.class));
        filter.setLegacyScopeAdmin(true);

        Authentication auth = runAndCaptureAuth(filter, "Bearer tok");

        assertTrue(hasAuthority(auth, "SCOPE_READ"));
        assertTrue(hasAuthority(auth, "SCOPE_EINTRAGEN"));
        assertTrue(hasAuthority(auth, "SCOPE_ADMIN"));
    }

    @Test
    void unbekannterScopeWert_bekommtNurRead() throws Exception {
        JwtTokenService jwt = jwtValidating("tok", 1L, "default", "u@x.ch", "GARBAGE", "jti-4");
        Authentication auth = runAndCaptureAuth(jwtFilter(jwt, mock(McpUserRoles.class)), "Bearer tok");

        assertTrue(hasAuthority(auth, "SCOPE_READ"));
        assertFalse(hasAuthority(auth, "SCOPE_EINTRAGEN"));
        assertFalse(hasAuthority(auth, "SCOPE_ADMIN"));
    }

    // ------------------------------------------------------------------ Revocation (Task 006)

    @Test
    void gesperrteJti_wird401_ohneRevocationCheckerFunktioniertAltesToken() throws Exception {
        JwtTokenService jwt = jwtValidating("tok", 1L, "default", "u@x.ch", "ADMIN", "gesperrt-jti");
        StringWriter sink = new StringWriter();
        HttpServletResponse response = responseWithWriter(sink);
        FilterChain chain = mock(FilterChain.class);

        McpBearerTokenFilter filter = McpBearerTokenFilter.jwtOnly(jwt, mock(McpUserRoles.class),
                jti -> jti.equals("gesperrt-jti"));

        filter.doFilter(requestWithAuth("Bearer tok"), response, chain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void nichtGesperrteJti_wirdDurchgelassen() throws Exception {
        JwtTokenService jwt = jwtValidating("tok", 1L, "default", "u@x.ch", "ADMIN", "andere-jti");
        McpBearerTokenFilter filter = McpBearerTokenFilter.jwtOnly(jwt, mock(McpUserRoles.class),
                jti -> jti.equals("gesperrt-jti"));

        Authentication auth = runAndCaptureAuth(filter, "Bearer tok");

        assertNotNull(auth, "Token mit anderer jti als der gesperrten wird durchgelassen");
    }

    @Test
    void ohneRevocationChecker_wirdKeinTokenAlsRevokedBehandelt() throws Exception {
        // 2-Arg-Factory (kein Checker) — Default-Verhalten für app/iot/guild, die (noch) keine
        // Blocklist-Bean bereitstellen.
        JwtTokenService jwt = jwtValidating("tok", 1L, "default", "u@x.ch", "ADMIN", "irgendeine-jti");
        Authentication auth = runAndCaptureAuth(jwtFilter(jwt, mock(McpUserRoles.class)), "Bearer tok");

        assertNotNull(auth);
    }

    // ------------------------------------------------------------------ DATABASE-Strategie

    @Test
    void databaseStrategie_nutztApiTokenServiceInklusiveRevocationCheck() throws Exception {
        IApiTokenService apiTokenService = mock(IApiTokenService.class);
        when(apiTokenService.validateToken("dbtok")).thenReturn(Optional.of(
                new IApiTokenService.ApiTokenValidationResult(
                        99L, "iot", "iot@plaintext.ch", "shelly", Instant.now().plusSeconds(60))));

        HttpServletRequest request = requestWithAuth("Bearer dbtok");
        HttpServletResponse response = mock(HttpServletResponse.class);
        AtomicReference<Authentication> waehrendKette = new AtomicReference<>();
        FilterChain chain = (rq, rs) -> waehrendKette.set(SecurityContextHolder.getContext().getAuthentication());

        McpBearerTokenFilter.withRevocationCheck(apiTokenService, mock(McpUserRoles.class))
                .doFilter(request, response, chain);

        Authentication auth = waehrendKette.get();
        assertNotNull(auth);
        assertEquals("iot@plaintext.ch", auth.getName());
        assertTrue(hasAuthority(auth, "PROPERTY_MYUSERID_99"));
        assertTrue(hasAuthority(auth, "PROPERTY_MANDAT_iot"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void databaseStrategie_revoked_wird401() throws Exception {
        IApiTokenService apiTokenService = mock(IApiTokenService.class);
        when(apiTokenService.validateToken("revoked")).thenReturn(Optional.empty());

        HttpServletRequest request = requestWithAuth("Bearer revoked");
        StringWriter sink = new StringWriter();
        HttpServletResponse response = responseWithWriter(sink);
        FilterChain chain = mock(FilterChain.class);

        McpBearerTokenFilter.withRevocationCheck(apiTokenService, mock(McpUserRoles.class))
                .doFilter(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(any(), any());
    }

    // ------------------------------------------------------------------ Registrierung / Patterns

    /** Nicht-MCP-Pfade bleiben unangetastet: der Filter wird per Default NUR auf /mcp/* gemappt. */
    @Test
    void registrierung_defaultNurMcpPattern_undOrder1() {
        McpBearerTokenFilterProperties props = new McpBearerTokenFilterProperties();

        FilterRegistrationBean<McpBearerTokenFilter> registration = new McpBearerTokenFilterConfig()
                .mcpBearerTokenFilterRegistration(props, mock(JwtTokenService.class),
                        mock(IApiTokenService.class), mock(McpUserRoles.class), mock(ObjectProvider.class));

        assertEquals(List.of("/mcp/*"), List.copyOf(registration.getUrlPatterns()),
                "Default-Registrierung greift NUR auf /mcp/* — andere Pfade passieren unangetastet");
        assertEquals(1, registration.getOrder());
        assertNotNull(registration.getFilter());
    }

    @Test
    void registrierung_zusaetzlichePatterns_wieSchuetuTurnierApi() {
        McpBearerTokenFilterProperties props = new McpBearerTokenFilterProperties();
        props.setUrlPatterns(List.of("/mcp/*", "/api/turnier/*"));
        props.setOrder(1);

        FilterRegistrationBean<McpBearerTokenFilter> registration = new McpBearerTokenFilterConfig()
                .mcpBearerTokenFilterRegistration(props, mock(JwtTokenService.class),
                        mock(IApiTokenService.class), mock(McpUserRoles.class), mock(ObjectProvider.class));

        assertEquals(List.of("/mcp/*", "/api/turnier/*"), List.copyOf(registration.getUrlPatterns()));
    }

    /** Leere Pattern-Liste darf NIE auf /* mappen (würde die ganze App hinter Bearer-Auth legen). */
    @Test
    void registrierung_leerePatterns_fallenAufMcpDefaultZurueck() {
        McpBearerTokenFilterProperties props = new McpBearerTokenFilterProperties();
        props.setUrlPatterns(List.of());

        FilterRegistrationBean<McpBearerTokenFilter> registration = new McpBearerTokenFilterConfig()
                .mcpBearerTokenFilterRegistration(props, mock(JwtTokenService.class),
                        mock(IApiTokenService.class), mock(McpUserRoles.class), mock(ObjectProvider.class));

        assertEquals(List.of("/mcp/*"), List.copyOf(registration.getUrlPatterns()));
    }
}
