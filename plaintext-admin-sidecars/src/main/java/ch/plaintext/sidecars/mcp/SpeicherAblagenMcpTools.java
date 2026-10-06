/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars.mcp;

import ch.plaintext.boot.plugins.log.Log;
import ch.plaintext.sidecars.entity.SpeicherAblage;
import ch.plaintext.sidecars.service.SpeicherAblageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MCP für die Speicher-Ablagen (Karte 1406). Rechte wie {@link SidecarsMcpTools}: Rolle ROOT, Lesen
 * mit {@code SCOPE_READ}, Einrichten mit {@code SCOPE_ADMIN}; das App-Passwort nur schreiben.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnClass(name = "org.springframework.ai.mcp.annotation.McpTool")
public class SpeicherAblagenMcpTools {

    /** Praefix jeder Fehlerantwort (Karte 1416, Sonar java:S1192). */
    private static final String FEHLER = "FEHLER: ";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SpeicherAblageService service;

    @PreAuthorize("hasAuthority('SCOPE_READ')")
    @McpTool(name = "list_speicher_ablagen", description = "Listet die Speicher-Ablagen (heute Nextcloud): Name, Adresse, "
            + "Benutzer, Pfad, letzte Prüfung und Ergebnis. Module legen darüber Dateien ab (Schnittstelle DateiAblage). "
            + "Ohne Passwort. Erfordert die Rolle ROOT.")
    public String listSpeicherAblagen() {
        String v = pruefe("list_speicher_ablagen", false);
        if (v != null) {
            return v;
        }
        ArrayNode a = JSON.createArrayNode();
        for (SpeicherAblage s : service.liste()) {
            ObjectNode o = a.addObject();
            o.put("name", s.getName());
            o.put("art", s.getArt());
            o.put("url", s.getUrl());
            o.put("benutzer", s.getBenutzer());
            o.put("pfad", s.getPfad());
            o.put("passwortHinterlegt", s.hatPasswort());
            o.put("ok", s.getOk());
            o.put("meldung", s.getMeldung());
            o.put("letztePruefung", String.valueOf(s.getLetztePruefung()));
        }
        return a.toPrettyString();
    }

    @PreAuthorize("hasAuthority('SCOPE_READ')")
    @McpTool(name = "pruefe_speicher_ablage", description = "Prüft Anmeldung und Ordner einer Speicher-Ablage sofort "
            + "und gibt das Ergebnis zurück. Erfordert die Rolle ROOT.")
    public String pruefeSpeicherAblage(@McpToolParam(description = "Name der Ablage") String name) {
        String v = pruefe("pruefe_speicher_ablage", false);
        if (v != null) {
            return v;
        }
        try {
            SpeicherAblage s = service.pruefe(service.eintrag(name));
            return (Boolean.TRUE.equals(s.getOk()) ? "OK: " : FEHLER) + s.getMeldung();
        } catch (NoSuchElementException e) {
            return FEHLER + e.getMessage();
        }
    }

    @PreAuthorize("hasAuthority('SCOPE_ADMIN')")
    @McpTool(name = "set_speicher_ablage", description = "Richtet eine Speicher-Ablage ein oder ändert sie (Nextcloud): "
            + "name (a-z, 0-9, -), url (z. B. https://home.plaintext.ch), benutzer, passwort (App-Passwort, nur schreiben; "
            + "leer = unverändert, beim Anlegen Pflicht), pfad (Ordner in der Nextcloud). Prüft danach sofort. "
            + "Erfordert scope=ADMIN und die Rolle ROOT.")
    public String setSpeicherAblage(@McpToolParam(description = "Name der Ablage") String name,
                                    @McpToolParam(description = "Nextcloud-Adresse") String url,
                                    @McpToolParam(description = "Benutzer") String benutzer,
                                    @McpToolParam(required = false, description = "App-Passwort; leer = unverändert") String passwort,
                                    @McpToolParam(description = "Ordner in der Nextcloud, z. B. Projekte/drawio") String pfad) {
        String v = pruefe("set_speicher_ablage", true);
        if (v != null) {
            return v;
        }
        try {
            SpeicherAblage s = service.speichere(name, url, benutzer, passwort, pfad);
            return (Boolean.TRUE.equals(s.getOk()) ? "OK: " : "GESPEICHERT, ABER NICHT ERREICHBAR: ") + s.getMeldung();
        } catch (IllegalArgumentException e) {
            return FEHLER + e.getMessage();
        }
    }

    private String pruefe(String werkzeug, boolean schreibend) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return "FEHLER: nicht authentisiert.";
        }
        Set<String> a = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toUnmodifiableSet());
        if (!a.contains("ROLE_ROOT")) {
            log.warn("MCP: {} abgewiesen — Rolle ROOT fehlt (Aufrufer {})", werkzeug, Log.mail(auth.getName()));
            return FEHLER + werkzeug + " erfordert die Rolle ROOT.";
        }
        if (schreibend && !a.contains("SCOPE_ADMIN")) {
            return FEHLER + werkzeug + " erfordert einen Aufrufer-Token mit scope=ADMIN.";
        }
        return null;
    }
}
