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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cards 1363/1365 (audit part D, HD7): the scope of a token is capped by the roles of its owner —
 * when issuing in the UI and on every request in the {@link McpBearerTokenFilter}.
 *
 * <p>Each negative case ("capped to READ") has a positive control next to it ("an admin keeps
 * WRITE/ADMIN", "the service token keeps WRITE"), so that a filter that simply grants nothing any
 * more cannot pass.</p>
 */
class ApiTokenScopeDeckelTest {

    @AfterEach
    void aufraeumen() {
        SecurityContextHolder.clearContext();
        new ch.plaintext.boot.plugins.security.PlaintextSecurityHolder().setDelegate(null);
    }

    // ------------------------------------------------------------------ rules

    @Nested
    class Regeln {

        private final ApiTokenScopeDeckel deckel = new ApiTokenScopeDeckel();

        @Test
        void nichtAdmin_hoechstensRead() {
            assertEquals(Optional.of("READ"), deckel.hoechsterScope(Set.of("user", "auszahlung"), "x"));
            assertEquals(Optional.of("READ"), deckel.deckeln("ADMIN", Set.of("user", "finanzen"), "x"));
            assertEquals(Optional.of("READ"), deckel.deckeln("WRITE", Set.of("eintragen"), "x"));
            assertEquals(Optional.of("READ"), deckel.deckeln("EINTRAGEN", Set.of(), "x"));
        }

        @Test
        void adminUndRoot_behaltenDenClaim_positivkontrolle() {
            assertEquals(Optional.of("ADMIN"), deckel.deckeln("ADMIN", Set.of("admin"), "x"));
            assertEquals(Optional.of("ADMIN"), deckel.deckeln("ADMIN", Set.of("root"), "x"));
            assertEquals(Optional.of("WRITE"), deckel.deckeln("WRITE", Set.of("admin"), "x"));
            assertEquals(Optional.of("WRITE"), deckel.deckeln("EINTRAGEN", Set.of("admin"), "x"),
                    "EINTRAGEN ist der Altname von WRITE (Karte 545)");
            assertEquals(Optional.of("READ"), deckel.deckeln("READ", Set.of("admin"), "x"),
                    "der Deckel hebt nie an: READ bleibt READ, auch für Admins");
        }

        /**
         * root grants {@code "ROLE_" + role.toUpperCase()}; the roles column holds lower case. Both
         * spellings, and the prefix, must count as the same role — {@code hasAnyRole('admin')} in
         * schuetu never matched because of exactly that difference.
         */
        @Test
        void rollennamen_ohneRuecksichtAufGrossKleinUndPraefix() {
            assertEquals(Optional.of("ADMIN"), deckel.deckeln("ADMIN", Set.of("ROLE_ADMIN"), "x"));
            assertEquals(Optional.of("ADMIN"), deckel.deckeln("ADMIN", Set.of("Admin"), "x"));
            assertEquals(Optional.of("READ"), deckel.deckeln("ADMIN", Set.of("PROPERTY_ADMIN"), "x"),
                    "PROPERTY_* ist keine Rolle");
        }

        @Test
        void unbekannterOderFehlenderClaim_giltAlsRead() {
            assertEquals(Optional.of("READ"), deckel.deckeln("GARBAGE", Set.of("admin"), "x"));
            assertEquals(Optional.of("READ"), deckel.deckeln(null, Set.of("admin"), "x"));
            assertEquals(Optional.of("READ"), deckel.deckeln("SESSION", Set.of("admin"), "x"));
        }

        @Test
        void serviceToken_behaeltWrite_nurMitRolleUndNamen_nieAdmin() {
            deckel.setServiceTokenSchreibRollen(Map.of("schuetu-turnier-ui", List.of("eintragen", "kontrollierer")));

            assertEquals(Optional.of("WRITE"), deckel.deckeln("WRITE", Set.of("eintragen"), "schuetu-turnier-ui"),
                    "Positivkontrolle: der serverseitig gemintete Token behält WRITE");
            assertEquals(Optional.of("WRITE"), deckel.deckeln("ADMIN", Set.of("kontrollierer"), "schuetu-turnier-ui"),
                    "nie ADMIN über die Ausnahme");
            assertEquals(Optional.of("READ"), deckel.deckeln("WRITE", Set.of("beobachter"), "schuetu-turnier-ui"),
                    "falsche Rolle");
            assertEquals(Optional.of("READ"), deckel.deckeln("WRITE", Set.of("eintragen"), "mein-token"),
                    "anderer Name");
            assertEquals(Optional.of("READ"), deckel.deckeln("WRITE", Set.of("eintragen"), null));
        }

        @Test
        void leseRollen_sperrenAndereGanz() {
            deckel.setLeseRollen(List.of("admin", "root"));
            assertEquals(Optional.empty(), deckel.hoechsterScope(Set.of("auszahlung", "user"), "x"));
            assertEquals(Optional.empty(), deckel.deckeln("READ", Set.of("finanzen", "user"), "x"));
            assertEquals(List.of(), deckel.waehlbareScopes(Set.of("finanzen", "user")));
            assertEquals(Optional.of("ADMIN"), deckel.deckeln("ADMIN", Set.of("admin"), "x"),
                    "Positivkontrolle: Admin bleibt unverändert");
        }

        @Test
        void waehlbareScopes_jeRolle() {
            assertEquals(List.of("READ"), deckel.waehlbareScopes(Set.of("ROLE_USER", "ROLE_FINANZEN")));
            assertEquals(List.of("READ", "WRITE", "ADMIN"), deckel.waehlbareScopes(Set.of("ROLE_USER", "ROLE_ADMIN")));
            deckel.setServiceTokenSchreibRollen(Map.of("schuetu-turnier-ui", List.of("eintragen")));
            assertEquals(List.of("READ"), deckel.waehlbareScopes(Set.of("ROLE_EINTRAGEN")),
                    "die Service-Token-Ausnahme gilt nicht für selbst ausgestellte Tokens");
        }

        @Test
        void schreibRollen_eigeneStufe() {
            deckel.setSchreibRollen(List.of("admin", "root", "finanzen"));
            assertEquals(Optional.of("WRITE"), deckel.deckeln("ADMIN", Set.of("finanzen"), "x"));
            assertEquals(List.of("READ", "WRITE"), deckel.waehlbareScopes(Set.of("finanzen")));
        }

        @Test
        void abgeschaltet_giltDerClaimAllein() {
            deckel.setEnabled(false);
            assertEquals(Optional.of("ADMIN"), deckel.deckeln("ADMIN", Set.of("user"), "x"));
        }

        /** The YAML keys the apps use must bind, including a token name with hyphens as map key. */
        @Test
        void bindetDieKonfigurationDerApps() {
            Map<String, String> quelle = Map.of(
                    "plaintext.apitoken.scope-deckel.lese-rollen[0]", "admin",
                    "plaintext.apitoken.scope-deckel.lese-rollen[1]", "root",
                    "plaintext.apitoken.scope-deckel.service-token-schreib-rollen.schuetu-turnier-ui[0]", "eintragen",
                    "plaintext.apitoken.scope-deckel.service-token-schreib-rollen.schuetu-turnier-ui[1]", "speaker");
            ApiTokenScopeDeckel gebunden = new Binder(new MapConfigurationPropertySource(quelle))
                    .bind("plaintext.apitoken.scope-deckel", ApiTokenScopeDeckel.class).get();

            assertEquals(List.of("admin", "root"), gebunden.getLeseRollen());
            assertEquals(List.of("eintragen", "speaker"),
                    gebunden.getServiceTokenSchreibRollen().get("schuetu-turnier-ui"));
            assertEquals(List.of("ADMIN", "ROOT"), gebunden.getSchreibRollen(), "Vorgabe bleibt");
            assertEquals(Optional.of("WRITE"),
                    gebunden.deckeln("WRITE", Set.of("speaker"), "schuetu-turnier-ui"));
        }
    }

    // ------------------------------------------------------------------ filter

    @Nested
    class Filter {

        private McpBearerTokenFilter filter(String scope, String tokenName, Set<String> rollen) {
            JwtTokenService jwt = mock(JwtTokenService.class);
            when(jwt.validateToken("tok")).thenReturn(Optional.of(new JwtTokenService.JwtValidationResult(
                    5L, "default", "u@x.ch", tokenName, Instant.now().plusSeconds(60), scope, "jti-" + scope)));
            McpUserRoles roles = mock(McpUserRoles.class);
            when(roles.rolesForUser(5L)).thenReturn(rollen);
            return McpBearerTokenFilter.jwtOnly(jwt, roles);
        }

        private Authentication durchlauf(McpBearerTokenFilter filter) throws Exception {
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getHeader("Authorization")).thenReturn("Bearer tok");
            HttpServletResponse response = mock(HttpServletResponse.class);
            when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
            AtomicReference<Authentication> auth = new AtomicReference<>();
            FilterChain chain = (rq, rs) -> auth.set(SecurityContextHolder.getContext().getAuthentication());
            filter.doFilter(request, response, chain);
            return auth.get();
        }

        private boolean hat(Authentication auth, String authority) {
            return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(authority));
        }

        /** The finding: a user with only a subject role holding a self-issued ADMIN token. */
        @Test
        void adminTokenEinesNichtAdmins_wirdAufReadGedeckelt() throws Exception {
            Authentication auth = durchlauf(filter("ADMIN", "mein-token", Set.of("auszahlung", "user")));

            assertTrue(hat(auth, "SCOPE_READ"));
            assertFalse(hat(auth, "SCOPE_WRITE"), "ein Nicht-Admin darf kein Schreibrecht behalten");
            assertFalse(hat(auth, "SCOPE_EINTRAGEN"));
            assertFalse(hat(auth, "SCOPE_ADMIN"), "ein Nicht-Admin darf kein ADMIN behalten");
            assertTrue(hat(auth, "ROLE_AUSZAHLUNG"), "die Rollen bleiben unverändert im Kontext");
        }

        @Test
        void writeTokenEinesNichtAdmins_wirdAufReadGedeckelt() throws Exception {
            Authentication auth = durchlauf(filter("WRITE", "mein-token", Set.of("eintragen")));
            assertTrue(hat(auth, "SCOPE_READ"));
            assertFalse(hat(auth, "SCOPE_WRITE"));
        }

        @Test
        void adminTokenEinesAdmins_bleibtAdmin_positivkontrolle() throws Exception {
            Authentication auth = durchlauf(filter("ADMIN", "mcpZorin", Set.of("admin", "user")));
            assertTrue(hat(auth, "SCOPE_READ"));
            assertTrue(hat(auth, "SCOPE_WRITE"));
            assertTrue(hat(auth, "SCOPE_ADMIN"));
        }

        @Test
        void serviceTokenMitFachrolle_behaeltWrite_wennKonfiguriert() throws Exception {
            McpBearerTokenFilter f = filter("WRITE", "schuetu-turnier-ui", Set.of("eintragen"));
            ApiTokenScopeDeckel deckel = new ApiTokenScopeDeckel();
            deckel.setServiceTokenSchreibRollen(Map.of("schuetu-turnier-ui", List.of("eintragen")));
            f.setScopeDeckel(deckel);

            Authentication auth = durchlauf(f);
            assertTrue(hat(auth, "SCOPE_WRITE"), "Positivkontrolle: die Turnier-SPA schreibt weiter");
            assertFalse(hat(auth, "SCOPE_ADMIN"));
        }

        @Test
        void serviceToken_ohneKonfiguration_wirdGedeckelt() throws Exception {
            Authentication auth = durchlauf(filter("WRITE", "schuetu-turnier-ui", Set.of("eintragen")));
            assertFalse(hat(auth, "SCOPE_WRITE"));
        }

        @Test
        void ohneFreigegebeneRolle_403_undKetteNichtErreicht() throws Exception {
            McpBearerTokenFilter f = filter("READ", "mein-token", Set.of("auszahlung", "user"));
            ApiTokenScopeDeckel deckel = new ApiTokenScopeDeckel();
            deckel.setLeseRollen(List.of("admin", "root"));
            f.setScopeDeckel(deckel);

            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getHeader("Authorization")).thenReturn("Bearer tok");
            HttpServletResponse response = mock(HttpServletResponse.class);
            StringWriter sink = new StringWriter();
            when(response.getWriter()).thenReturn(new PrintWriter(sink));
            FilterChain chain = mock(FilterChain.class);

            f.doFilter(request, response, chain);

            verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
            verify(chain, never()).doFilter(any(), any());
            assertTrue(sink.toString().contains("Forbidden"));
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }

        @Test
        void leseRollen_adminKommtWeiterDurch_positivkontrolle() throws Exception {
            McpBearerTokenFilter f = filter("ADMIN", "mcpZorin", Set.of("root", "admin"));
            ApiTokenScopeDeckel deckel = new ApiTokenScopeDeckel();
            deckel.setLeseRollen(List.of("admin", "root"));
            f.setScopeDeckel(deckel);
            assertTrue(hat(durchlauf(f), "SCOPE_ADMIN"));
        }

        @Test
        void legacyScopeAdmin_wirdEbenfallsGedeckelt() throws Exception {
            McpBearerTokenFilter f = filter(null, "alt", Set.of("user"));
            f.setLegacyScopeAdmin(true);
            Authentication auth = durchlauf(f);
            assertFalse(hat(auth, "SCOPE_ADMIN"), "der Legacy-Rückfall ADMIN gilt nur für Admins");
            assertTrue(hat(auth, "SCOPE_READ"));
        }

        @Test
        void registrierung_uebernimmtDenKonfiguriertenDeckel() throws Exception {
            McpBearerTokenFilterProperties props = new McpBearerTokenFilterProperties();
            ApiTokenScopeDeckel deckel = new ApiTokenScopeDeckel();
            deckel.setLeseRollen(List.of("admin"));
            @SuppressWarnings("unchecked")
            ObjectProvider<ApiTokenScopeDeckel> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(deckel);

            FilterRegistrationBean<McpBearerTokenFilter> registration = new McpBearerTokenFilterConfig()
                    .mcpBearerTokenFilterRegistration(props, mock(JwtTokenService.class),
                            mock(IApiTokenService.class), mock(McpUserRoles.class), mock(ObjectProvider.class),
                            provider);

            assertEquals(deckel, ReflectionTestUtils.getField(registration.getFilter(), "scopeDeckel"));
        }
    }

    // ------------------------------------------------------------------ UI

    @Nested
    class Oberflaeche {

        private ApiTokenBackingBean bean(ApiTokenService service, String... authorities) {
            ApiTokenBackingBean bean = new ApiTokenBackingBean();
            ReflectionTestUtils.setField(bean, "apiTokenService", service);
            ReflectionTestUtils.setField(bean, "scopeDeckel", new ApiTokenScopeDeckel());
            List<SimpleGrantedAuthority> rechte = java.util.Arrays.stream(authorities)
                    .map(SimpleGrantedAuthority::new).toList();
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("u@x.ch", null, rechte));
            ch.plaintext.PlaintextSecurity security = mock(ch.plaintext.PlaintextSecurity.class);
            when(security.getId()).thenReturn(5L);
            when(security.getMandat()).thenReturn("default");
            when(security.getUser()).thenReturn("u@x.ch");
            new ch.plaintext.boot.plugins.security.PlaintextSecurityHolder().setDelegate(security);
            return bean;
        }

        @Test
        void nichtAdmin_siehtNurRead() {
            assertEquals(List.of("READ"),
                    bean(mock(ApiTokenService.class), "ROLE_USER", "ROLE_AUSZAHLUNG").getVerfuegbareScopes());
        }

        @Test
        void admin_siehtAlleStufen_positivkontrolle() {
            assertEquals(List.of("READ", "WRITE", "ADMIN"),
                    bean(mock(ApiTokenService.class), "ROLE_USER", "ROLE_ADMIN").getVerfuegbareScopes());
        }

        /** A manipulated postback (ADMIN not offered, sent anyway) issues nothing. */
        @Test
        void nichtAdmin_verlangtAdmin_esWirdNichtsAusgestellt() {
            ApiTokenService service = mock(ApiTokenService.class);
            ApiTokenBackingBean b = bean(service, "ROLE_USER", "ROLE_FINANZEN");
            b.setNewTokenName("mein-token");
            b.setNewTokenScope("ADMIN");

            b.createToken();

            verify(service, never()).createToken(any(), anyString(), anyString(), any(), anyInt(), any());
            assertFalse(b.hasNewlyCreatedToken());
        }

        @Test
        void admin_verlangtAdmin_wirdAusgestellt_positivkontrolle() {
            ApiTokenService service = mock(ApiTokenService.class);
            when(service.createToken(5L, "default", "mein-token", "u@x.ch", JwtTokenService.DEFAULT_VALIDITY_DAYS, "ADMIN"))
                    .thenReturn("jwt");
            ApiTokenBackingBean b = bean(service, "ROLE_USER", "ROLE_ADMIN");
            b.setNewTokenName("mein-token");
            b.setNewTokenScope("ADMIN");

            b.createToken();

            verify(service).createToken(5L, "default", "mein-token", "u@x.ch", JwtTokenService.DEFAULT_VALIDITY_DAYS, "ADMIN");
            assertTrue(b.hasNewlyCreatedToken());
        }

        @Test
        void nichtAdmin_verlangtRead_wirdAusgestellt() {
            ApiTokenService service = mock(ApiTokenService.class);
            when(service.createToken(any(), anyString(), anyString(), any(), anyInt(), any())).thenReturn("jwt");
            ApiTokenBackingBean b = bean(service, "ROLE_USER");
            b.setNewTokenName("lesen");
            b.setNewTokenScope("READ");

            b.createToken();

            verify(service).createToken(5L, "default", "lesen", "u@x.ch", JwtTokenService.DEFAULT_VALIDITY_DAYS, "READ");
        }
    }
}
