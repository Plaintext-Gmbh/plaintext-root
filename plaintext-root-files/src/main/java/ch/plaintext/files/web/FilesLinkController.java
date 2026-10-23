/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.files.web;

import ch.plaintext.files.service.FilesService;
import ch.plaintext.linktoken.LinkToken;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Download per Freigabe-Link - {@code /nosec/files/{token}}, ohne Anmeldung (Karte 1559, Adresse wie im Inventar,
 * damit bestehende Links weiter gehen). Geschützt allein durch das Token.
 */
@RestController
@RequiredArgsConstructor
public class FilesLinkController {

    private final FilesService filesService;

    @GetMapping(FilesService.PFAD + "{token}")
    public ResponseEntity<byte[]> herunterladen(@PathVariable String token) {
        Optional<LinkToken> link = filesService.finde(token);
        byte[] inhalt = link.map(l -> filesService.inhalt(l, token)).orElse(null);
        if (inhalt == null) {
            return ResponseEntity.status(404).cacheControl(CacheControl.noStore())
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Datei nicht gefunden - der Link ist widerrufen oder die Datei abgelaufen."
                            .getBytes(StandardCharsets.UTF_8));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Robots-Tag", "noindex")
                .header("Referrer-Policy", "no-referrer")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(FilesService.datei(link.get()), StandardCharsets.UTF_8).build().toString())
                .body(inhalt);
    }
}
