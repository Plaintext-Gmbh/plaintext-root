/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars.mcp;

import ch.plaintext.boot.plugins.log.Log;
import ch.plaintext.sidecars.SidecarVerbindung;
import ch.plaintext.sidecars.entity.AuthZustand;
import ch.plaintext.sidecars.entity.Sidecar;
import ch.plaintext.sidecars.service.SidecarBeschreibung;
import ch.plaintext.sidecars.service.SidecarProtokollClient;
import ch.plaintext.sidecars.service.SidecarService;
import tools.jackson.core.type.TypeReference;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
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

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MCP-Zugang zur Sidecar-Registry (Karte 1400, Daniel 02.10.2026): ein LLM soll sehen, welche
 * Zusatzdienste es gibt und was sie können, um neue Funktionen damit zu planen.
 *
 * <p><b>Rechte.</b> Alles nur mit der Rolle {@code ROOT}, wie die Seite. Lesen genügt mit
 * {@code SCOPE_READ}; was ändert oder einen Sidecar aufruft, verlangt {@code SCOPE_ADMIN}. Die
 * Schranke steht zweimal: als {@code @PreAuthorize} (für den Scope-Vertrag) und im Rumpf, der auch
 * dann greift, wenn die Annotation in einer Anwendung nicht wirkt (Begründung in
 * {@code SecretsMcpTools}).</p>
 *
 * <p><b>Allgemeiner Aufruf.</b> {@code rufe_sidecar_faehigkeit} ruft nur Fähigkeiten auf, die der
 * Sidecar selbst mit {@code mcp: true} freigibt und die nicht nach aussen wirken
 * ({@code seiteneffekt: aussen} nie); Methode und Pfad kommen aus seiner Beschreibung, nie vom LLM.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnClass(name = "org.springframework.ai.mcp.annotation.McpTool")
public class SidecarsMcpTools {

    /** Status, wenn der Sidecar nicht antwortet (Karte 1416, Sonar java:S1192). */
    private static final String NICHT_ERREICHBAR = "nicht erreichbar";

    /** JSON-Feld (Karte 1416, Sonar java:S1192). */
    private static final String FELD_ERREICHBAR = "erreichbar";

    private static final String SCOPE_ADMIN = "SCOPE_ADMIN";
    private static final String ROLE_ROOT = "ROLE_ROOT";
    private static final ObjectMapper JSON = JsonMapper.builderWithJackson2Defaults().build();

    private final SidecarService service;
    private final SidecarProtokollClient client;

    @PreAuthorize("hasAuthority('SCOPE_READ')")
    @McpTool(name = "list_sidecars", description = "Listet die Sidecars dieser Anwendung (eigenständige Container mit "
            + "Zusatzdiensten nach dem Plaintext-Sidecar-Protokoll): Name, Titel, Version, erreichbar, Status, "
            + "Zugang (Token-Zustand) und die Ids ihrer Fähigkeiten. Planungsgrundlage für neue Funktionen; "
            + "Details mit get_sidecar. Erfordert die Rolle ROOT.")
    public String listSidecars() {
        String v = pruefe("list_sidecars", false);
        if (v != null) {
            return v;
        }
        ArrayNode a = JSON.createArrayNode();
        for (Sidecar s : service.liste()) {
            SidecarBeschreibung b = service.beschreibung(s);
            ObjectNode o = a.addObject();
            o.put("name", s.getName());
            o.put("titel", s.getTitel());
            o.put("version", s.getVersion());
            o.put(FELD_ERREICHBAR, s.isErreichbar());
            o.put("status", s.isErreichbar() ? s.getStatus() : NICHT_ERREICHBAR);
            o.put("zugang", String.valueOf(s.getAuthZustand()));
            ArrayNode f = o.putArray("faehigkeiten");
            if (b != null) {
                b.faehigkeiten().forEach(x -> f.add(x.id()));
            }
        }
        return a.toPrettyString();
    }

    @PreAuthorize("hasAuthority('SCOPE_READ')")
    @McpTool(name = "get_sidecar", description = "Vollständige Beschreibung eines Sidecars: Status, Teile und alle "
            + "Fähigkeiten mit Zweck, Methode, Pfad, Ein-/Ausgabe als JSON-Schema, ob ein Token nötig ist, "
            + "Seiteneffekt und ob die Fähigkeit per rufe_sidecar_faehigkeit aufrufbar ist. Ohne Token. "
            + "Erfordert die Rolle ROOT.")
    public String getSidecar(@McpToolParam(description = "Name des Sidecars, z. B. whatsapp") String name) {
        String v = pruefe("get_sidecar", false);
        if (v != null) {
            return v;
        }
        try {
            Sidecar s = service.sidecar(name);
            ObjectNode o = JSON.createObjectNode();
            o.put("name", s.getName());
            o.put("url", s.getUrl());
            o.put("quelle", s.getQuelle().name());
            o.put(FELD_ERREICHBAR, s.isErreichbar());
            o.put("zugang", String.valueOf(s.getAuthZustand()));
            o.put("fehler", s.getFehler());
            o.put("letzteAbfrage", String.valueOf(s.getLetzteAbfrage()));
            if (s.getBeschreibungJson() != null) {
                o.set("beschreibung", JSON.readTree(s.getBeschreibungJson()));
                SidecarBeschreibung b = service.beschreibung(s);
                ArrayNode aufrufbar = o.putArray("perMcpAufrufbar");
                if (b != null) {
                    b.faehigkeiten().stream().filter(SidecarBeschreibung.Faehigkeit::perMcpAufrufbar).forEach(f -> aufrufbar.add(f.id()));
                }
            }
            return o.toPrettyString();
        } catch (NoSuchElementException | JacksonException e) {
            return fehler(e.getMessage());
        }
    }

    @PreAuthorize("hasAuthority('SCOPE_READ')")
    @McpTool(name = "find_sidecar_faehigkeit", description = "Sucht über alle Sidecars nach Fähigkeiten: Text in Id, "
            + "Titel oder Beschreibung (z. B. 'bild', 'gesicht', 'signal'). Liefert Sidecar, Id, Titel, "
            + "Seiteneffekt und ob der Sidecar gerade erreichbar ist. Erfordert die Rolle ROOT.")
    public String findSidecarFaehigkeit(@McpToolParam(description = "Suchtext") String text) {
        String v = pruefe("find_sidecar_faehigkeit", false);
        if (v != null) {
            return v;
        }
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT).strip();
        ArrayNode a = JSON.createArrayNode();
        for (Sidecar s : service.liste()) {
            SidecarBeschreibung b = service.beschreibung(s);
            if (b == null) {
                continue;
            }
            for (SidecarBeschreibung.Faehigkeit f : b.faehigkeiten()) {
                String heu = (f.id() + " " + f.titel() + " " + (f.beschreibung() == null ? "" : f.beschreibung())).toLowerCase(Locale.ROOT);
                if (t.isEmpty() || heu.contains(t)) {
                    ObjectNode o = a.addObject();
                    o.put("sidecar", s.getName());
                    o.put(FELD_ERREICHBAR, s.isErreichbar());
                    o.put("id", f.id());
                    o.put("titel", f.titel());
                    o.put("seiteneffekt", f.seiteneffekt());
                    o.put("perMcpAufrufbar", f.perMcpAufrufbar());
                }
            }
        }
        return a.toPrettyString();
    }

    @PreAuthorize("hasAuthority('SCOPE_READ')")
    @McpTool(name = "pruefe_sidecar", description = "Fragt einen Sidecar sofort neu ab (Status, Version, Fähigkeiten, "
            + "Token-Zustand) und gibt den neuen Stand zurück. Erfordert die Rolle ROOT.")
    public String pruefeSidecar(@McpToolParam(description = "Name des Sidecars") String name) {
        String v = pruefe("pruefe_sidecar", false);
        if (v != null) {
            return v;
        }
        try {
            Sidecar s = service.aktualisiere(service.sidecar(name));
            return "OK: " + s.getName() + " ist " + (s.isErreichbar() ? s.getStatus() : NICHT_ERREICHBAR)
                    + ", Zugang " + s.getAuthZustand() + (s.getFehler() == null ? "" : ". Hinweis: " + s.getFehler());
        } catch (NoSuchElementException e) {
            return fehler(e.getMessage());
        }
    }

    @PreAuthorize("hasAuthority('SCOPE_ADMIN')")
    @McpTool(name = "registriere_sidecar", description = "Ergänzt einen Sidecar über seine Basis-URL (z. B. "
            + "http://plaintext-sidecar-fotos:3003). Er muss das Plaintext-Sidecar-Protokoll sprechen; der Name kommt "
            + "aus seiner Beschreibung. Nur Hosts aus der Konfiguration oder plaintext.sidecars.erlaubte-hosts. "
            + "Erfordert scope=ADMIN und die Rolle ROOT.")
    public String registriereSidecar(@McpToolParam(description = "Basis-URL des Sidecars") String url) {
        String v = pruefe("registriere_sidecar", true);
        if (v != null) {
            return v;
        }
        try {
            Sidecar s = service.registriere(url);
            return "OK: Sidecar «" + s.getName() + "» ergänzt, " + (s.isErreichbar() ? s.getStatus() : NICHT_ERREICHBAR) + ".";
        } catch (IllegalArgumentException e) {
            return fehler(e.getMessage());
        }
    }

    @PreAuthorize("hasAuthority('SCOPE_ADMIN')")
    @McpTool(name = "set_sidecar_token", description = "Hinterlegt den Token eines Sidecars (nur schreiben, er wird nie "
            + "zurückgegeben) und prüft ihn sofort gegen den Sidecar. Leerer Token entfernt ihn. "
            + "Erfordert scope=ADMIN und die Rolle ROOT.")
    public String setSidecarToken(@McpToolParam(description = "Name des Sidecars") String name,
                                  @McpToolParam(required = false, description = "Token; leer = entfernen") String token) {
        String v = pruefe("set_sidecar_token", true);
        if (v != null) {
            return v;
        }
        try {
            AuthZustand z = service.setzeToken(name, token);
            return "OK: Token für «" + name + "» " + (token == null || token.isBlank() ? "entfernt" : "hinterlegt") + ", Zustand " + z + ".";
        } catch (NoSuchElementException e) {
            return fehler(e.getMessage());
        }
    }

    @PreAuthorize("hasAuthority('SCOPE_ADMIN')")
    @McpTool(name = "rufe_sidecar_faehigkeit", description = "Ruft eine Fähigkeit eines Sidecars auf, die er selbst für "
            + "MCP freigibt (mcp=true, kein Seiteneffekt nach aussen); siehe perMcpAufrufbar in get_sidecar. "
            + "Methode und Pfad kommen aus der Beschreibung des Sidecars, der hinterlegte Token wird mitgeschickt. "
            + "Antwort: Status, Inhaltstyp und Text (gekürzt auf 64 KB); binäre Antworten nur mit Grösse. "
            + "Erfordert scope=ADMIN und die Rolle ROOT.")
    public String rufeSidecarFaehigkeit(
            @McpToolParam(description = "Name des Sidecars") String name,
            @McpToolParam(description = "Id der Fähigkeit, z. B. bild.info") String faehigkeit,
            @McpToolParam(required = false, description = "Query-Parameter als JSON-Objekt mit Text-Werten") String parameterJson,
            @McpToolParam(required = false, description = "Body als JSON (bei POST/PUT/PATCH)") String bodyJson) {
        String v = pruefe("rufe_sidecar_faehigkeit", true);
        if (v != null) {
            return v;
        }
        try {
            Sidecar s = service.sidecar(name);
            SidecarBeschreibung b = service.beschreibung(s);
            SidecarBeschreibung.Faehigkeit f = b == null ? null : b.faehigkeit(faehigkeit);
            if (f == null) {
                return fehler("«" + name + "» bietet die Fähigkeit «" + faehigkeit + "» nicht an.");
            }
            if (!f.perMcpAufrufbar()) {
                return fehler("Die Fähigkeit «" + faehigkeit + "» ist nicht für MCP freigegeben (mcp=" + f.mcp()
                        + ", seiteneffekt=" + f.seiteneffekt() + ").");
            }
            Map<String, String> parameter = parameterJson == null || parameterJson.isBlank() ? Map.of()
                    : JSON.readValue(parameterJson, new TypeReference<Map<String, String>>() { });
            if (bodyJson != null && !bodyJson.isBlank()) {
                JSON.readTree(bodyJson);
            }
            SidecarVerbindung verbindung = service.verbindung(name).orElseThrow();
            log.info("MCP: rufe_sidecar_faehigkeit {} {} (Aufrufer {})", name, faehigkeit, Log.mail(aufrufer()));
            SidecarProtokollClient.Antwort a = client.rufe(verbindung, f, parameter, bodyJson);
            ObjectNode o = JSON.createObjectNode();
            o.put("status", a.status());
            o.put("inhaltTyp", a.inhaltTyp());
            o.put("bytes", a.bytes());
            o.put("gekuerzt", a.gekuerzt());
            o.put("text", a.text());
            return o.toPrettyString();
        } catch (NoSuchElementException | IOException | JacksonException e) {
            return fehler(e.getMessage());
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return fehler("Aufruf unterbrochen.");
        }
    }

    /** @return Fehlermeldung oder {@code null}, wenn der Aufrufer darf */
    private String pruefe(String werkzeug, boolean schreibend) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return fehler("nicht authentisiert.");
        }
        Set<String> a = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toUnmodifiableSet());
        if (!a.contains(ROLE_ROOT)) {
            log.warn("MCP: {} abgewiesen — Rolle ROOT fehlt (Aufrufer {})", werkzeug, Log.mail(auth.getName()));
            return fehler(werkzeug + " erfordert die Rolle ROOT.");
        }
        if (schreibend && !a.contains(SCOPE_ADMIN)) {
            log.warn("MCP: {} abgewiesen — scope=ADMIN fehlt (Aufrufer {})", werkzeug, Log.mail(auth.getName()));
            return fehler(werkzeug + " erfordert einen Aufrufer-Token mit scope=ADMIN.");
        }
        return null;
    }

    private static String aufrufer() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "?" : auth.getName();
    }

    private static String fehler(String text) {
        return "FEHLER: " + text;
    }

    /** Für Tests: welche Werkzeuge schreiben bzw. aufrufen. */
    static List<String> schreibendeWerkzeuge() {
        return List.of("registriere_sidecar", "set_sidecar_token", "rufe_sidecar_faehigkeit");
    }
}
