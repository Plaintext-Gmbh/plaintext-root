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
 * Deterministic guard against the bug class of the index 500 of 30.05.: {@code #{dashboardBean.empty}}
 * threw a Facelets {@code ParseException}, because {@code empty} is a reserved EL keyword, and thereby
 * hit the logged-in start page of every dependent app (across the root releases 1.200.0/1.201.0
 * up to the fix in 1.202.0).
 *
 * <p>This test validates EVERY EL expression ({@code #{...}} and {@code ${...}}) of all framework XHTML
 * with the Jakarta EL factory ({@link ExpressionFactory#createValueExpression} parses eagerly and throws
 * on syntax errors). An expression that cannot be parsed (e.g. a reserved keyword as a {@code .property})
 * makes the test fail with file + expression in the error text. Resolution/beans are not needed;
 * the framework XHTML contain no namespaced EL functions, so a {@link StandardELContext} suffices.
 */
class IndexXhtmlElParseTest {

    /** Captures individual EL tokens #{...} and ${...} (no '}' within an expression). */
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
        // Exactly the index-500 bug: 'empty' (a reserved EL keyword) as a .property -> not parseable.
        assertThrows(ELException.class, () -> parse("#{dashboardBean.empty}"));
    }
}
