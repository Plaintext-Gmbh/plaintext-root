/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules.katalog;

import ch.plaintext.boot.plugins.log.Log;
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

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Karte 1405: der Schnittstellen-Katalog per MCP, nur lesend. Beschreibt, was die geladenen Module
 * über ihre öffentlichen Verträge anbieten, damit ein LLM neue Funktionen daraus planen kann; ruft
 * selbst keine Methode auf. Rolle ADMIN oder ROOT (Aufbau der Anwendung, keine Fachdaten).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnClass(name = "org.springframework.ai.mcp.annotation.McpTool")
public class SchnittstellenKatalogMcpTools {

    private static final Set<String> ROLLEN = Set.of("ROLE_ADMIN", "ROLE_ROOT");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SchnittstellenKatalog katalog;

    @PreAuthorize("hasAuthority('SCOPE_READ')")
    @McpTool(name = "list_modul_schnittstellen", description = "Listet die öffentlichen Schnittstellen (Verträge) der "
            + "geladenen Module dieser Anwendung: Modul, Name, Zweck in einem Satz, Art (SCHNITTSTELLE = Dienst, DTO = "
            + "übergebenes Model, leer = nicht mit @ModulApi markiert), Stabilität (STABIL/NEU/VERALTET), Herkunft "
            + "(interfaces-Modul oder im Modul), Anzahl Methoden und welche Module sie umsetzen. Grundlage, um neue Funktionen aus vorhandenen Bausteinen zu planen; Details mit "
            + "get_modul_schnittstelle. Erfordert die Rolle ADMIN oder ROOT.")
    public String listModulSchnittstellen() {
        String v = pruefe("list_modul_schnittstellen");
        if (v != null) {
            return v;
        }
        ArrayNode a = JSON.createArrayNode();
        for (SchnittstellenKatalog.Schnittstelle s : katalog.alle()) {
            ObjectNode o = a.addObject();
            o.put("modul", s.modul());
            o.put("name", s.name());
            o.put("zweck", s.zweckKurz());
            // Karte 1422: Art (SCHNITTSTELLE/DTO), Stabilität und Herkunft aus @ModulApi
            o.put("art", s.art());
            o.put("stabilitaet", s.stabilitaet());
            o.put("herkunft", s.herkunft());
            o.put("methoden", s.methoden().size());
            ArrayNode u = o.putArray("umgesetztIn");
            s.umsetzer().stream().map(SchnittstellenKatalog.Umsetzer::modul).distinct().forEach(u::add);
        }
        return a.toPrettyString();
    }

    @PreAuthorize("hasAuthority('SCOPE_READ')")
    @McpTool(name = "get_modul_schnittstelle", description = "Vollständige Beschreibung einer Schnittstelle: Zweck "
            + "(Javadoc), Art, Stabilität, seit, Ersatz, erweiterte Schnittstellen, jede Methode mit Rückgabe, Parametern "
            + "und Zweck sowie die umsetzenden Beans mit Klasse, Modul und — falls vorhanden — der Beschreibung der "
            + "Umsetzung (@ModulApiUmsetzung: Verhalten, Seiteneffekte KEINE/INTERN/AUSSEN, Hinweise, Beispiele). Name voll (ch.plaintext…) oder kurz. Erfordert die Rolle ADMIN oder ROOT.")
    public String getModulSchnittstelle(@McpToolParam(description = "Name der Schnittstelle, voll oder kurz") String name) {
        String v = pruefe("get_modul_schnittstelle");
        if (v != null) {
            return v;
        }
        return katalog.eine(name).map(s -> JSON.valueToTree(s).toPrettyString())
                .orElse("FEHLER: Schnittstelle «" + name + "» steht nicht im Katalog.");
    }

    @PreAuthorize("hasAuthority('SCOPE_READ')")
    @McpTool(name = "suche_modul_schnittstellen", description = "Sucht im Schnittstellen-Katalog nach Text in Name, Zweck "
            + "und Methoden (z. B. 'mail', 'kalender', 'rechnung', 'foto'). Liefert Modul, Name und Zweck in einem Satz. "
            + "Erfordert die Rolle ADMIN oder ROOT.")
    public String sucheModulSchnittstellen(@McpToolParam(description = "Suchtext") String text) {
        String v = pruefe("suche_modul_schnittstellen");
        if (v != null) {
            return v;
        }
        List<SchnittstellenKatalog.Schnittstelle> l = katalog.suche(text);
        ArrayNode a = JSON.createArrayNode();
        l.forEach(s -> a.addObject().put("modul", s.modul()).put("name", s.name()).put("zweck", s.zweckKurz()));
        return a.toPrettyString();
    }

    private String pruefe(String werkzeug) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return "FEHLER: nicht authentisiert.";
        }
        Set<String> a = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toUnmodifiableSet());
        if (ROLLEN.stream().noneMatch(a::contains)) {
            log.warn("MCP: {} abgewiesen — Rolle ADMIN/ROOT fehlt (Aufrufer {})", werkzeug, Log.mail(auth.getName()));
            return "FEHLER: " + werkzeug + " erfordert die Rolle ADMIN oder ROOT.";
        }
        return null;
    }
}
