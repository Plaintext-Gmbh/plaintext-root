/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web.oberflaeche;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Card 1547: messages appear in the one growl of the template ({@code globalGrowl}, updated by
 * {@code p:autoUpdate} on every Ajax request). A page growl shows every message a second time and drifts in
 * position and duration, so no XHTML in this repository may carry its own {@code p:growl}.
 */
class EinGrowlProSeiteTest {

    private static final String TEMPLATE = "includes/template.xhtml";

    @Test
    void keineSeiteHatEinenEigenenGrowl() throws IOException {
        assertThat(xhtml()).as("Positivkontrolle: die Suche findet Seiten").hasSizeGreaterThan(20);
        List<String> mitGrowl = xhtml().stream()
                .filter(p -> !p.toString().replace('\\', '/').endsWith(TEMPLATE))
                .filter(p -> lies(p).contains("<p:growl"))
                .map(p -> repo().relativize(p).toString())
                .toList();
        assertThat(mitGrowl).as("Seiten mit eigenem p:growl: Meldungen zeigt der globalGrowl des Templates").isEmpty();
    }

    @Test
    void derTemplateGrowlAktualisiertSichSelbst() throws IOException {
        Path template = xhtml().stream().filter(p -> p.toString().replace('\\', '/').endsWith(TEMPLATE))
                .findFirst().orElseThrow();
        String s = lies(template);
        String growl = s.substring(s.indexOf("<p:growl id=\"globalGrowl\""), s.indexOf("</p:growl>"));
        assertThat(growl).contains("<p:autoUpdate/>");
    }

    private static List<Path> xhtml() throws IOException {
        try (Stream<Path> s = Files.walk(repo())) {
            return s.filter(p -> p.toString().endsWith(".xhtml"))
                    .filter(p -> p.toString().replace('\\', '/').contains("/src/main/resources/"))
                    .toList();
        }
    }

    /** Repository root: the topmost directory upwards that still has a pom.xml. */
    private static Path repo() {
        Path p = Paths.get("").toAbsolutePath();
        while (p.getParent() != null && Files.exists(p.getParent().resolve("pom.xml"))) {
            p = p.getParent();
        }
        return p;
    }

    private static String lies(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new IllegalStateException(p.toString(), e);
        }
    }
}
