/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.freigabe.link.mcp;

import ch.plaintext.freigabe.FreigabeQuelle;
import ch.plaintext.freigabe.FreigabeRecht;
import ch.plaintext.freigabe.link.service.FreigabeLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * MCP für Freigabe-Links (Karte 1476). Lesen mit {@code SCOPE_READ}, anlegen und widerrufen mit {@code SCOPE_WRITE}.
 * Ob der Aufrufer ein Objekt freigeben darf, entscheidet die {@link FreigabeQuelle} des Moduls (Rolle, Mandat,
 * Sichtbarkeit). Das Token gibt es nur in der Antwort von {@code erstelle_freigabe_link}; es gilt nur unter
 * {@code /nosec/freigabe/}, nie am {@code /mcp}.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnClass(name = "org.springframework.ai.mcp.annotation.McpTool")
public class FreigabeLinkMcpTools {

    private static final String FEHLER = "FEHLER: ";

    private final FreigabeLinkService service;

    @PreAuthorize("hasAuthority('SCOPE_WRITE')")
    @McpTool(name = "erstelle_freigabe_link", description = "Legt einen Freigabe-Link ohne Anmeldung auf ein Objekt eines Moduls an "
            + "(z.B. typ=drawio). Recht r (lesen) oder rw (lesen und schreiben, nur wenn das Modul es kann), Ablauf in Tagen, "
            + "optional ein Teil (z.B. eine Seite). Die Adresse wird nur JETZT ausgegeben, gespeichert ist nur ihr Hash. "
            + "Der Aufrufer muss das Objekt im Modul freigeben dürfen.")
    public String erstelleFreigabeLink(
            @McpToolParam(description = "Objekttyp, siehe list_freigabe_links ohne Parameter (Zeile 'Typen')") String typ,
            @McpToolParam(description = "Id des Objekts im Modul") Long objektId,
            @McpToolParam(required = false, description = "r (Vorgabe) oder rw") String recht,
            @McpToolParam(required = false, description = "Teil des Objekts, leer = ganzes Objekt") String teil,
            @McpToolParam(required = false, description = "Gültigkeit in Tagen, 0 = unbefristet, leer = 90, höchstens 3650") Integer gueltigTage,
            @McpToolParam(required = false, description = "Zweck, z.B. Empfänger") String zweck) {
        try {
            FreigabeLinkService.Link l = service.erzeuge(typ, objektId, teil,
                    recht == null || recht.isBlank() ? FreigabeRecht.LESEN : FreigabeRecht.von(recht), gueltigTage, zweck);
            return "OK: Link " + l.id() + " (" + l.recht() + ", gültig bis " + l.gueltigBis() + "). Adresse, nur jetzt sichtbar:\n" + l.url();
        } catch (NoSuchElementException | IllegalArgumentException e) {
            return FEHLER + e.getMessage();
        }
    }

    @PreAuthorize("hasAuthority('SCOPE_READ')")
    @McpTool(name = "list_freigabe_links", description = "Listet die Freigabe-Links des Mandats auf Objekte, die der Aufrufer "
            + "freigeben darf: Id, Typ, Objekt, Teil, Recht, Zweck, Ablauf, Aufrufe. Ohne Token. Ohne Parameter auch die "
            + "verfügbaren Objekttypen.")
    public String listFreigabeLinks(
            @McpToolParam(required = false, description = "Objekttyp, leer = alle") String typ,
            @McpToolParam(required = false, description = "Id des Objekts, leer = alle") Long objektId) {
        StringBuilder sb = new StringBuilder();
        if ((typ == null || typ.isBlank()) && objektId == null) {
            sb.append("Typen: ").append(service.quellen().stream().map(FreigabeQuelle::typ).collect(Collectors.joining(", "))).append('\n');
        }
        for (FreigabeLinkService.Link l : service.liste(typ == null || typ.isBlank() ? null : typ, objektId)) {
            sb.append("- id=").append(l.id()).append(' ').append(l.typ()).append('/').append(l.objektId())
                    .append(l.teil() == null ? "" : " teil=" + l.teil())
                    .append(" recht=").append(l.recht())
                    .append(" bis=").append(l.gueltigBis()).append(l.abgelaufen() ? " ABGELAUFEN" : "")
                    .append(" aufrufe=").append(l.aufrufe())
                    .append(l.zweck() == null ? "" : " zweck=" + l.zweck()).append('\n');
        }
        return sb.isEmpty() ? "Keine Freigabe-Links." : sb.toString();
    }

    @PreAuthorize("hasAuthority('SCOPE_WRITE')")
    @McpTool(name = "widerrufe_freigabe_link", description = "Widerruft einen Freigabe-Link sofort (Id aus list_freigabe_links).")
    public String widerrufeFreigabeLink(@McpToolParam(description = "Id des Links") Long linkId) {
        try {
            service.widerrufe(linkId);
            return "OK: Link " + linkId + " widerrufen.";
        } catch (NoSuchElementException e) {
            return FEHLER + e.getMessage();
        }
    }
}
