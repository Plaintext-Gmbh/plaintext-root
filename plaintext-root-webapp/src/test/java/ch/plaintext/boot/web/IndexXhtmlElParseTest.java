/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import jakarta.el.ELContext;
import jakarta.el.ELException;
import jakarta.el.ExpressionFactory;
import jakarta.el.StandardELContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Deterministischer Guard gegen die Bug-Klasse des index-500 vom 30.05.: {@code #{dashboardBean.empty}}
 * warf eine Facelets-{@code ParseException}, weil {@code empty} ein reserviertes EL-Keyword ist, und
 * traf damit die eingeloggte Startseite jeder abhängigen App (über die root-Releases 1.200.0/1.201.0
 * bis zum Fix in 1.202.0).
 *
 * <p>Dieser Test validiert JEDEN EL-Ausdruck ({@code #{...}} und {@code ${...}}) aller Framework-XHTML
 * mit der Jakarta-EL-Factory ({@link ExpressionFactory#createValueExpression} parst eager und wirft bei
 * Syntaxfehlern). Ein nicht parsebarer Ausdruck (z. B. ein reserviertes Keyword als {@code .property})
 * lässt den Test mit Datei + Ausdruck im Fehlertext fehlschlagen. Auflösung/Beans werden nicht benötigt;
 * die Framework-XHTML enthalten keine namespaced EL-Funktionen, daher genügt eine {@link StandardELContext}.
 */
class IndexXhtmlElParseTest {

    /** Erfasst einzelne EL-Token #{...} und ${...} (kein '}' innerhalb eines Ausdrucks). */
    private static final Pattern EL = Pattern.compile("[#$]\\{[^}]*\\}");

    private static Path resourcesDir() {
        for (String candidate : List.of(
                "src/main/resources/META-INF/resources",
                "plaintext-root-webapp/src/main/resources/META-INF/resources",
                "target/classes/META-INF/resources")) {
            Path p = Path.of(candidate);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        throw new IllegalStateException(
                "META-INF/resources nicht gefunden (cwd=" + Path.of("").toAbsolutePath() + ")");
    }

    private static void parse(String expr) {
        ExpressionFactory factory = ExpressionFactory.newInstance();
        ELContext ctx = new StandardELContext(factory);
        factory.createValueExpression(ctx, expr, Object.class);
    }

    @Test
    void alleElAusdrueckeDerFrameworkXhtmlSindParsebar() throws IOException {
        Path dir = resourcesDir();
        List<String> failures = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".xhtml"))::iterator) {
                String content = Files.readString(f);
                Matcher m = EL.matcher(content);
                while (m.find()) {
                    String expr = m.group();
                    checked++;
                    try {
                        parse(expr);
                    } catch (ELException | IllegalArgumentException ex) {
                        failures.add(f.getFileName() + " : " + expr + "  ->  " + ex.getMessage());
                    }
                }
            }
        }
        assertTrue(checked > 0, "Es wurden EL-Ausdrücke in den Framework-XHTML gefunden und geprüft");
        if (!failures.isEmpty()) {
            fail("Nicht parsebare EL-Ausdrücke (Render-500-Gefahr):\n" + String.join("\n", failures));
        }
    }

    @Test
    void reserviertesKeywordAlsPropertyWirdAlsFehlerErkannt() {
        // Exakt der index-500-Bug: 'empty' (reserviertes EL-Keyword) als .property -> nicht parsebar.
        assertThrows(ELException.class, () -> parse("#{dashboardBean.empty}"));
    }
}
