/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.secrets.mcp;

import ch.plaintext.secrets.SecretBackendType;
import ch.plaintext.secrets.SecretHealth;
import ch.plaintext.secrets.SecretService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MCP-Tool zum Setzen von Secrets. Bewusst nur <b>set</b> (one-way — Werte werden nie ausgelesen).
 *
 * <p>{@link ConditionalOnClass} auf die MCP-Annotation: das Bean lädt NUR in Apps, die selbst einen
 * spring-ai-MCP-Server haben (guild/iot/…). Apps ohne MCP bleiben unberührt (die optionale Dependency
 * ist nicht transitiv, und die Condition verhindert das Laden dieser Klasse).</p>
 *
 * <p><b>Autorisierung (Karte 557):</b> Bis zum 05.08.2026 hatte dieses Werkzeug <em>keine</em>
 * Zugriffskontrolle — weder Annotation noch Prüfung im Rumpf. Da jedes gültige MCP-Token mindestens
 * {@code SCOPE_READ} erhält, konnte ein reines Lesetoken Secrets überschreiben. Verlangt werden jetzt
 * {@code SCOPE_ADMIN} <b>und</b> die Rolle {@code ADMIN} oder {@code ROOT}:</p>
 * <ul>
 *   <li>Der <b>Scope</b> verhindert, dass ein Lese- oder Schreibtoken an den Secret-Speicher kommt.</li>
 *   <li>Die <b>Rolle</b> ist nötig, weil die Token-Ausstellung in der Oberfläche laut
 *       {@code ApiTokenMenu} den Rollen {@code USER, ADMIN, ROOT} offensteht und der Scope dort frei
 *       wählbar ist — ein gewöhnliches Mitglied könnte sich sonst selbst ein ADMIN-Token ausstellen
 *       und die Prüfung damit umgehen. Dieselbe Begründung wie in {@code ApiTokenMcpTools}.</li>
 * </ul>
 *
 * <p><b>Warum im Rumpf und nicht per {@code @PreAuthorize}:</b> Ein MCP-Aufruf läuft nicht über den
 * üblichen Web-Pfad; ob eine Annotation greift, hängt daran, dass Methodensicherheit in der
 * konsumierenden Anwendung eingeschaltet ist und das Bean proxied wird. Eine still wirkungslose
 * Annotation sähe von aussen genauso aus wie eine wirksame — die Prüfung im Rumpf kann nicht
 * ausfallen.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnClass(name = "org.springaicommunity.mcp.annotation.McpTool")
public class SecretsMcpTools {

    private static final String SCOPE_ADMIN = "SCOPE_ADMIN";
    private static final Set<String> SCHREIB_ROLLEN = Set.of("ROLE_ADMIN", "ROLE_ROOT");

    private final SecretService secretService;

    @McpTool(name = "set_secret",
            description = "Set (create or overwrite) a secret — ONE-WAY, the value is never read back. "
            + "backend = VAULTWARDEN (write to the shared vault), LOCAL_DB (AES-encrypted in this app's DB) "
            + "or HASHICORP (configured HashiCorp Vault). note = optional free-text comment. "
            + "The entry's creation timestamp is recorded automatically. "
            + "Requires a caller token with scope=ADMIN and the role ADMIN or ROOT.")
    public String setSecret(
            @McpToolParam(description = "Secret name / key") String name,
            @McpToolParam(description = "Backend: VAULTWARDEN, LOCAL_DB or HASHICORP") String backend,
            @McpToolParam(description = "The secret value to store (write-only)") String value,
            // Karte 520: Die Beschreibung verspricht "Optional", das Schema fuehrte den Parameter
            // aber als Pflicht (@McpToolParam ist per Default required). Wer die Notiz weglassen
            // wollte, musste einen Leerstring oder die Zeichenkette "null" schicken — beides landet
            // als Notiz am Secret. SecretService.set() vertraegt null ausdruecklich ("if (note != null)").
            @McpToolParam(required = false, description = "Optional free-text note/comment") String note) {
        String verweigert = autorisierungPruefen();
        if (verweigert != null) {
            return verweigert;
        }
        SecretBackendType type;
        try {
            type = SecretBackendType.valueOf(backend.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return "FEHLER: ungueltiges backend '" + backend + "' — erlaubt: VAULTWARDEN, LOCAL_DB, HASHICORP";
        }
        try {
            secretService.set(name, type, value, note);
            log.info("MCP: set_secret '{}' ({})", name, type);
            return "OK: Secret '" + name + "' gesetzt (" + type + ").";
        } catch (RuntimeException e) {
            return "FEHLER: " + e.getMessage();
        }
    }

    @McpTool(name = "set_secret_backend",
            description = "Stellt das AKTIVE Secrets-Backend dieses Mandanten um: VAULTWARDEN, "
            + "LOCAL_DB oder HASHICORP. configJson traegt die Zugangsdaten des Ziel-Backends, bei "
            + "HASHICORP {\"url\":\"...\",\"token\":\"...\",\"mount\":\"secret\"} — er wird "
            + "verschluesselt abgelegt und NIE zurueckgegeben. Das aktive Backend ist nur die "
            + "Vorgabe fuer NEU angelegte Secrets; bestehende Eintraege bleiben, wo sie sind. "
            + "Erfordert einen Aufrufer-Token mit scope=ADMIN sowie die Rolle ADMIN oder ROOT.")
    public String setSecretBackend(
            @McpToolParam(description = "Ziel-Backend: VAULTWARDEN, LOCAL_DB oder HASHICORP") String backend,
            @McpToolParam(required = false, description = "Zugangsdaten als JSON; bei LOCAL_DB "
                    + "weglassen. Leer lassen behaelt die bisher hinterlegte Konfiguration.")
            String configJson) {
        String verweigert = autorisierungPruefen("set_secret_backend");
        if (verweigert != null) {
            return verweigert;
        }
        SecretBackendType type;
        try {
            type = SecretBackendType.valueOf(backend.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return "FEHLER: ungueltiges backend '" + backend + "' — erlaubt: VAULTWARDEN, LOCAL_DB, HASHICORP";
        }
        try {
            secretService.setActiveBackend(type, configJson);
            // Der Live-Test gehoert hierher und nicht in einen zweiten Aufruf: Eine Konfiguration,
            // die nicht greift, faellt sonst erst auf, wenn das naechste Secret gebraucht wird.
            SecretHealth h = secretService.health();
            log.info("MCP: set_secret_backend -> {} (health: {})", type, h.ok() ? "ok" : "FEHLER");
            return (h.ok() ? "OK: Backend ist jetzt " + type + ". " : "WARNUNG: Backend ist auf "
                    + type + " gesetzt, greift aber nicht. ") + h.detail();
        } catch (RuntimeException e) {
            return "FEHLER: " + e.getMessage();
        }
    }

    @McpTool(name = "secret_backend_status",
            description = "Zeigt das aktive Secrets-Backend und ob es erreichbar ist — OHNE "
            + "Zugangsdaten. Erfordert einen Aufrufer-Token mit scope=ADMIN sowie die Rolle ADMIN "
            + "oder ROOT.")
    public String secretBackendStatus() {
        String verweigert = autorisierungPruefen("secret_backend_status");
        if (verweigert != null) {
            return verweigert;
        }
        SecretBackendType aktiv = secretService.activeBackend();
        boolean konfiguriert = secretService.isConfigured();
        SecretHealth h = secretService.health();
        // h.detail() ist bewusst dabei: Es nennt die URL und den Mount, aber nie den Token —
        // siehe SecretHealth.up(...) in den Backends.
        return "Backend: " + aktiv + (konfiguriert ? " (konfiguriert)" : " (Default, nicht konfiguriert)")
                + " — " + (h.ok() ? "erreichbar" : "NICHT erreichbar") + ": " + h.detail();
    }

    /**
     * Prüft den Aufrufer gegen den vom Bearer-Filter befüllten SecurityContext.
     *
     * @return {@code null}, wenn der Aufruf zulässig ist — sonst die fertige Fehlermeldung für den
     *         MCP-Client. Die Meldung nennt bewusst nur die fehlende Voraussetzung und keine Werte.
     */
    private String autorisierungPruefen() {
        return autorisierungPruefen("set_secret");
    }

    /**
     * Wie {@link #autorisierungPruefen()}, aber mit dem Namen des aufrufenden Werkzeugs in der
     * Meldung. Ohne den Parameter nannte jede Fehlermeldung {@code set_secret} — auch die von
     * einem anderen Werkzeug, was die Suche in die falsche Richtung schickt.
     */
    private String autorisierungPruefen(String werkzeug) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            log.warn("MCP: {} abgewiesen — nicht authentisiert", werkzeug);
            return "FEHLER: nicht authentisiert.";
        }
        Set<String> authorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toUnmodifiableSet());

        if (!authorities.contains(SCOPE_ADMIN)) {
            log.warn("MCP: {} abgewiesen — scope=ADMIN fehlt (Aufrufer {})", werkzeug, auth.getName());
            return "FEHLER: " + werkzeug + " erfordert einen Aufrufer-Token mit scope=ADMIN.";
        }
        if (SCHREIB_ROLLEN.stream().noneMatch(authorities::contains)) {
            log.warn("MCP: {} abgewiesen — Rolle ADMIN/ROOT fehlt (Aufrufer {})", werkzeug, auth.getName());
            return "FEHLER: " + werkzeug + " erfordert die Rolle ADMIN oder ROOT.";
        }
        return null;
    }
}
