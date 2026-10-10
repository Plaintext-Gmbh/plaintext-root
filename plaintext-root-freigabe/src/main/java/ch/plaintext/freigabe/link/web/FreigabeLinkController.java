/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.freigabe.link.web;

import ch.plaintext.freigabe.FreigabeInhalt;
import ch.plaintext.freigabe.link.service.FreigabeLinkService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Die Adresse hinter einem Freigabe-Link (Karte 1476), ohne Anmeldung unter {@code /nosec}.
 *
 * <ul>
 *   <li><b>GET</b> zeigt nur ({@link FreigabeLinkService#zeige}); Link-Scanner und Vorschauen ändern nichts.</li>
 *   <li><b>POST</b> schreibt, nur mit einem {@code rw}-Link; ein {@code r}-Link bekommt 403. {@code /nosec} ist vom
 *       CSRF-Filter ausgenommen. Das ist hier richtig: einziger Ausweis ist das Token in der Adresse, die Sitzung
 *       zählt nicht (das Modul läuft mit leerem SecurityContext). Eine fremde Seite, die das Token nicht kennt, hat
 *       also nichts, was sie mitschicken könnte. Der Inhalt ist auf {@code plaintext.freigabe.max-bytes} begrenzt.</li>
 *   <li>Unbekannt, abgelaufen, widerrufen und nicht darstellbar sehen gleich aus (404).</li>
 *   <li>Jede Antwort: CSP des Moduls oder {@link FreigabeInhalt#STRENG}, kein Cache, kein Referer (das Token steht in
 *       der Adresse), noindex, nosniff.</li>
 * </ul>
 */
@RestController
public class FreigabeLinkController {

    static final String UNGUELTIG = "Dieser Link ist ungültig, abgelaufen oder widerrufen.";

    private final FreigabeLinkService links;
    private final int maxBytes;

    public FreigabeLinkController(FreigabeLinkService links,
                                  @Value("${plaintext.freigabe.max-bytes:5242880}") int maxBytes) {
        this.links = links;
        this.maxBytes = maxBytes;
    }

    @GetMapping(FreigabeLinkService.PFAD + "{token}")
    public ResponseEntity<byte[]> zeige(@PathVariable String token) {
        return antwort(links.zeige(token));
    }

    @PostMapping(FreigabeLinkService.PFAD + "{token}")
    public ResponseEntity<byte[]> schreibe(@PathVariable String token, HttpServletRequest request) throws IOException {
        byte[] inhalt;
        try (InputStream in = request.getInputStream()) {
            inhalt = in.readNBytes(maxBytes + 1);
        }
        if (inhalt.length > maxBytes) {
            return text(HttpStatus.CONTENT_TOO_LARGE, "Der Inhalt ist zu gross.");
        }
        try {
            return antwort(links.schreibe(token, request.getContentType(), inhalt));
        } catch (FreigabeLinkService.NurLesen e) {
            return text(HttpStatus.FORBIDDEN, e.getMessage());
        }
    }

    private static ResponseEntity<byte[]> antwort(Optional<FreigabeInhalt> inhalt) {
        if (inhalt.isEmpty()) {
            return text(HttpStatus.NOT_FOUND, UNGUELTIG);
        }
        FreigabeInhalt i = inhalt.get();
        return ResponseEntity.ok().headers(koepfe(i.csp())).contentType(MediaType.parseMediaType(i.contentType())).body(i.inhalt());
    }

    private static ResponseEntity<byte[]> text(HttpStatus status, String text) {
        return ResponseEntity.status(status).headers(koepfe(null))
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .body(text.getBytes(StandardCharsets.UTF_8));
    }

    static HttpHeaders koepfe(String csp) {
        HttpHeaders h = new HttpHeaders();
        h.setCacheControl(CacheControl.noStore());
        h.set("Content-Security-Policy", csp == null || csp.isBlank() ? FreigabeInhalt.STRENG : csp);
        h.set("Referrer-Policy", "no-referrer");
        h.set("X-Robots-Tag", "noindex, nofollow");
        h.set("X-Content-Type-Options", "nosniff");
        return h;
    }
}
