/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.secrets.mcp;

import ch.plaintext.secrets.SecretBackendType;
import ch.plaintext.secrets.SecretService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Card 557: {@code set_secret} had neither {@code @PreAuthorize} nor a manual check — every
 * valid MCP token gets at least {@code SCOPE_READ}, so a pure read token could <b>overwrite</b>
 * secrets. The tool is one-way (values are never read back): an overwrite destroys the value, and
 * the damage only becomes apparent the next time the affected service logs in.
 *
 * <p><b>Why the check is in the body and not an annotation:</b> an MCP call does not go through the
 * usual web path. Whether {@code @PreAuthorize} takes effect here depends on method security being
 * enabled in the respective application and on the bean actually being proxied — neither is
 * guaranteed from here, and a silently ineffective annotation would look exactly like an effective
 * one. The same decision was made in {@code ApiTokenMcpTools}.
 *
 * <p><b>Why scope AND role:</b> according to {@code ApiTokenMenu}, issuing tokens in the UI is open
 * to the roles {@code USER, ADMIN, ROOT}, and the scope is freely selectable there. A pure
 * {@code SCOPE_ADMIN} check could therefore be issued by any member to themselves — it is
 * ineffective against exactly the case this card closes.
 */
class SecretsMcpToolsScopeTest {

    private final SecretService service = mock(SecretService.class);
    private final SecretsMcpTools tools = new SecretsMcpTools(service);

    @AfterEach
    void kontextLeeren() {
        SecurityContextHolder.clearContext();
    }

    private void authMit(String... authorities) {
        List<SimpleGrantedAuthority> granted = Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("u@x.ch", "n/a", granted));
    }

    /** The actual finding of the card: before the fix this call wrote the secret. */
    @Test
    void readToken_darfKeinSecretSchreiben() {
        authMit("SCOPE_READ", "ROLE_USER");

        String antwort = tools.setSecret("test-secret", "LOCAL_DB", "wert", null);

        verify(service, never()).set(any(), any(), any(), any());
        assertTrue(antwort.startsWith("FEHLER"), "Antwort war: " + antwort);
    }

    /** Write permission alone is not enough — set_secret requires ADMIN. */
    @Test
    void writeToken_darfKeinSecretSchreiben() {
        authMit("SCOPE_READ", "SCOPE_WRITE", "SCOPE_EINTRAGEN", "ROLE_USER");

        String antwort = tools.setSecret("test-secret", "LOCAL_DB", "wert", null);

        verify(service, never()).set(any(), any(), any(), any());
        assertTrue(antwort.startsWith("FEHLER"), "Antwort war: " + antwort);
    }

    /**
     * The case a pure scope check would leave open: an ordinary member can issue themselves a token
     * with scope ADMIN in the UI ({@code ApiTokenMenu}: USER/ADMIN/ROOT).
     */
    @Test
    void adminScopeOhneAdminRolle_darfKeinSecretSchreiben() {
        authMit("SCOPE_READ", "SCOPE_WRITE", "SCOPE_ADMIN", "ROLE_USER");

        String antwort = tools.setSecret("test-secret", "LOCAL_DB", "wert", null);

        verify(service, never()).set(any(), any(), any(), any());
        assertTrue(antwort.startsWith("FEHLER"), "Antwort war: " + antwort);
    }

    @Test
    void ohneAuthentisierung_darfKeinSecretSchreiben() {
        SecurityContextHolder.clearContext();

        String antwort = tools.setSecret("test-secret", "LOCAL_DB", "wert", null);

        verify(service, never()).set(any(), any(), any(), any());
        assertTrue(antwort.startsWith("FEHLER"), "Antwort war: " + antwort);
    }

    /** Cross-check: without it the test would also be green if set_secret did nothing at all. */
    @Test
    void adminScopeMitAdminRolle_darfSchreiben() {
        authMit("SCOPE_READ", "SCOPE_WRITE", "SCOPE_ADMIN", "ROLE_ADMIN");

        String antwort = tools.setSecret("test-secret", "LOCAL_DB", "wert", "notiz");

        verify(service).set(eq("test-secret"), eq(SecretBackendType.LOCAL_DB), eq("wert"), eq("notiz"));
        assertTrue(antwort.startsWith("OK"), "Antwort war: " + antwort);
    }

    /** ROOT is the second permitted route — otherwise the fix would lock out the root user. */
    @Test
    void adminScopeMitRootRolle_darfSchreiben() {
        authMit("SCOPE_READ", "SCOPE_ADMIN", "ROLE_ROOT");

        String antwort = tools.setSecret("test-secret", "LOCAL_DB", "wert", null);

        verify(service).set(eq("test-secret"), eq(SecretBackendType.LOCAL_DB), eq("wert"), eq(null));
        assertTrue(antwort.startsWith("OK"), "Antwort war: " + antwort);
    }
}
