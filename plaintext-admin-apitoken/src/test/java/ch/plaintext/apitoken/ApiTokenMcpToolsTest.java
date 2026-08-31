/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.apitoken;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ApiTokenMcpTools} — focus on authorization: issuing API tokens via
 * MCP must not be a privilege escalation. It is checked that without {@code SCOPE_ADMIN}, or without
 * the ADMIN/ROOT role, nothing is issued in the first place, that the scope is mandatory and that
 * tokens are only ever issued/revoked for the calling user within their own tenant.
 */
class ApiTokenMcpToolsTest {

    private final ApiTokenService service = mock(ApiTokenService.class);
    private final ApiTokenMcpTools tools = new ApiTokenMcpTools(service);

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authentifiziereAls(String... authorities) {
        List<GrantedAuthority> granted = Arrays.stream(authorities)
                .map(a -> (GrantedAuthority) new SimpleGrantedAuthority(a))
                .toList();
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken("u@x.ch", null, granted));
        SecurityContextHolder.setContext(ctx);
    }

    private void authAdmin() {
        authentifiziereAls("ROLE_USER", "ROLE_ADMIN", "SCOPE_READ", "SCOPE_EINTRAGEN", "SCOPE_ADMIN",
                "PROPERTY_MYUSERID_7", "PROPERTY_MANDAT_plaintext");
    }

    @Test
    void ohneAuthentication_keineAusstellung() {
        assertTrue(tools.createApiToken("t", "READ", 30).startsWith("FEHLER"));
        verifyNoInteractions(service);
    }

    @Test
    void ohneScopeAdmin_keineAusstellung() {
        authentifiziereAls("ROLE_ADMIN", "SCOPE_READ", "PROPERTY_MYUSERID_7", "PROPERTY_MANDAT_plaintext");
        String antwort = tools.createApiToken("t", "ADMIN", 30);
        assertTrue(antwort.contains("scope=ADMIN"), antwort);
        verifyNoInteractions(service);
    }

    @Test
    void ohneAdminRolle_keineAusstellung() {
        authentifiziereAls("ROLE_USER", "SCOPE_ADMIN", "PROPERTY_MYUSERID_7", "PROPERTY_MANDAT_plaintext");
        String antwort = tools.createApiToken("t", "READ", 30);
        assertTrue(antwort.contains("Rolle ADMIN oder ROOT"), antwort);
        verifyNoInteractions(service);
    }

    @Test
    void rootRolleGenuegt() {
        authentifiziereAls("ROLE_ROOT", "SCOPE_ADMIN", "PROPERTY_MYUSERID_7", "PROPERTY_MANDAT_plaintext");
        when(service.createToken(anyLong(), anyString(), anyString(), anyString(), anyInt(), anyString()))
                .thenReturn("jwt-x");
        assertTrue(tools.createApiToken("t", "READ", 30).contains("jwt-x"));
    }

    @Test
    void fehlenderScope_wirdNichtStillschweigendGesetzt() {
        authAdmin();
        String antwort = tools.createApiToken("t", "  ", 30);
        assertTrue(antwort.contains("scope fehlt"), antwort);
        verify(service, never()).createToken(anyLong(), anyString(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void unbekannterScope_wirdAbgelehnt() {
        authAdmin();
        assertTrue(tools.createApiToken("t", "SUPERUSER", 30).contains("ungueltiger scope"));
        verify(service, never()).createToken(anyLong(), anyString(), anyString(), anyString(), anyInt(), anyString());
    }

    /**
     * Card 545: {@code WRITE} is the new name of the write permission, {@code EINTRAGEN} stays
     * valid during the transition window. Both must pass the issuing path — otherwise a client that
     * has already switched to the new name can no longer have a token issued to it.
     */
    @Test
    void schreibScopes_werdenBeideAkzeptiert_neuerUndAlterName() {
        authAdmin();
        when(service.createToken(eq(7L), eq("plaintext"), anyString(), eq("u@x.ch"), eq(30), anyString()))
                .thenReturn("jwt-abc");

        assertTrue(tools.createApiToken("neu", "WRITE", 30).contains("jwt-abc"));
        assertTrue(tools.createApiToken("alt", "eintragen", 30).contains("jwt-abc"));

        verify(service).createToken(7L, "plaintext", "neu", "u@x.ch", 30, "WRITE");
        verify(service).createToken(7L, "plaintext", "alt", "u@x.ch", 30, "EINTRAGEN");
    }

    @Test
    void gueltigeAusstellung_nutztIdentitaetDesAufrufers() {
        authAdmin();
        when(service.createToken(eq(7L), eq("plaintext"), eq("mcpZorin"), eq("u@x.ch"), eq(90), eq("ADMIN")))
                .thenReturn("jwt-abc");

        String antwort = tools.createApiToken(" mcpZorin ", "admin", null);

        assertTrue(antwort.contains("jwt-abc"), antwort);
        verify(service).createToken(7L, "plaintext", "mcpZorin", "u@x.ch", 90, "ADMIN");
    }

    @Test
    void gueltigkeitAusserhalbDerGrenzen_wirdAbgelehnt() {
        authAdmin();
        assertTrue(tools.createApiToken("t", "READ", 1).contains("validityDays"));
        assertTrue(tools.createApiToken("t", "READ", 9999).contains("validityDays"));
        verify(service, never()).createToken(anyLong(), anyString(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void fremderToken_wirdNichtWiderrufen() {
        authAdmin();
        when(service.getAllTokens(7L, "plaintext")).thenReturn(List.of(token(1L, "eigener")));

        String antwort = tools.revokeApiToken(42L);

        assertTrue(antwort.startsWith("FEHLER"), antwort);
        verify(service, never()).invalidateToken(anyLong(), anyLong(), anyString());
    }

    @Test
    void eigenerToken_wirdWiderrufen() {
        authAdmin();
        when(service.getAllTokens(7L, "plaintext")).thenReturn(List.of(token(1L, "eigener")));

        assertEquals("OK: Token 1 widerrufen.", tools.revokeApiToken(1L));
        verify(service).invalidateToken(1L, 7L, "plaintext");
    }

    @Test
    void listeZeigtKeineTokenStrings() {
        authAdmin();
        when(service.getAllTokens(7L, "plaintext")).thenReturn(List.of(token(1L, "mcpZorin")));

        String antwort = tools.listApiTokens();

        assertTrue(antwort.contains("mcpZorin"), antwort);
        assertTrue(antwort.contains("id=1"), antwort);
        assertTrue(!antwort.contains("hash-1"), "Hash/Token darf nicht in der Ausgabe stehen: " + antwort);
    }

    /**
     * Card 670: The context holds two contradictory {@code PROPERTY_MANDAT_*} — one from the
     * token claim (lower case) and one from {@code my_user_entity.roles}, where the same tenant is
     * stored in upper case in PROD. Authoritative is the tenant of the <b>token</b>.
     *
     * <p>Both insertion orders are checked. Taken by itself that does <b>not</b> prove that
     * the selection is hash-independent — a {@code HashSet} ignores the insertion order, both
     * runs see the same ordering. The variation guards against a later implementation
     * that does honour the order after all (a {@code LinkedHashSet} in the caller, say). That the
     * test actually bites has been counter-checked: with the old {@code findFirst()} over
     * {@code PROPERTY_MANDAT_} it fails ("FEHLER: Token 1 nicht gefunden").
     */
    @Test
    void zweiMandatAuthorities_tokenMandatGewinnt_unabhaengigVonDerReihenfolge() {
        for (boolean grossZuerst : new boolean[] {true, false}) {
            SecurityContextHolder.clearContext();
            if (grossZuerst) {
                authentifiziereAls("ROLE_ADMIN", "SCOPE_ADMIN", "PROPERTY_MYUSERID_7",
                        "PROPERTY_MANDAT_PLAINTEXT", "PROPERTY_MANDAT_plaintext",
                        "PROPERTY_TOKEN_MANDAT_plaintext");
            } else {
                authentifiziereAls("ROLE_ADMIN", "SCOPE_ADMIN", "PROPERTY_MYUSERID_7",
                        "PROPERTY_TOKEN_MANDAT_plaintext", "PROPERTY_MANDAT_plaintext",
                        "PROPERTY_MANDAT_PLAINTEXT");
            }
            when(service.getAllTokens(7L, "plaintext")).thenReturn(List.of(token(1L, "eigener")));

            assertEquals("OK: Token 1 widerrufen.", tools.revokeApiToken(1L),
                    "grossZuerst=" + grossZuerst);
            verify(service, never()).invalidateToken(anyLong(), anyLong(), eq("PLAINTEXT"));
        }
    }

    /**
     * Card 670: Between the root release and the rollout in app/guild/schuetu, a filter without
     * {@code PROPERTY_TOKEN_MANDAT_*} is still running there. Without a fallback to
     * {@code PROPERTY_MANDAT_}, every token tool would abort during that window with "tenant cannot
     * be determined" — a read error would turn into an outage.
     */
    @Test
    void ohneTokenMandatAuthority_faelltAufAltePropertyZurueck() {
        authentifiziereAls("ROLE_ADMIN", "SCOPE_ADMIN", "PROPERTY_MYUSERID_7",
                "PROPERTY_MANDAT_plaintext");
        when(service.getAllTokens(7L, "plaintext")).thenReturn(List.of(token(1L, "eigener")));

        assertEquals("OK: Token 1 widerrufen.", tools.revokeApiToken(1L));
        verify(service).invalidateToken(1L, 7L, "plaintext");
    }

    /** Card 670: If every tenant authority is missing, nothing is guessed — the call is aborted. */
    @Test
    void ohneJedeMandatAuthority_keineAusstellung() {
        authentifiziereAls("ROLE_ADMIN", "SCOPE_ADMIN", "PROPERTY_MYUSERID_7");

        assertTrue(tools.createApiToken("t", "READ", 30).contains("Mandant"),
                "Ohne Mandant darf kein Token ausgestellt werden");
        verify(service, never()).createToken(anyLong(), anyString(), anyString(), anyString(),
                anyInt(), anyString());
    }

    private ApiToken token(Long id, String name) {
        ApiToken t = new ApiToken();
        t.setId(id);
        t.setTokenName(name);
        t.setTokenHash("hash-" + id);
        t.setUserId(7L);
        t.setMandat("plaintext");
        t.setExpiresAt(LocalDateTime.now().plusDays(30));
        t.setDeleted(false);
        return t;
    }
}
