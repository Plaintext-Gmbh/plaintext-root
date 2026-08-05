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
 * Karte 557: {@code set_secret} hatte weder {@code @PreAuthorize} noch eine manuelle Prüfung — jedes
 * gültige MCP-Token bekommt mindestens {@code SCOPE_READ}, damit konnte ein reines Lesetoken Secrets
 * <b>überschreiben</b>. Das Werkzeug ist one-way (Werte werden nie zurückgelesen): ein Überschreiben
 * zerstört den Wert, und der Schaden fällt erst auf, wenn sich der betroffene Dienst das nächste Mal
 * anmeldet.
 *
 * <p><b>Warum die Prüfung im Rumpf steht und nicht als Annotation:</b> Ein MCP-Aufruf läuft nicht über
 * den üblichen Web-Pfad. Ob {@code @PreAuthorize} hier greift, hängt daran, dass Methodensicherheit in
 * der jeweiligen Anwendung eingeschaltet ist und das Bean tatsächlich proxied wird — beides ist von
 * hier aus nicht garantiert, und eine still wirkungslose Annotation sähe genauso aus wie eine
 * wirksame. Dieselbe Entscheidung wurde in {@code ApiTokenMcpTools} getroffen.
 *
 * <p><b>Warum Scope UND Rolle:</b> Die Token-Ausstellung in der Oberfläche steht laut
 * {@code ApiTokenMenu} den Rollen {@code USER, ADMIN, ROOT} offen, und der Scope ist dort frei
 * wählbar. Eine reine {@code SCOPE_ADMIN}-Prüfung könnte sich also jedes Mitglied selbst
 * ausstellen — sie ist gegen genau den Fall wirkungslos, den diese Karte schliesst.
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

    /** Der eigentliche Befund der Karte: vor dem Fix schrieb dieser Aufruf das Secret. */
    @Test
    void readToken_darfKeinSecretSchreiben() {
        authMit("SCOPE_READ", "ROLE_USER");

        String antwort = tools.setSecret("test-secret", "LOCAL_DB", "wert", null);

        verify(service, never()).set(any(), any(), any(), any());
        assertTrue(antwort.startsWith("FEHLER"), "Antwort war: " + antwort);
    }

    /** Schreibrecht allein genügt nicht — set_secret verlangt ADMIN. */
    @Test
    void writeToken_darfKeinSecretSchreiben() {
        authMit("SCOPE_READ", "SCOPE_WRITE", "SCOPE_EINTRAGEN", "ROLE_USER");

        String antwort = tools.setSecret("test-secret", "LOCAL_DB", "wert", null);

        verify(service, never()).set(any(), any(), any(), any());
        assertTrue(antwort.startsWith("FEHLER"), "Antwort war: " + antwort);
    }

    /**
     * Der Fall, den eine reine Scope-Prüfung offenliesse: Ein gewöhnliches Mitglied kann sich in der
     * Oberfläche selbst ein Token mit Scope ADMIN ausstellen ({@code ApiTokenMenu}: USER/ADMIN/ROOT).
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

    /** Gegenprobe: Ohne sie wäre der Test auch grün, wenn set_secret gar nichts mehr täte. */
    @Test
    void adminScopeMitAdminRolle_darfSchreiben() {
        authMit("SCOPE_READ", "SCOPE_WRITE", "SCOPE_ADMIN", "ROLE_ADMIN");

        String antwort = tools.setSecret("test-secret", "LOCAL_DB", "wert", "notiz");

        verify(service).set(eq("test-secret"), eq(SecretBackendType.LOCAL_DB), eq("wert"), eq("notiz"));
        assertTrue(antwort.startsWith("OK"), "Antwort war: " + antwort);
    }

    /** ROOT ist der zweite zulässige Weg — sonst schlösse der Fix den Root-Benutzer aus. */
    @Test
    void adminScopeMitRootRolle_darfSchreiben() {
        authMit("SCOPE_READ", "SCOPE_ADMIN", "ROLE_ROOT");

        String antwort = tools.setSecret("test-secret", "LOCAL_DB", "wert", null);

        verify(service).set(eq("test-secret"), eq(SecretBackendType.LOCAL_DB), eq("wert"), eq(null));
        assertTrue(antwort.startsWith("OK"), "Antwort war: " + antwort);
    }
}
