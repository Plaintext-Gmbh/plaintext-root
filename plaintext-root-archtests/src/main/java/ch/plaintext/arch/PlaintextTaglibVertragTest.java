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
 * Waechter fuer die {@code pt:}-Taglib aus {@code plaintext-root-template} (Karte 984).
 *
 * <p><b>Warum es diesen Test gibt.</b> Die Taglib besteht aus zwei Teilen, die nichts voneinander
 * wissen: der Deklaration in {@code META-INF/plaintext.taglib.xml} und den Tag-Files in
 * {@code META-INF/tags/}. Faellt eines auseinander — ein Tag deklariert, aber die Datei
 * umbenannt —, merkt das kein Compiler. Der Fehler erscheint erst, wenn jemand die Seite
 * aufruft, und dann als HTTP 500.</p>
 *
 * <p><b>Die zweite Regel hier ist teuer erkauft.</b> Sie haelt einen Fallstrick fest, der am
 * 30.08.2026 an gerendertem HTML gemessen wurde und den man beim Lesen des Codes nicht sieht:</p>
 *
 * <p><b>Ein Tag-File erbt die Attribute des umgebenden Tag-Files.</b> Ruft jemand
 * {@code pt:colText} ohne {@code rendered} innerhalb einer {@code pt:table} auf, die ein
 * {@code rendered} traegt, sieht die Spalte das {@code rendered} der Tabelle. Im Pilotversuch
 * erbte auf diese Weise jede einzelne Tabellenzelle die {@code styleClass} der Tabelle
 * ({@code class="token-table"} an 12 von 12 Zellen). Deshalb duerfen optionale Attribute der
 * inneren Tags <b>nicht</b> so heissen wie Attribute von {@code pt:table} — sie tragen ein
 * Praefix ({@code colClass}, {@code colRendered}, {@code btnClass}, …). Pflichtattribute sind
 * unkritisch, weil sie immer gesetzt werden und darum nie erben.</p>
 *
 * <p>Der dritte Fallstrick — {@code var="#{var}"} an einer {@code p:dataTable} wirft
 * {@code IllegalArgumentException} in {@code UIData.setValueExpression} — laesst sich nicht als
 * Regel pruefen, weil er nur eine einzige Stelle betrifft. Er steht als Warnung im Kopf von
 * {@code tags/table.xhtml}.</p>
 *
 * <p>Der Test laeuft im Consumer ueber {@code dependenciesToScan} mit; Reactoren ohne Taglib
 * bestehen ihn (dann ist schlicht nichts zu pruefen).</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextTaglibVertragTest {

    private static final String RESOURCES_SUFFIX = "src/main/resources";

    /** {@code <tag-name>x</tag-name>} und {@code <source>tags/x.xhtml</source>} aus der Deklaration. */
    private static final Pattern TAG_NAME = Pattern.compile("<tag-name>\\s*([^<\\s]+)\\s*</tag-name>");
    private static final Pattern SOURCE = Pattern.compile("<source>\\s*([^<\\s]+)\\s*</source>");

    /**
     * Attribute, die {@code pt:table} selbst fuehrt und die deshalb in einem inneren Tag-File
     * nicht als OPTIONALES Attribut auftauchen duerfen — sonst erben sie stillschweigend.
     * {@code value} steht bewusst NICHT hier: es ist ueberall Pflicht und wird immer gesetzt.
     */
    private static final Set<String> ERBENDE_ATTRIBUTE =
            Set.of("rendered", "styleClass", "paginator", "rows", "emptyMessage", "widgetVar", "reflow");

    /** Tag-Files, die selbst die Tabelle aufspannen — fuer sie gilt die Erb-Regel nicht. */
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

            // Gegenprobe: Ohne sie waere der Test auch dann gruen, wenn die Regex nichts findet.
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
                        // Gesucht ist die VERWENDUNG als eigener Parameter: #{attribut} oder
                        // #{empty attribut ...}. Ein gleichnamiges XML-Attribut (styleClass="#{colClass}")
                        // ist gerade der richtige Weg und darf nicht anschlagen.
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
