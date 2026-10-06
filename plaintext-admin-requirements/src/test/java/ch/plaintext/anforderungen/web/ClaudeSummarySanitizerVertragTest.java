/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Karte 1360 (HB4): die Claude-Summary wird mit marked gerendert und ueber innerHTML eingehaengt.
 * Den Text schreibt jeder Automations-Token ({@code POST /nosec/api/claude/summary}), marked reicht
 * rohes HTML unveraendert durch. Bis zum 30.09.2026 ging das Ergebnis ungefiltert in innerHTML.
 *
 * <p>Der Test liest die ausgelieferten Dateien, nicht den Quellbaum: er prueft, was der Browser
 * bekommt. Das Verhalten von DOMPurify selbst prueft er nicht (das ist deren Testsuite); der
 * Nachweis mit einer echten Nutzlast steht in der Karte (Node/jsdom-Lauf).
 */
class ClaudeSummarySanitizerVertragTest {

    private static String lies(String pfad) throws IOException {
        try (InputStream in = ClaudeSummarySanitizerVertragTest.class.getResourceAsStream(pfad)) {
            assertThat(in).as("Ressource fehlt: " + pfad).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void gerendertesMarkdownGehtNurGefiltertInsInnerHtml() throws IOException {
        String js = lies("/META-INF/resources/plaintext-root/js/claudesummary.js");

        assertThat(js).contains("DOMPurify.sanitize(html")
                .as("das rohe marked-Ergebnis darf nicht direkt ins innerHTML")
                .doesNotContain("innerHTML = html")
        // fail closed: ohne DOMPurify nur Text, kein ungefiltertes HTML
                .contains("typeof DOMPurify === 'undefined'")
                .contains("pre.textContent = fallbackText");
    }

    @Test
    void domPurifyLiegtLokalUndWirdVorDemRendernGeladen() throws IOException {
        String seite = lies("/META-INF/resources/claudesummary.xhtml");
        String purify = lies("/META-INF/resources/js/purify.min.js");

        assertThat(purify).startsWith("/*! @license DOMPurify 3.4.16");
        int purifyTag = seite.indexOf("/js/purify.min.js");
        int renderSkript = seite.indexOf("js/claudesummary.js");
        assertThat(purifyTag).as("purify.min.js wird eingebunden").isPositive()
                .as("DOMPurify muss VOR claudesummary.js geladen sein")
                .isLessThan(renderSkript);
        assertThat(seite).as("lokal, kein CDN (CSP script-src 'self')").doesNotContain("cdn");
    }
}
