/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings.mcp;

import ch.plaintext.settings.ISettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MCP tools for the installation settings (card 1050, request by Daniel on 03.09.2026:
 * "Mache mir in root eine mcp schnittstelle dafür token admin").
 *
 * <h2>Why these tools guard harder than an annotation</h2>
 *
 * <p>Settings steer the whole installation: the public address used in every outgoing link
 * ({@code app.ownhost}), mail accounts, feature switches. A token that may merely write should not
 * be able to change them, and a token that may merely read should not see them either — values can
 * carry account ids and endpoints.
 *
 * <p>Therefore, exactly like {@code ApiTokenMcpTools}, every tool here checks <b>in the method
 * body</b> for {@code SCOPE_ADMIN} <b>and</b> the role {@code ADMIN} or {@code ROOT}. An
 * {@code @PreAuthorize} alone would drop the role check — the scope contract lists this build style
 * as a deliberate exception for that reason.
 *
 * <h2>Why it exists at all</h2>
 *
 * <p>Until now a setting could only be maintained through the web mask. Writing straight into the
 * production database is no substitute: it bypasses the audit columns, the value type and every
 * check. This interface makes it a clean, logged path.
 */
@Component
@RequiredArgsConstructor
@Slf4j
// Ohne diese Bedingung scheitert jede Anwendung ohne eigenen MCP-Server beim Start, weil die
// Annotationen dann nicht auf dem Klassenpfad liegen — plaintext-root selbst ist so eine.
// Dieselbe Bauart wie ApiTokenMcpTools.
@ConditionalOnClass(name = "org.springaicommunity.mcp.annotation.McpTool")
public class SettingsMcpTools {

    private static final String SCOPE_ADMIN = "SCOPE_ADMIN";
    private static final Set<String> VERWALTER_ROLLEN = Set.of("ROLE_ADMIN", "ROLE_ROOT");
    private static final Set<String> ERLAUBTE_TYPEN = Set.of("STRING", "INTEGER", "BOOLEAN", "DATE", "LIST");

    private final ISettingsService settingsService;

    @McpTool(name = "list_settings",
            description = "List all setting keys of the caller's tenant together with their current value and type. "
                    + "Requires an ADMIN-scoped token AND the ADMIN or ROOT role.")
    public Object listSettings() {
        Aufrufer a;
        try {
            a = aufruferPruefen();
        } catch (ZugriffVerweigert e) {
            return e.getMessage();
        }
        log.info("MCP: list_settings (mandat={}, user={})", a.mandat(), a.name());
        List<SettingInfo> out = new ArrayList<>();
        for (String key : settingsService.getAllKeys(a.mandat())) {
            out.add(new SettingInfo(key, settingsService.getString(key, a.mandat())));
        }
        return out;
    }

    @McpTool(name = "get_setting",
            description = "Read one setting of the caller's tenant by its key. "
                    + "Requires an ADMIN-scoped token AND the ADMIN or ROOT role.")
    public String getSetting(@McpToolParam(description = "Setting key, e.g. app.ownhost") String key) {
        Aufrufer a;
        try {
            a = aufruferPruefen();
        } catch (ZugriffVerweigert e) {
            return e.getMessage();
        }
        if (leer(key)) {
            return "FEHLER: Bitte einen Schluessel angeben.";
        }
        String wert = settingsService.getString(key.trim(), a.mandat());
        // Karte 1063: getString faellt auf den globalen Eintrag zurueck. Der Wert GILT damit fuer
        // diesen Mandanten — aber er gehoert ihm nicht. Wer ihn aendern will, legt einen eigenen
        // an; wer ihn fuer alle aendern will, muss den globalen anfassen. Deshalb wird die
        // Herkunft mitgesagt statt verschwiegen.
        boolean eigener = settingsService.exists(key.trim(), a.mandat());
        log.info("MCP: get_setting '{}' (mandat={}, gefunden={}, eigener={})",
                key, a.mandat(), wert != null, eigener);
        if (wert == null) {
            return "Kein Wert fuer '" + key.trim() + "' im Mandanten " + a.mandat() + ".";
        }
        return eigener ? wert : wert + "  (globale Einstellung, gilt fuer alle Mandanten)";
    }

    /**
     * Creates or updates a setting.
     *
     * <p>The value type is validated against the five the store knows. An unknown type would be
     * written through and only fail later when something tries to read it as a number or a date —
     * far from the call that caused it.
     */
    @McpTool(name = "set_setting",
            description = "Create or update one setting of the caller's tenant. Example: key='app.ownhost', "
                    + "value='https://app.plaintext.ch', valueType='STRING'. Allowed types: STRING, INTEGER, "
                    + "BOOLEAN, DATE, LIST. Requires an ADMIN-scoped token AND the ADMIN or ROOT role.")
    public String setSetting(
            @McpToolParam(description = "Setting key, e.g. app.ownhost") String key,
            @McpToolParam(description = "The value to store") String value,
            @McpToolParam(required = false, description = "STRING (default), INTEGER, BOOLEAN, DATE or LIST") String valueType,
            @McpToolParam(required = false, description = "What this setting is for — shown in the settings mask") String description) {
        Aufrufer a;
        try {
            a = aufruferPruefen();
        } catch (ZugriffVerweigert e) {
            return e.getMessage();
        }
        if (leer(key)) {
            return "FEHLER: Bitte einen Schluessel angeben.";
        }
        String typ = leer(valueType) ? "STRING" : valueType.trim().toUpperCase(java.util.Locale.ROOT);
        if (!ERLAUBTE_TYPEN.contains(typ)) {
            return "FEHLER: Unbekannter Typ '" + valueType + "'. Erlaubt: " + String.join(", ", ERLAUBTE_TYPEN) + ".";
        }
        // exists() statt getString(): geschrieben wird IMMER beim eigenen Mandanten, also muss
        // auch "neu oder geaendert" dort gemessen werden. Mit getString haette ein bloss global
        // gesetzter Schluessel als "geaendert" gemeldet, obwohl gerade der erste eigene Eintrag
        // entstanden ist (Karte 1063).
        boolean hatteEigenen = settingsService.exists(key.trim(), a.mandat());
        settingsService.setSetting(key.trim(), a.mandat(), value, typ, description);
        log.info("MCP: set_setting '{}' (mandat={}, user={}, {} -> gesetzt)",
                key, a.mandat(), a.name(), hatteEigenen ? "geaendert" : "neu");
        return hatteEigenen
                ? "OK: '" + key.trim() + "' geaendert (vorher war ein Wert gesetzt)."
                : "OK: '" + key.trim() + "' angelegt.";
    }

    @McpTool(name = "delete_setting",
            description = "Delete one setting of the caller's tenant. "
                    + "Requires an ADMIN-scoped token AND the ADMIN or ROOT role.")
    public String deleteSetting(@McpToolParam(description = "Setting key") String key) {
        Aufrufer a;
        try {
            a = aufruferPruefen();
        } catch (ZugriffVerweigert e) {
            return e.getMessage();
        }
        if (leer(key)) {
            return "FEHLER: Bitte einen Schluessel angeben.";
        }
        // exists() statt getString(): Der globale Rueckfall aus Karte 1063 haette hier gemeldet,
        // es gebe etwas zu loeschen, waehrend deleteSetting(key, mandat) anschliessend nichts
        // findet — Ergebnis waere ein "OK: geloescht" fuer einen Eintrag, der unveraendert
        // weitergilt. exists() fragt bewusst NUR den eigenen Mandanten.
        if (!settingsService.exists(key.trim(), a.mandat())) {
            return "Kein eigener Wert fuer '" + key.trim() + "' im Mandanten " + a.mandat()
                    + " — nichts geloescht. (Ein globaler Eintrag wird hier nicht angetastet.)";
        }
        settingsService.deleteSetting(key.trim(), a.mandat());
        log.info("MCP: delete_setting '{}' (mandat={}, user={})", key, a.mandat(), a.name());
        return "OK: '" + key.trim() + "' geloescht.";
    }

    // ── Rechte ──────────────────────────────────────────────────────────────

    /**
     * Checks the caller and returns their tenant.
     *
     * @throws ZugriffVerweigert if not authenticated, {@code SCOPE_ADMIN} or the ADMIN/ROOT role is
     *                           missing, or the tenant cannot be determined
     */
    private Aufrufer aufruferPruefen() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ZugriffVerweigert("FEHLER: nicht authentisiert.");
        }
        Set<String> authorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toUnmodifiableSet());
        if (!authorities.contains(SCOPE_ADMIN)) {
            throw new ZugriffVerweigert("FEHLER: Einstellungen erfordern einen Token mit scope=ADMIN.");
        }
        if (VERWALTER_ROLLEN.stream().noneMatch(authorities::contains)) {
            throw new ZugriffVerweigert("FEHLER: Einstellungen erfordern die Rolle ADMIN oder ROOT.");
        }
        String mandat = praefixWert(authorities, "TOKEN_MANDAT_")
                .or(() -> praefixWert(authorities, "PROPERTY_MANDAT_"))
                .orElseThrow(() -> new ZugriffVerweigert("FEHLER: Mandant im Sicherheitskontext nicht bestimmbar."));
        return new Aufrufer(mandat, auth.getName());
    }

    private static Optional<String> praefixWert(Set<String> authorities, String praefix) {
        return authorities.stream()
                .filter(s -> s.startsWith(praefix))
                .map(s -> s.substring(praefix.length()))
                .filter(s -> !s.isBlank())
                .findFirst();
    }

    private static boolean leer(String s) {
        return s == null || s.isBlank();
    }

    private record Aufrufer(String mandat, String name) {
    }

    /** Kein Stacktrace noetig: die Meldung ist die Antwort an den Aufrufer. */
    private static class ZugriffVerweigert extends RuntimeException {
        ZugriffVerweigert(String meldung) {
            super(meldung, null, false, false);
        }
    }

    public record SettingInfo(String key, String value) {
    }
}
