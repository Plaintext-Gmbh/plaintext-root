/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guard for the {@code pt:} taglib from {@code plaintext-root-template} (card 984).
 *
 * <p><b>Why this test exists.</b> The taglib consists of two parts that know nothing about each
 * other: the declaration in {@code META-INF/plaintext.taglib.xml} and the tag files in
 * {@code META-INF/tags/}. If one of them drifts apart — a tag declared but the file
 * renamed — no compiler notices. The defect only appears when somebody opens the page,
 * and then as an HTTP 500.</p>
 *
 * <p><b>The second rule here was dearly bought.</b> It records a pitfall that was measured on
 * rendered HTML on 30.08.2026 and that you cannot see by reading the code:</p>
 *
 * <p><b>A tag file inherits the attributes of the surrounding tag file.</b> If somebody calls
 * {@code pt:colText} without {@code rendered} inside a {@code pt:table} that carries a
 * {@code rendered}, the column sees the {@code rendered} of the table. In the pilot run every
 * single table cell inherited the {@code styleClass} of the table this way
 * ({@code class="token-table"} on 12 of 12 cells). That is why optional attributes of the
 * inner tags must <b>not</b> be named like attributes of {@code pt:table} — they carry a
 * prefix ({@code colClass}, {@code colRendered}, {@code btnClass}, …). Mandatory attributes are
 * uncritical, because they are always set and therefore never inherit.</p>
 *
 * <p>The third pitfall — {@code var="#{var}"} on a {@code p:dataTable} throws
 * {@code IllegalArgumentException} in {@code UIData.setValueExpression} — cannot be checked as a
 * rule, because it affects only a single place. It stands as a warning in the header of
 * {@code tags/table.xhtml}.</p>
 *
 * <p>The test also runs in the consumer via {@code dependenciesToScan}; reactors without a taglib
 * pass it (there is then simply nothing to check).</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextTaglibVertragTest {

    private static final String RESOURCES_SUFFIX = "src/main/resources";

    /** {@code <tag-name>x</tag-name>} and {@code <source>tags/x.xhtml</source>} from the declaration. */
    private static final Pattern TAG_NAME = Pattern.compile("<tag-name>\\s*([^<\\s]+)\\s*</tag-name>");
    private static final Pattern SOURCE = Pattern.compile("<source>\\s*([^<\\s]+)\\s*</source>");

    /**
     * Attributes that {@code pt:table} itself carries and that therefore must not appear in an inner
     * tag file as an OPTIONAL attribute — otherwise they inherit silently.
     * {@code value} deliberately is NOT in this list: it is mandatory everywhere and always set.
     */
    private static final Set<String> ERBENDE_ATTRIBUTE =
            Set.of("rendered", "styleClass", "paginator", "rows", "emptyMessage", "widgetVar", "reflow");

    /** Tag files that span the table themselves — the inheritance rule does not apply to them. */
    private static final Set<String> TABELLEN_TAGS = Set.of("table.xhtml");

    private static List<Path> taglibDeklarationen() {
        List<Path> gefunden = new ArrayList<>();
        for (Path root : ReactorLayout.sourceRoots(RESOURCES_SUFFIX)) {
            Path deklaration = root.resolve("META-INF/plaintext.taglib.xml");
            if (Files.isRegularFile(deklaration)) {
                gefunden.add(deklaration);
            }
        }
        return gefunden;
    }

    private static String lies(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("Jedes deklarierte pt:-Tag hat seine Datei — und jede Datei ist deklariert")
    void deklarationUndDateienStimmenUeberein() {
        List<String> fehler = new ArrayList<>();
        for (Path deklaration : taglibDeklarationen()) {
            String xml = lies(deklaration);
            Path verzeichnis = deklaration.getParent();

            Set<String> deklariert = new LinkedHashSet<>();
            Matcher m = SOURCE.matcher(xml);
            while (m.find()) {
                String quelle = m.group(1);
                deklariert.add(quelle);
                if (!Files.isRegularFile(verzeichnis.resolve(quelle))) {
                    fehler.add(ReactorLayout.relativ(deklaration) + ": <source>" + quelle
                            + "</source> zeigt auf keine Datei — die Seite endet zur Laufzeit in HTTP 500");
                }
            }

            Path tags = verzeichnis.resolve("tags");
            if (Files.isDirectory(tags)) {
                try (Stream<Path> dateien = Files.list(tags)) {
                    dateien.filter(p -> p.getFileName().toString().endsWith(".xhtml"))
                            .forEach(p -> {
                                String relativ = "tags/" + p.getFileName();
                                if (!deklariert.contains(relativ)) {
                                    fehler.add(ReactorLayout.relativ(p) + ": Tag-File ohne Eintrag in "
                                            + "plaintext.taglib.xml — es ist in keiner Seite benutzbar");
                                }
                            });
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            // Counter-check: without it the test would be green even if the regex found nothing.
            long tagNamen = TAG_NAME.matcher(xml).results().count();
            if (tagNamen == 0) {
                fehler.add(ReactorLayout.relativ(deklaration) + ": kein einziges <tag-name> gefunden — "
                        + "entweder ist die Datei leer oder das Format hat sich geaendert");
            }
        }
        assertTrue(fehler.isEmpty(), "pt:-Taglib ist nicht schluessig:\n  " + String.join("\n  ", fehler));
    }

    @Test
    @DisplayName("Innere Tag-Files erben keine Attribute von pt:table")
    void innereTagsVerwendenKeineErbendenAttributnamen() {
        List<String> fehler = new ArrayList<>();
        for (Path deklaration : taglibDeklarationen()) {
            Path tags = deklaration.getParent().resolve("tags");
            if (!Files.isDirectory(tags)) {
                continue;
            }
            try (Stream<Path> dateien = Files.list(tags)) {
                for (Path datei : dateien.filter(p -> p.toString().endsWith(".xhtml")).toList()) {
                    if (TABELLEN_TAGS.contains(datei.getFileName().toString())) {
                        continue;
                    }
                    String inhalt = lies(datei);
                    for (String attribut : ERBENDE_ATTRIBUTE) {
                        // What we look for is the USE as a parameter of its own: #{attribut} or
                        // #{empty attribut ...}. An XML attribute of the same name (styleClass="#{colClass}")
                        // is precisely the right way and must not trip the rule.
                        Pattern verwendung = Pattern.compile("#\\{\\s*(empty\\s+)?" + attribut + "\\b");
                        if (verwendung.matcher(inhalt).find()) {
                            fehler.add(ReactorLayout.relativ(datei) + ": benutzt #{" + attribut + "} als "
                                    + "eigenen Parameter. Dieser Name gehoert pt:table — wird das Attribut "
                                    + "am inneren Tag nicht gesetzt, erbt es still den Wert der Tabelle. "
                                    + "Ein Praefix loest es (z.B. col" + Character.toUpperCase(attribut.charAt(0))
                                    + attribut.substring(1) + ").");
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        assertTrue(fehler.isEmpty(),
                "Attributnamen kollidieren mit pt:table — am gerenderten HTML gemessen, 30.08.2026:\n  "
                        + String.join("\n  ", fehler));
    }
}
