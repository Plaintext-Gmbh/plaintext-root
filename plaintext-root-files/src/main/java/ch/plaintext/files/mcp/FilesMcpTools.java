/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.files.mcp;

import ch.plaintext.files.config.FilesProperties;
import ch.plaintext.files.service.Datei;
import ch.plaintext.files.service.FilesService;
import ch.plaintext.linktoken.LinkToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * MCP-Werkzeuge {@code list_files}, {@code get_file}, {@code save_file} und {@code create_file_link} auf die
 * persönliche Ablage des Token-Inhabers (Karte 1559, aus dem Inventar {@code FilesMcpController}). Lesen mit
 * {@code SCOPE_READ}, schreiben mit {@code SCOPE_WRITE}.
 *
 * <p><b>Wer bist du?</b> Ist {@code plaintext.files.mcp-identitaet} gesetzt, fragt die KI vor jedem Zugriff nach dem
 * Namen und gibt die Antwort in {@code wer} weiter; nur dieser Name kommt durch. Das ersetzt keine Anmeldung (die
 * leistet das Token am {@code /mcp}), es hält die KI davon ab, die Ablage für jemand anderen zu öffnen, der zufällig
 * am selben Client sitzt. Im Inventar war der Name fest verdrahtet.</p>
 *
 * <p>Abweichung vom Inventar: dort REST unter {@code /nosec/api/mcp/files} hinter dem Inventar-MCP; in root gibt es
 * die MCP-Werkzeuge direkt (wie {@code FreigabeLinkMcpTools}), {@code /nosec} wäre hier ohne Anmeldung.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnClass(name = "org.springframework.ai.mcp.annotation.McpTool")
public class FilesMcpTools {

    /** Bis zu dieser Grösse kommt der Inhalt in der Antwort mit. */
    static final int MAX_INLINE = 1024 * 1024;
    private static final String FEHLER = "FEHLER: ";

    private final FilesService filesService;
    private final FilesProperties properties;

    @PreAuthorize("hasAuthority('SCOPE_READ')")
    @McpTool(name = "list_files", description = "Listet die eigenen Dateien der persönlichen Ablage (Files) mit Grösse, "
            + "Änderung und Ablauf. Verlangt die Einstellung eine Identität, vorher den Benutzer 'Wer bist du?' fragen und "
            + "die Antwort in 'wer' übergeben.")
    public String listFiles(@McpToolParam(required = false, description = "Antwort auf 'Wer bist du?'") String wer) {
        return mitIdentitaet(wer, benutzer -> {
            StringBuilder sb = new StringBuilder();
            for (Datei d : filesService.liste(benutzer)) {
                sb.append("- ").append(d.name()).append(" bytes=").append(d.groesse())
                        .append(" geaendert=").append(d.geaendert()).append(" ablauf=").append(d.ablauf()).append('\n');
            }
            return sb.isEmpty() ? "Keine Dateien." : sb.toString();
        });
    }

    @PreAuthorize("hasAuthority('SCOPE_READ')")
    @McpTool(name = "get_file", description = "Liest eine eigene Datei: UTF-8-Text direkt, sonst Base64; über 1 MB nur "
            + "die Grösse (dann create_file_link).")
    public String getFile(@McpToolParam(required = false, description = "Antwort auf 'Wer bist du?'") String wer,
                          @McpToolParam(description = "Dateiname aus list_files") String name) {
        return mitIdentitaet(wer, benutzer -> {
            byte[] inhalt = filesService.herunterladen(benutzer, name);
            if (inhalt == null) {
                return FEHLER + "Datei '" + name + "' gibt es nicht (mehr) - list_files nennt die vorhandenen.";
            }
            if (inhalt.length > MAX_INLINE) {
                return "OK: " + name + " hat " + inhalt.length + " Bytes - grösser als 1 MB, Inhalt nicht mitgeliefert; "
                        + "create_file_link erzeugt einen Download-Link.";
            }
            String text = alsText(inhalt);
            return text != null ? text : "BASE64:" + Base64.getEncoder().encodeToString(inhalt);
        });
    }

    @PreAuthorize("hasAuthority('SCOPE_WRITE')")
    @McpTool(name = "save_file", description = "Legt eine Datei in der eigenen Ablage ab oder ersetzt sie (Text oder "
            + "Base64); die Ablauffrist beginnt neu.")
    public String saveFile(@McpToolParam(required = false, description = "Antwort auf 'Wer bist du?'") String wer,
                           @McpToolParam(description = "Dateiname") String name,
                           @McpToolParam(description = "Inhalt") String inhalt,
                           @McpToolParam(required = false, description = "true, wenn der Inhalt Base64 ist") Boolean base64) {
        return mitIdentitaet(wer, benutzer -> {
            byte[] bytes = Boolean.TRUE.equals(base64) ? Base64.getMimeDecoder().decode(inhalt)
                    : (inhalt == null ? "" : inhalt).getBytes(StandardCharsets.UTF_8);
            String gespeichert = filesService.hochladen(benutzer, name, bytes);
            return "OK: " + gespeichert + " abgelegt (" + bytes.length + " Bytes); wird "
                    + filesService.frist().toDays() + " Tage nach der letzten Änderung automatisch gelöscht.";
        });
    }

    @PreAuthorize("hasAuthority('SCOPE_WRITE')")
    @McpTool(name = "create_file_link", description = "Lese-Link ohne Anmeldung auf eine eigene Datei, gültig solange "
            + "die Datei lebt. Ein bestehender Link wird wiederverwendet, wenn der Speicher sein Token kennt.")
    public String createFileLink(@McpToolParam(required = false, description = "Antwort auf 'Wer bist du?'") String wer,
                                 @McpToolParam(description = "Dateiname aus list_files") String name) {
        return mitIdentitaet(wer, benutzer -> {
            if (filesService.liste(benutzer).stream().noneMatch(d -> d.name().equals(name))) {
                return FEHLER + "Datei '" + name + "' gibt es nicht - list_files nennt die vorhandenen.";
            }
            LinkToken link = filesService.links(benutzer, name).stream()
                    .filter(l -> l.token() != null && l.gueltigBis() == null).findFirst()
                    .orElseGet(() -> filesService.linkErstellen(benutzer, name));
            return "OK: " + filesService.url(link.token())
                    + "\nLädt ohne Anmeldung herunter; gilt bis die Datei abläuft.";
        });
    }

    interface Aktion {
        String ausfuehren(String benutzer);
    }

    String mitIdentitaet(String wer, Aktion aktion) {
        if (!erlaubt(properties.getMcpIdentitaet(), wer)) {
            log.warn("[FilesMcp] Zugriff verweigert | wer={}", wer);
            return FEHLER + "Zugriff verweigert. Frage den Benutzer 'Wer bist du?' und übergib seine Antwort in 'wer'.";
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        try {
            return aktion.ausfuehren(auth == null ? "" : auth.getName());
        } catch (RuntimeException e) {
            return FEHLER + e.getMessage();
        }
    }

    /** Ohne eingestellte Identität kommt jeder durch; sonst nur genau dieser Name, ohne Gross/Klein und Leerraum. */
    static boolean erlaubt(String identitaet, String wer) {
        if (identitaet == null || identitaet.isBlank()) {
            return true;
        }
        return wer != null && normal(identitaet).equals(normal(wer));
    }

    private static String normal(String s) {
        return s.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** UTF-8-Text ohne Nullbytes, sonst {@code null} (dann Base64). */
    static String alsText(byte[] inhalt) {
        for (byte b : inhalt) {
            if (b == 0) {
                return null;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(inhalt)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
