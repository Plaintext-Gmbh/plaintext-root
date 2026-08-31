/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.i18n.service;

import ch.plaintext.boot.utils.I18nSeedLinter;
import ch.plaintext.boot.utils.I18nSeedLinter.Seed;
import ch.plaintext.boot.utils.I18nSeedLinter.SeedRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Completeness of the seed across the <b>languages</b>: for every key in
 * {@code i18n/plaintext-root.csv} there is a row for every target language that
 * {@link I18nService#getAvailableLanguages()} returns besides {@code de}.
 *
 * <p><b>Delimitation from {@code PlaintextI18nSeedTest}</b> (plaintext-root-archtests): that one
 * checks the other axis — that every {@code i18n.t('…')} of a facelet has a seed row at all, namely
 * in the lead language {@code en}. Whether the same key is also present in French and Italian
 * it does not see. And that is exactly what nobody else notices: {@code I18nService.translate()}
 * silently creates an {@code X_} placeholder for a missing language, and the user who switches
 * to FR in the topbar sees {@code X_Speichern} instead of {@code Enregistrer}.
 *
 * <p><b>Why de is not part of it:</b> the key IS the German text, and
 * {@code translate()} returns the default text unchanged for {@code de} — a
 * {@code de} row would have no effect (see {@link I18nSeedLinter}).
 *
 * <p><b>Recording a justified gap:</b> a key that deliberately has no row in one language
 * belongs in {@link #BEGRUENDETE_LUECKEN} — with a reason. Today the list is empty; an
 * unjustified hole makes this test fail.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@DisplayName("i18n-Seed plaintext-root.csv: jeder Schluessel in allen Zielsprachen")
class I18nSeedSprachabdeckungTest {

    /** The seed of this module — deliberately as a file, not through the classpath: in the test the
     *  latter also carries {@code src/test/resources/i18n/seed-test.csv} (a fixture with deliberately
     *  broken rows), which has no business here. */
    private static final Path SEED = Path.of("src/main/resources/i18n/plaintext-root.csv");

    /** {@code "<Schluessel>::<Sprache>"} -> reason why the row may be missing. */
    private static final Map<String, String> BEGRUENDETE_LUECKEN = Map.of();

    @Test
    @DisplayName("Zielsprachen sind en, fr, it — abgeleitet aus getAvailableLanguages() ohne de")
    void zielsprachenKommenAusDemDienstNichtAusEinemLiteral() {
        List<String> verfuegbar = new I18nService(null).getAvailableLanguages();

        assertEquals(List.of("de", "en", "fr", "it"), verfuegbar,
                "Sprachliste geaendert — diese Seed und der Sprachwechsel in der Topbar muessen zusammenpassen");
        assertEquals(List.of("en", "fr", "it"), zielsprachen());
    }

    @Test
    @DisplayName("Kein Schluessel ohne fr- oder it-Zeile (sonst X_-Platzhalter im Betrieb)")
    void jederSchluesselHatJedeZielsprache() {
        Seed seed = I18nSeedLinter.parse(SEED);
        assertTrue(seed.problems().isEmpty(), "Seed nicht lesbar: " + seed.problems());

        Map<String, Set<String>> sprachenJeSchluessel = new LinkedHashMap<>();
        for (SeedRow row : seed.rows()) {
            sprachenJeSchluessel.computeIfAbsent(row.defaultLabel(), k -> new LinkedHashSet<>())
                    .add(row.languageCode());
        }
        assertTrue(sprachenJeSchluessel.size() >= 300,
                "Seed unerwartet klein: " + sprachenJeSchluessel.size() + " Schluessel");

        List<String> fehlend = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : sprachenJeSchluessel.entrySet()) {
            for (String sprache : zielsprachen()) {
                if (!e.getValue().contains(sprache)
                        && !BEGRUENDETE_LUECKEN.containsKey(e.getKey() + "::" + sprache)) {
                    fehlend.add("'" + e.getKey() + "' (" + sprache + ")");
                }
            }
        }
        if (!fehlend.isEmpty()) {
            fail("\n=== I18N-SEED: " + fehlend.size() + " fehlende Sprachzeile(n) in " + SEED + " ===\n"
                    + "  ! " + String.join("\n  ! ", fehlend)
                    + "\nFix: Zeile '<Schluessel>;<Sprache>;<Uebersetzung>' ergaenzen, oder die Luecke in "
                    + "BEGRUENDETE_LUECKEN mit Grund eintragen.\n");
        }
    }

    @Test
    @DisplayName("Keine de-Zeile und keine unbekannte Sprache in der Seed")
    void nurZielsprachenInDerSeed() {
        Set<String> gesehen = new TreeSet<>();
        I18nSeedLinter.parse(SEED).rows().forEach(r -> gesehen.add(r.languageCode()));

        assertEquals(new TreeSet<>(zielsprachen()), gesehen,
                "de-Zeilen sind wirkungslos, fremde Sprachcodes landen in keiner Auswahl");
    }

    @Test
    @DisplayName("Kein (Schluessel, Sprache) zweimal — der Importer wuerde sonst zufaellig gewinnen")
    void keineDoppelteZeile() {
        Set<String> gesehen = new LinkedHashSet<>();
        List<String> doppelt = new ArrayList<>();
        for (SeedRow row : I18nSeedLinter.parse(SEED).rows()) {
            if (!gesehen.add(row.defaultLabel() + "::" + row.languageCode())) {
                doppelt.add("Zeile " + row.line() + ": '" + row.defaultLabel() + "' (" + row.languageCode() + ")");
            }
        }
        assertTrue(doppelt.isEmpty(), "doppelte Seed-Zeilen: " + doppelt);
    }

    @Test
    @DisplayName("Menuetitel der @MenuAnnotation-Klassen sind vorbelegt (PrimefacesMenuItem uebersetzt sie)")
    void menuetitelStehenInDerSeed() {
        Set<String> schluessel = new LinkedHashSet<>();
        I18nSeedLinter.parse(SEED).rows().forEach(r -> schluessel.add(r.defaultLabel()));
        List<String> titel = menuetitel();

        // Positive control: an empty scan would silently pass the check below.
        assertTrue(titel.size() >= 30, "Menuescan hat zu wenig gefunden: " + titel);
        assertTrue(titel.contains("Übersetzungen") && titel.contains("Mandate"),
                "Menuescan findet die bekannten Root-Eintraege nicht: " + titel);

        List<String> fehlend = titel.stream().filter(t -> !schluessel.contains(t)).toList();

        assertTrue(fehlend.isEmpty(),
                "Menuetitel ohne Seed-Zeile — im uebersetzten Menue stuende ein X_-Platzhalter: " + fehlend);
    }

    private static List<String> zielsprachen() {
        return new I18nService(null).getAvailableLanguages().stream()
                .filter(l -> !"de".equalsIgnoreCase(l))
                .toList();
    }

    /**
     * The {@code title} values of all {@code @MenuAnnotation} classes in the reactor, read from the
     * source code. Deliberately not a classpath scan: the menu classes live in modules that
     * plaintext-admin-i18n knows nothing about (plaintext-admin-cron, -oidc, …), but they all sit
     * under the same repository root.
     */
    private static List<String> menuetitel() {
        Path repoWurzel = Path.of("").toAbsolutePath().getParent();
        if (repoWurzel == null || !Files.isDirectory(repoWurzel)) {
            return List.of();
        }
        Set<String> titel = new TreeSet<>();
        try (var dateien = Files.walk(repoWurzel)) {
            dateien.filter(p -> p.toString().endsWith(".java"))
                   .filter(p -> p.toString().contains("/src/main/java/"))
                   .forEach(p -> titelAus(p, titel));
        } catch (Exception e) {
            return List.of();
        }
        return List.copyOf(titel);
    }

    /**
     * The {@code title} of a real annotation. Mentions in Javadoc ({@code {@link
     * MenuAnnotation}}, example blocks) are ruled out, because neither {@code link =} nor
     * {@code parent =} appears there and the block contains a Javadoc asterisk.
     */
    private static final java.util.regex.Pattern ANNOTATION = java.util.regex.Pattern.compile(
            "@MenuAnnotation\\s*\\(([^)]*)\\)", java.util.regex.Pattern.DOTALL);

    private static void titelAus(Path datei, Set<String> titel) {
        String inhalt;
        try {
            inhalt = Files.readString(datei);
        } catch (Exception e) {
            return;
        }
        var bloecke = ANNOTATION.matcher(inhalt);
        while (bloecke.find()) {
            String block = bloecke.group(1);
            if (block.contains("*") || !block.contains("link")) {
                continue;
            }
            var m = java.util.regex.Pattern.compile("title\\s*=\\s*\"([^\"]+)\"").matcher(block);
            if (m.find()) {
                titel.add(m.group(1));
            }
        }
    }
}
