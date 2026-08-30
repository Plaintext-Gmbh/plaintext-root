/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.secrets.mcp;

import ch.plaintext.secrets.SecretBackendType;
import ch.plaintext.secrets.SecretHealth;
import ch.plaintext.secrets.SecretService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Karte 999: {@code set_secret_backend} und {@code secret_backend_status}.
 *
 * <p><b>Warum es diese Werkzeuge gibt.</b> Das aktive Secrets-Backend liess sich bisher nur ueber
 * die JSF-Oberflaeche setzen. Bei Karte 996 fuehrte das dazu, dass die Konfigurationszeile per SQL
 * geschrieben und dafuer das Kryptoformat nachgebaut werden musste — es hat funktioniert und war
 * belegt, aber es ist kein Weg, den man dokumentieren moechte.</p>
 *
 * <p>Die Autorisierung ist dieselbe wie bei {@code set_secret} und aus demselben Grund im Rumpf
 * geprueft: Wer das Backend umstellen kann, lenkt kuenftige Secrets auf einen Tresor seiner Wahl.</p>
 */
@DisplayName("MCP: Secrets-Backend setzen und abfragen")
class SecretBackendMcpToolsTest {

    private final SecretService service = mock(SecretService.class);
    private final SecretsMcpTools tools = new SecretsMcpTools(service);

    @AfterEach
    void kontextLeeren() {
        SecurityContextHolder.clearContext();
    }

    private void authMit(String... authorities) {
        List<SimpleGrantedAuthority> granted =
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("u@x.ch", "n/a", granted));
    }

    // ── Autorisierung: dieselben drei Faelle wie bei set_secret ──────────────────────────────

    @Test
    @DisplayName("READ-Token darf das Backend nicht umstellen")
    void readTokenDarfNicht() {
        authMit("SCOPE_READ", "ROLE_USER");

        String antwort = tools.setSecretBackend("HASHICORP", "{}");

        verify(service, never()).setActiveBackend(any(), any());
        assertTrue(antwort.startsWith("FEHLER"), antwort);
    }

    @Test
    @DisplayName("ADMIN-Scope ohne ADMIN-Rolle darf nicht — ein Mitglied kann sich den Scope selbst ausstellen")
    void adminScopeOhneRolleDarfNicht() {
        authMit("SCOPE_ADMIN", "ROLE_USER");

        String antwort = tools.setSecretBackend("HASHICORP", "{}");

        verify(service, never()).setActiveBackend(any(), any());
        assertTrue(antwort.startsWith("FEHLER"), antwort);
    }

    @Test
    @DisplayName("die Fehlermeldung nennt das aufrufende Werkzeug, nicht set_secret")
    void meldungNenntDasRichtigeWerkzeug() {
        authMit("SCOPE_READ", "ROLE_USER");

        assertTrue(tools.setSecretBackend("HASHICORP", "{}").contains("set_secret_backend"));
        assertTrue(tools.secretBackendStatus().contains("secret_backend_status"));
        // Gegenprobe: das alte Werkzeug nennt weiterhin sich selbst.
        assertTrue(tools.setSecret("x", "LOCAL_DB", "v", null).contains("set_secret"));
    }

    // ── Fachlich ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("mit ADMIN-Scope UND ADMIN-Rolle wird umgestellt und der Live-Test mitgeliefert")
    void stelltUmUndPrueftGleich() {
        authMit("SCOPE_ADMIN", "ROLE_ADMIN");
        when(service.health()).thenReturn(SecretHealth.up("HashiCorp-Vault erreichbar (mount=secret)."));

        String antwort = tools.setSecretBackend("HASHICORP", "{\"url\":\"http://openbao:8200\"}");

        verify(service).setActiveBackend(SecretBackendType.HASHICORP, "{\"url\":\"http://openbao:8200\"}");
        assertTrue(antwort.startsWith("OK"), antwort);
        assertTrue(antwort.contains("erreichbar"), antwort);
    }

    @Test
    @DisplayName("greift das Backend nicht, ist die Antwort eine WARNUNG statt eines OK")
    void warntWennDasZielNichtGreift() {
        authMit("SCOPE_ADMIN", "ROLE_ROOT");
        when(service.health()).thenReturn(SecretHealth.down("Token ungueltig oder abgelaufen (HTTP 403)"));

        String antwort = tools.setSecretBackend("HASHICORP", "{}");

        // Umgestellt wird trotzdem — sonst stuende die Konfiguration halb da. Aber der Aufrufer
        // erfaehrt es sofort, statt erst beim naechsten gebrauchten Secret.
        verify(service).setActiveBackend(SecretBackendType.HASHICORP, "{}");
        assertTrue(antwort.startsWith("WARNUNG"), antwort);
        assertTrue(antwort.contains("403"), antwort);
    }

    @Test
    @DisplayName("unbekanntes Backend wird abgewiesen, ohne etwas zu aendern")
    void unbekanntesBackend() {
        authMit("SCOPE_ADMIN", "ROLE_ADMIN");

        String antwort = tools.setSecretBackend("OPENBAO", null);

        verify(service, never()).setActiveBackend(any(), any());
        assertTrue(antwort.startsWith("FEHLER"), antwort);
        assertTrue(antwort.contains("VAULTWARDEN"), antwort);
    }

    @Test
    @DisplayName("status nennt Backend und Erreichbarkeit — und keinen Zugangsdaten-Wert")
    void statusOhneZugangsdaten() {
        authMit("SCOPE_ADMIN", "ROLE_ADMIN");
        when(service.activeBackend()).thenReturn(SecretBackendType.HASHICORP);
        when(service.isConfigured()).thenReturn(true);
        when(service.health()).thenReturn(SecretHealth.up("HashiCorp-Vault erreichbar (mount=secret)."));

        String antwort = tools.secretBackendStatus();

        assertTrue(antwort.contains("HASHICORP"), antwort);
        assertTrue(antwort.contains("konfiguriert"), antwort);
        assertTrue(antwort.contains("erreichbar"), antwort);
        // Der Punkt des Werkzeugs: es zeigt den Zustand, nicht das Geheimnis. `health()` liefert
        // Mount und URL, nie den Token — der Test haelt diese Zusage fest.
        assertFalse(antwort.toLowerCase().contains("token\":"), antwort);
    }

    @Test
    @DisplayName("nicht konfiguriert wird als solches gemeldet, nicht als Fehler")
    void nichtKonfiguriert() {
        authMit("SCOPE_ADMIN", "ROLE_ADMIN");
        when(service.activeBackend()).thenReturn(SecretBackendType.VAULTWARDEN);
        when(service.isConfigured()).thenReturn(false);
        when(service.health()).thenReturn(SecretHealth.down("Config fehlt"));

        String antwort = tools.secretBackendStatus();

        assertTrue(antwort.contains("Default, nicht konfiguriert"), antwort);
    }
}
