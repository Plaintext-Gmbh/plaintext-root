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
 * Vollstaendigkeit der Seed ueber die <b>Sprachen</b>: zu jedem Schluessel von
 * {@code i18n/plaintext-root.csv} gibt es eine Zeile fuer jede Zielsprache, die
 * {@link I18nService#getAvailableLanguages()} neben {@code de} ausliefert.
 *
 * <p><b>Abgrenzung zu {@code PlaintextI18nSeedTest}</b> (plaintext-root-archtests): der prueft die
 * andere Achse — dass jedes {@code i18n.t('…')} eines Facelets ueberhaupt eine Seed-Zeile hat, und
 * zwar in der Leitsprache {@code en}. Ob derselbe Schluessel auch auf Franzoesisch und Italienisch
 * vorliegt, sieht er nicht. Genau das faellt sonst niemandem auf: {@code I18nService.translate()}
 * legt fuer eine fehlende Sprache still einen {@code X_}-Platzhalter an, und der Benutzer, der in
 * der Topbar auf FR schaltet, sieht {@code X_Speichern} statt {@code Enregistrer}.
 *
 * <p><b>Warum de nicht dazugehoert:</b> der Schluessel IST der deutsche Text, und
 * {@code translate()} gibt fuer {@code de} den Vorgabetext unveraendert zurueck — eine
 * {@code de}-Zeile waere wirkungslos (siehe {@link I18nSeedLinter}).
 *
 * <p><b>Begruendete Luecke eintragen:</b> Ein Schluessel, der in einer Sprache bewusst keine Zeile
 * hat, gehoert in {@link #BEGRUENDETE_LUECKEN} — mit Grund. Heute ist die Liste leer; ein
 * unbegruendetes Loch laesst diesen Test fehlschlagen.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@DisplayName("i18n-Seed plaintext-root.csv: jeder Schluessel in allen Zielsprachen")
class I18nSeedSprachabdeckungTest {

    /** Die Seed dieses Moduls — bewusst als Datei, nicht ueber den Classpath: der traegt im Test
     *  zusaetzlich {@code src/test/resources/i18n/seed-test.csv} (Fixture mit Absicht kaputten
     *  Zeilen), die hier nichts zu suchen hat. */
    private static final Path SEED = Path.of("src/main/resources/i18n/plaintext-root.csv");

    /** {@code "<Schluessel>::<Sprache>"} -> Grund, warum die Zeile fehlen darf. */
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

        // Positivkontrolle: ein leerer Scan wuerde die Pruefung unten stillschweigend bestehen.
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
     * Die {@code title}-Werte aller {@code @MenuAnnotation}-Klassen des Reactors, aus dem Quelltext
     * gelesen. Bewusst kein Classpath-Scan: die Menueklassen liegen in Modulen, von denen
     * plaintext-admin-i18n nichts weiss (plaintext-admin-cron, -oidc, …), stehen aber alle unter
     * derselben Repo-Wurzel.
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
     * Der {@code title} einer echten Annotation. Erwaehnungen in Javadoc ({@code {@link
     * MenuAnnotation}}, Beispielbloecke) scheiden aus, weil dort weder {@code link =} noch
     * {@code parent =} steht und der Block einen Javadoc-Stern enthaelt.
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
