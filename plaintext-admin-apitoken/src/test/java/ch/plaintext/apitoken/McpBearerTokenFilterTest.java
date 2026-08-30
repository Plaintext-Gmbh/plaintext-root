/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) plaintext.ch, 2026.
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
import org.springframework.security.access.AccessDeniedException;
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
 * Tests for the canonical {@link McpBearerTokenFilter} (centralization of the former
 * app/iot/schuetu copies). The MCP endpoint must NOT be "open by default":
 * <ul>
 *   <li>missing/empty/invalid bearer token → 401, the filter chain is NOT continued;</li>
 *   <li>valid token → an Authentication with the token roles and the real user roles for the
 *       duration of the chain;</li>
 *   <li>SecurityContext hygiene: no in-place mutation of a session context (schuetu
 *       regression) and a clean reset in the {@code finally} (iot N1 regression);</li>
 *   <li>registration: by default it applies ONLY to {@code /mcp/*} — non-MCP paths stay
 *       untouched.</li>
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
        verify(chain, never()).doFilter(any(), any());   // NOT let through
        assertTrue(sink.toString().contains("Unauthorized"), "401-JSON-Body wird geschrieben");
        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "Ohne gültiges Token wird keine Authentication gesetzt");
    }

    // ------------------------------------------------------------------ Blocking

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

    // ------------------------------------------------------------------ Valid token

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
        // Mixture: normal role (→ ROLE_ prefix + uppercase), PROPERTY_ role (stays without a
        // ROLE_ prefix), null and blank (are ignored).
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
        assertTrue(namen.contains("PROPERTY_TOKEN_MANDAT_mandat1"),
                "Karte 670: eindeutige Mandanten-Authority aus dem Token: " + namen);
        assertEquals(7, namen.size(),
                "null/blank ignoriert; erwartet ROLE_USER + 3 PROPERTY-Basis (inkl. TOKEN_MANDAT, Karte 670) "
                        + "+ 1 SCOPE_READ (fehlender Claim = READ, fail-closed seit Karte 312) + 2 gemappte: "
                        + namen);
    }

    /**
     * Card 670: The user roles carry the same tenant in a different spelling
     * ({@code PROPERTY_MANDAT_EXTRA} above, {@code PROPERTY_MANDAT_PLAINTEXT} in PROD). Then two
     * {@code PROPERTY_MANDAT_*} sit in the context and a {@code findFirst()} over them is a
     * coin toss. {@code PROPERTY_TOKEN_MANDAT_*} must therefore occur <b>exactly once</b> —
     * otherwise the fix merely moves the problem elsewhere.
     */
    @Test
    void tokenMandatAuthority_kommtGenauEinmalVor_auchWennRollenDenMandantenWiederholen()
            throws Exception {
        JwtTokenService jwt = jwtValidating("gueltig", 7L, "plaintext", "root@plaintext.ch");
        McpUserRoles roles = mock(McpUserRoles.class);
        when(roles.rolesForUser(7L)).thenReturn(new LinkedHashSet<>(
                Arrays.asList("root", "PROPERTY_MANDAT_plaintext", "PROPERTY_TOKEN_MANDAT_fremd")));

        HttpServletRequest request = requestWithAuth("Bearer gueltig");
        HttpServletResponse response = mock(HttpServletResponse.class);
        AtomicReference<Authentication> waehrendKette = new AtomicReference<>();
        FilterChain chain = (rq, rs) -> waehrendKette.set(SecurityContextHolder.getContext().getAuthentication());

        jwtFilter(jwt, roles).doFilter(request, response, chain);

        List<String> namen = waehrendKette.get().getAuthorities().stream().map(Object::toString).toList();
        long tokenMandate = namen.stream().filter(n -> n.startsWith("PROPERTY_TOKEN_MANDAT_")).count();
        assertEquals(1, tokenMandate,
                "genau eine Token-Mandanten-Authority erwartet, sonst ist die Auswahl wieder mehrdeutig: "
                        + namen);
        assertTrue(namen.contains("PROPERTY_TOKEN_MANDAT_plaintext"), namen.toString());
    }

    // ------------------------------------------------------------------ SecurityContext hygiene

    /**
     * schuetu regression: SPAs poll the token REST API with the session cookie of the logged-in
     * user PLUS a bearer token. The filter must NOT mutate the (session-loaded) SecurityContext
     * in place, otherwise it steals the user's real roles.
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

        // During the request the token authentication applied, not the session roles.
        assertNotNull(waehrendKette.get());
        assertTrue(hasAuthority(waehrendKette.get(), "ROLE_USER"));
        assertFalse(hasAuthority(waehrendKette.get(), "ROLE_ADMIN"));

        // After the request: the original session context is unchanged (no in-place mutation).
        assertSame(userAuth, SecurityContextHolder.getContext().getAuthentication());
        assertTrue(hasAuthority(sessionCtx.getAuthentication(), "ROLE_ADMIN"));
        assertFalse(hasAuthority(sessionCtx.getAuthentication(), "ROLE_USER"));
    }

    /** iot N1 regression: even if the chain throws, the context is reset in the finally. */
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
        assertFalse(hasAuthority(auth, "SCOPE_WRITE"));
        assertFalse(hasAuthority(auth, "SCOPE_EINTRAGEN"));
        assertFalse(hasAuthority(auth, "SCOPE_ADMIN"));
    }

    /**
     * Card 545: {@code WRITE} is the new name of the write permission (decision by Daniel, 05.08.2026).
     */
    @Test
    void scopeWrite_bekommtReadUndWrite_keinAdmin() throws Exception {
        JwtTokenService jwt = jwtValidating("tok", 1L, "default", "u@x.ch", "WRITE", "jti-2a");
        Authentication auth = runAndCaptureAuth(jwtFilter(jwt, mock(McpUserRoles.class)), "Bearer tok");

        assertTrue(hasAuthority(auth, "SCOPE_READ"));
        assertTrue(hasAuthority(auth, "SCOPE_WRITE"));
        assertFalse(hasAuthority(auth, "SCOPE_ADMIN"));
    }

    /**
     * Card 545, transition window: the old name {@code EINTRAGEN} and the new name {@code WRITE}
     * grant <b>the same</b> authorities. Without that equality one half of the code would check
     * against SCOPE_EINTRAGEN and the other against SCOPE_WRITE — a token would only make it
     * halfway, and it is exactly that in-between state the rename is meant to avoid.
     */
    @Test
    void altnameEintragenUndNeunameWrite_vergebenDieselbenAuthorities() throws Exception {
        JwtTokenService alt = jwtValidating("tok", 1L, "default", "u@x.ch", "EINTRAGEN", "jti-2");
        Authentication mitAltname = runAndCaptureAuth(jwtFilter(alt, mock(McpUserRoles.class)), "Bearer tok");
        JwtTokenService neu = jwtValidating("tok", 1L, "default", "u@x.ch", "WRITE", "jti-2b");
        Authentication mitNeuname = runAndCaptureAuth(jwtFilter(neu, mock(McpUserRoles.class)), "Bearer tok");

        for (String erwartet : new String[] {"SCOPE_READ", "SCOPE_WRITE", "SCOPE_EINTRAGEN"}) {
            assertTrue(hasAuthority(mitAltname, erwartet), "EINTRAGEN muss " + erwartet + " vergeben");
            assertTrue(hasAuthority(mitNeuname, erwartet), "WRITE muss " + erwartet + " vergeben");
        }
        assertFalse(hasAuthority(mitAltname, "SCOPE_ADMIN"));
        assertFalse(hasAuthority(mitNeuname, "SCOPE_ADMIN"));
    }

    @Test
    void scopeAdmin_bekommtAlleDrei() throws Exception {
        JwtTokenService jwt = jwtValidating("tok", 1L, "default", "u@x.ch", "ADMIN", "jti-3");
        Authentication auth = runAndCaptureAuth(jwtFilter(jwt, mock(McpUserRoles.class)), "Bearer tok");

        assertTrue(hasAuthority(auth, "SCOPE_READ"));
        assertTrue(hasAuthority(auth, "SCOPE_WRITE"));
        assertTrue(hasAuthority(auth, "SCOPE_EINTRAGEN"));
        assertTrue(hasAuthority(auth, "SCOPE_ADMIN"));
    }

    /**
     * Card 312 (H-7): A missing {@code scope} claim must NO longer mean ADMIN. Since token
     * issuing granted no scope at all until then, in practice every API token used to be a
     * full-access token — a member could create a token in the UI and use it to call destructive
     * tools over MCP.
     */
    @Test
    void fehlenderScopeClaim_giltNurNochAlsRead_failClosed() throws Exception {
        JwtTokenService jwt = jwtValidating("tok", 1L, "default", "u@x.ch", null, null);
        Authentication auth = runAndCaptureAuth(jwtFilter(jwt, mock(McpUserRoles.class)), "Bearer tok");

        assertTrue(hasAuthority(auth, "SCOPE_READ"));
        assertFalse(hasAuthority(auth, "SCOPE_WRITE"), "fehlender Claim darf kein Schreibrecht geben");
        assertFalse(hasAuthority(auth, "SCOPE_EINTRAGEN"), "fehlender Claim darf kein Schreibrecht geben");
        assertFalse(hasAuthority(auth, "SCOPE_ADMIN"), "fehlender Claim darf kein ADMIN geben");
    }

    /**
     * The migration opt-out {@code legacy-scope-admin=true} brings the old behaviour back for a
     * limited time, so that an instance whose tokens have not been renewed yet does not drop to
     * read access all at once.
     */
    @Test
    void fehlenderScopeClaim_mitLegacyFlag_giltWeiterhinAlsAdmin() throws Exception {
        JwtTokenService jwt = jwtValidating("tok", 1L, "default", "u@x.ch", null, null);
        McpBearerTokenFilter filter = jwtFilter(jwt, mock(McpUserRoles.class));
        filter.setLegacyScopeAdmin(true);

        Authentication auth = runAndCaptureAuth(filter, "Bearer tok");

        assertTrue(hasAuthority(auth, "SCOPE_READ"));
        assertTrue(hasAuthority(auth, "SCOPE_WRITE"));
        assertTrue(hasAuthority(auth, "SCOPE_EINTRAGEN"));
        assertTrue(hasAuthority(auth, "SCOPE_ADMIN"));
    }

    @Test
    void unbekannterScopeWert_bekommtNurRead() throws Exception {
        JwtTokenService jwt = jwtValidating("tok", 1L, "default", "u@x.ch", "GARBAGE", "jti-4");
        Authentication auth = runAndCaptureAuth(jwtFilter(jwt, mock(McpUserRoles.class)), "Bearer tok");

        assertTrue(hasAuthority(auth, "SCOPE_READ"));
        assertFalse(hasAuthority(auth, "SCOPE_WRITE"));
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
        // 2-arg factory (no checker) — the default behaviour for app/iot/guild, which do not
        // (yet) provide a blocklist bean.
        JwtTokenService jwt = jwtValidating("tok", 1L, "default", "u@x.ch", "ADMIN", "irgendeine-jti");
        Authentication auth = runAndCaptureAuth(jwtFilter(jwt, mock(McpUserRoles.class)), "Bearer tok");

        assertNotNull(auth);
    }

    // ------------------------------------------------------------------ DATABASE strategy

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

    /**
     * Regression (card 349): The DATABASE strategy must pass the token's {@code scope} through.
     * When it did not, every token arrived at the filter without a claim and the fail-closed default
     * degraded it to READ — switching to {@code validation: DATABASE} would thereby have silently
     * capped all EINTRAGEN flows (Zeiterfassung-Uhr, Juriwagen) to read access.
     */
    @Test
    void databaseStrategie_reichtScopeDurch() throws Exception {
        IApiTokenService apiTokenService = mock(IApiTokenService.class);
        when(apiTokenService.validateToken("dbtok")).thenReturn(Optional.of(
                new IApiTokenService.ApiTokenValidationResult(
                        99L, "app", "u@plaintext.ch", "uhr", Instant.now().plusSeconds(60), "EINTRAGEN")));

        HttpServletRequest request = requestWithAuth("Bearer dbtok");
        HttpServletResponse response = mock(HttpServletResponse.class);
        AtomicReference<Authentication> waehrendKette = new AtomicReference<>();
        FilterChain chain = (rq, rs) -> waehrendKette.set(SecurityContextHolder.getContext().getAuthentication());

        McpBearerTokenFilter.withRevocationCheck(apiTokenService, mock(McpUserRoles.class))
                .doFilter(request, response, chain);

        Authentication auth = waehrendKette.get();
        assertNotNull(auth);
        assertTrue(hasAuthority(auth, "SCOPE_EINTRAGEN"));
        assertTrue(hasAuthority(auth, "SCOPE_READ"));
        assertFalse(hasAuthority(auth, "SCOPE_ADMIN"));
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

    // ------------------------------------------------------------------ Registration / patterns

    /** Non-MCP paths stay untouched: by default the filter is mapped ONLY to /mcp/*. */
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

    /** An empty pattern list must NEVER map to /* (would put the whole app behind bearer auth). */
    @Test
    void registrierung_leerePatterns_fallenAufMcpDefaultZurueck() {
        McpBearerTokenFilterProperties props = new McpBearerTokenFilterProperties();
        props.setUrlPatterns(List.of());

        FilterRegistrationBean<McpBearerTokenFilter> registration = new McpBearerTokenFilterConfig()
                .mcpBearerTokenFilterRegistration(props, mock(JwtTokenService.class),
                        mock(IApiTokenService.class), mock(McpUserRoles.class), mock(ObjectProvider.class));

        assertEquals(List.of("/mcp/*"), List.copyOf(registration.getUrlPatterns()));
    }

    // ------------------------------------------------------------------ Permission missing → 403, not 302 (card 652)

    /**
     * Measured on schuetu INT on 11.08.2026: A valid READ token against
     * {@code @PreAuthorize("hasAuthority('SCOPE_WRITE')")} returned HTTP 302 to {@code /login.html},
     * and with {@code curl -L} that became HTTP 200 and 14 534 bytes of login page — a success as
     * far as the calling script was concerned. Cause: the {@code finally} restores the anonymous
     * context before the {@code ExceptionTranslationFilter} decides, on exactly that
     * Authentication, between 403 and a login redirect.
     */
    private static HttpServletRequest apiRequestWithAuth(String authHeader) {
        HttpServletRequest request = requestWithAuth(authHeader);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/turnier/kommando");
        return request;
    }

    @Test
    void rechtFehlt_wird403MitJson_statt302AufDieAnmeldung() throws Exception {
        // The filter sees the header WITH the prefix; validateToken gets the rest.
        JwtTokenService jwt = jwtValidating("t", 7L, "worb", "u@example.invalid", "READ", null);

        HttpServletRequest request = apiRequestWithAuth("Bearer t");
        StringWriter sink = new StringWriter();
        HttpServletResponse response = responseWithWriter(sink);
        when(response.isCommitted()).thenReturn(false);

        FilterChain chain = mock(FilterChain.class);
        doThrow(new AccessDeniedException("Access Denied")).when(chain).doFilter(any(), any());

        jwtFilter(jwt, mock(McpUserRoles.class)).doFilter(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verify(response).setContentType("application/json");
        assertTrue(sink.toString().contains("Forbidden"),
                "403-JSON-Body statt HTML-Redirect — ein Geraet kann 'Recht fehlt' sonst nicht von "
                        + "'Session abgelaufen' unterscheiden");
        assertFalse(sink.toString().contains("SCOPE_"),
                "Die Fehlerantwort verraet nicht, welcher Scope gefehlt haette");
    }

    /** Even on the rejection path, no token authentication may be left behind on the pooled thread. */
    @Test
    void rechtFehlt_securityContextWirdTrotzdemRestauriert() throws Exception {
        SecurityContext vorher = SecurityContextHolder.getContext();
        JwtTokenService jwt = jwtValidating("t", 7L, "worb", "u@example.invalid", "READ", null);

        HttpServletRequest request = apiRequestWithAuth("Bearer t");
        HttpServletResponse response = responseWithWriter(new StringWriter());
        FilterChain chain = mock(FilterChain.class);
        doThrow(new AccessDeniedException("Access Denied")).when(chain).doFilter(any(), any());

        jwtFilter(jwt, mock(McpUserRoles.class)).doFilter(request, response, chain);

        assertSame(vorher, SecurityContextHolder.getContext(),
                "finally laeuft auch auf dem Ablehnungspfad");
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * If the response is already running (an SSE stream under /mcp, for example), a JSON body
     * pushed in afterwards would destroy the data stream. In that case the filter passes the
     * exception on instead of writing into a response that has already begun.
     */
    @Test
    void antwortBereitsBegonnen_exceptionWirdDurchgereicht() throws Exception {
        JwtTokenService jwt = jwtValidating("t", 7L, "worb", "u@example.invalid", "READ", null);

        HttpServletRequest request = apiRequestWithAuth("Bearer t");
        HttpServletResponse response = responseWithWriter(new StringWriter());
        when(response.isCommitted()).thenReturn(true);

        FilterChain chain = mock(FilterChain.class);
        doThrow(new AccessDeniedException("Access Denied")).when(chain).doFilter(any(), any());

        McpBearerTokenFilter filter = jwtFilter(jwt, mock(McpUserRoles.class));
        assertThrows(AccessDeniedException.class, () -> filter.doFilter(request, response, chain));

        verify(response, never()).setStatus(HttpServletResponse.SC_FORBIDDEN);
        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "finally raeumt auch beim Durchreichen auf");
    }

    // ------------------------------------------------------------------ Card 652: permission missing

    /**
     * Card 652: A permission denial from {@code @PreAuthorize} does NOT reach this filter as an
     * {@link org.springframework.security.access.AccessDeniedException}, but wrapped in a
     * {@link jakarta.servlet.ServletException} — that is how the DispatcherServlet wraps it.
     * The first attempt at this card caught only the unwrapped type and was therefore never
     * executed; the caller still got a 302 to /login.html (measured on schuetu INT, 11.08.2026).
     */
    @Test
    void verpackteAccessDeniedException_wird403Json() throws Exception {
        JwtTokenService jwt = jwtValidating("gueltig", 42L, "default", "mcp@plaintext.ch", "READ", null);
        HttpServletRequest request = requestWithAuth("Bearer gueltig");
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/turnier/kommando");
        StringWriter sink = new StringWriter();
        HttpServletResponse response = responseWithWriter(sink);
        when(response.isCommitted()).thenReturn(false);
        FilterChain chain = (rq, rs) -> {
            throw new jakarta.servlet.ServletException(
                    new org.springframework.security.access.AccessDeniedException("Access Denied"));
        };

        jwtFilter(jwt, mock(McpUserRoles.class)).doFilter(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verify(response).setContentType("application/json");
        assertTrue(sink.toString().contains("Forbidden"),
                "Der Rumpf muss JSON sein, war: " + sink);
    }

    /**
     * Counter-check: Without this distinction the catch would have turned into a silencing filter —
     * an arbitrary server error must not end up at the client as a 403.
     */
    @Test
    void andereVerpackteFehler_werdenDurchgereicht() throws Exception {
        JwtTokenService jwt = jwtValidating("gueltig", 42L, "default", "mcp@plaintext.ch", "READ", null);
        HttpServletRequest request = requestWithAuth("Bearer gueltig");
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = (rq, rs) -> {
            throw new jakarta.servlet.ServletException(new IllegalStateException("Datenbank weg"));
        };

        assertThrows(jakarta.servlet.ServletException.class,
                () -> jwtFilter(jwt, mock(McpUserRoles.class)).doFilter(request, response, chain));
        verify(response, never()).setStatus(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    void findeAccessDenied_findetNichtsInEinerZyklischenKette() {
        RuntimeException selbstbezug = new RuntimeException("zyklisch") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertNull(McpBearerTokenFilter.findeAccessDenied(selbstbezug),
                "Eine sich selbst als Ursache tragende Exception darf nicht zur Endlosschleife fuehren");
    }

}
