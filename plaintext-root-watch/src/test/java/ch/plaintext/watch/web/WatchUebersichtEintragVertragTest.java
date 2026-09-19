/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Card 1260: every {@code #{e.…}} on the overview page must be a <b>record component</b> of
 * {@link WatchUebersichtBean.Eintrag}.
 *
 * <h2>The fault this pins, measured on 19.09.2026</h2>
 *
 * <p>{@code Eintrag} carried a convenience method {@code getSchalterText()} and the page read
 * {@code #{e.schalterText}}. In Java that is an ordinary, callable getter — every unit test was
 * green. In EL it is nothing: a record is resolved by {@code RecordELResolver}, which knows
 * <b>only the components</b> of the record. The page answered:</p>
 *
 * <pre>
 * jakarta.el.PropertyNotFoundException: The class '…$Eintrag'
 *     does not have a readable property 'schalterText'   →  HTTP 500
 * </pre>
 *
 * <p>Found by the page walkthrough ({@code AllPagesSmokePlaywrightIT}), a ten-minute browser run
 * in a pipeline that does not run on every change. This test is the same statement for a tenth
 * of a second, and it fails at the place the fault is made.</p>
 *
 * <p><b>It reads the shipped page, not a copy.</b> A list of expressions maintained here would
 * go stale the first time somebody edits the view — which is exactly the moment it has to
 * speak.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class WatchUebersichtEintragVertragTest {

    private static final Path SEITE =
            Path.of("src/main/resources/META-INF/resources/watch/elemente.xhtml");

    /** {@code #{e.name}} and {@code #{e.name ? … }} — the loop variable of the switch list. */
    private static final Pattern AUSDRUCK = Pattern.compile("#\\{\\s*e\\.([A-Za-z_][A-Za-z0-9_]*)");

    private static Set<String> ausdrueckeInDerSeite() throws IOException {
        String inhalt = Files.readString(SEITE);
        Set<String> gefunden = new LinkedHashSet<>();
        Matcher m = AUSDRUCK.matcher(inhalt);
        while (m.find()) {
            gefunden.add(m.group(1));
        }
        return gefunden;
    }

    private static Set<String> komponenten() {
        return Arrays.stream(WatchUebersichtBean.Eintrag.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    @Test
    @DisplayName("Jeder #{e.…}-Ausdruck der Seite ist eine Record-Komponente")
    void jederAusdruckIstEineKomponente() throws IOException {
        Set<String> inDerSeite = ausdrueckeInDerSeite();
        Set<String> komponenten = komponenten();

        assertFalse(inDerSeite.isEmpty(),
                "Kein #{e.…} in " + SEITE + " gefunden — dann prueft dieser Test nichts mehr; "
                        + "wurde die Schleifenvariable umbenannt?");
        for (String name : inDerSeite) {
            assertTrue(komponenten.contains(name),
                    "#{e." + name + "} in " + SEITE + " ist KEINE Komponente von Eintrag "
                            + komponenten + ". EL loest einen Record ueber RecordELResolver auf, "
                            + "und der kennt nur die Komponenten — ein zusaetzliches "
                            + "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1)
                            + "() ist dort unsichtbar und die Seite antwortet mit HTTP 500.");
        }
    }

    @Test
    @DisplayName("GEGENPROBE: ein erfundener Name faellt durch dieselbe Pruefung")
    void gegenprobeErfundenerName() {
        // Ohne diese Messung belegt der Test oben nichts — eine Pruefung, die jeden Namen
        // durchliesse, waere dort ebenso gruen.
        assertFalse(komponenten().contains("schalterText"),
                "genau dieser Name hat am 19.09.2026 die Seite auf HTTP 500 gelegt");
    }

    @Test
    @DisplayName("Eintrag ist ein Record — sonst gilt die Begruendung dieses Tests nicht mehr")
    void eintragIstEinRecord() {
        assertTrue(WatchUebersichtBean.Eintrag.class.isRecord(),
                "Wird daraus eine gewoehnliche Klasse, greifen wieder die Bean-Getter und diese "
                        + "Pruefung ist zu streng — dann gehoert sie weg, nicht aufgeweicht.");
        assertEquals(Set.of("id", "titel", "aktiv"), komponenten());
    }
}
