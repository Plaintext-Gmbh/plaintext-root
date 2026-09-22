/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Positive control for the lower bound itself and contract over the file linters that carry it.
 *
 * <p>Seven of the shared linters used to leave their test method with a bare {@code return} as soon
 * as their scan set was empty (card 1274, finding 4). That is the same shape as
 * {@code mindestens.werkzeuge} in the MCP scope contract: a rule of the form "everything found is in
 * order" stays green when nothing is found any more. It never went off, because all six reactors
 * always carried resource and source directories — the finding was latent, not active, and it would
 * have gone active silently on the next module rename or nesting change.
 *
 * <p>Two things are pinned here. First that {@link ReactorLayout#untergrenze} really bites: a scan
 * set below the bound has to end red. Without that proof the change would only show that the tests
 * are still green, which they were before. Second that no linter quietly returns to the old shape:
 * every class listed here has to call the bound.
 */
class ReactorLayoutUntergrenzeTest {

    /**
     * The seven linters from finding 4 of card 1274, plus the two that card 1294 moved onto
     * {@link ReactorLayout#sourceRoots(String)} on 22.09.2026. Whoever adds a tenth file linter
     * that scans {@code sourceRoots} belongs in this list.
     */
    private static final List<String> LINTER_MIT_UNTERGRENZE = List.of(
            "PlaintextFaceletsElLinterTest",
            "PlaintextJsfViewLinterTest",
            "PlaintextInlineJsVertragTest",
            "PlaintextMobileFormLinterTest",
            "PlaintextI18nSeedTest",
            "PlaintextHeaderHygieneTest",
            "PlaintextGroessenLeitplankeTest",
            // Karte 1294: bis dahin mit eigener Pfadsuche und ohne Untergrenze.
            "PlaintextViewScopedBanTest",
            "PlaintextPrivateKeyBanTest");

    private static final Path QUELLEN = Path.of("src/main/java/ch/plaintext/arch");

    @Test
    @DisplayName("Positivkontrolle: eine zu kleine Scanmenge faellt durch")
    void zuKleineScanmengeFaelltDurch() {
        List<Path> eineWurzel = List.of(Path.of("src/main/resources"));

        AssertionError fehler = assertThrows(AssertionError.class,
                () -> ReactorLayout.untergrenze(eineWurzel, 2, "src/main/resources"),
                "Eine Untergrenze, die eine einzelne Wurzel durchlaesst, prueft nichts. Genau eine "
                        + "Wurzel ist der Fall 'repoRoot() lieferte null' — dann sieht der Linter nur sein "
                        + "eigenes Modul.");

        assertTrue(fehler.getMessage().contains("festgehalten sind mindestens 2"),
                "Die Meldung muss die festgehaltene Zahl nennen, sonst weiss niemand, was zu tun ist: "
                        + fehler.getMessage());
    }

    @Test
    @DisplayName("Positivkontrolle: eine leere Scanmenge faellt durch")
    void leereScanmengeFaelltDurch() {
        assertThrows(AssertionError.class,
                () -> ReactorLayout.untergrenze(List.of(), 2, "src/main/resources"),
                "Der Fall, mit dem der Befund anfing: gar keine Wurzel und trotzdem gruen.");
    }

    @Test
    @DisplayName("Eine ausreichende Scanmenge bleibt gruen")
    void ausreichendeScanmengeBleibtGruen() {
        List<Path> zweiWurzeln = List.of(Path.of("a/src/main/resources"), Path.of("b/src/main/resources"));
        assertDoesNotThrow(() -> ReactorLayout.untergrenze(zweiWurzeln, 2, "src/main/resources"),
                "Eine Untergrenze, die auch den Normalfall rot faerbt, ist keine Grenze, sondern ein Defekt.");
    }

    @Test
    @DisplayName("Die neun Linter rufen die Untergrenze und kehren nicht mehr still zurueck")
    void jederLinterRuftDieUntergrenze() throws IOException {
        assertTrue(Files.isDirectory(QUELLEN),
                "Die Linter-Quellen liegen nicht unter " + QUELLEN.toAbsolutePath()
                        + " — ohne sie prueft dieser Vertrag nichts.");

        List<String> maengel = new ArrayList<>();
        for (String klasse : LINTER_MIT_UNTERGRENZE) {
            Path quelle = QUELLEN.resolve(klasse + ".java");
            if (!Files.isRegularFile(quelle)) {
                maengel.add(klasse + ": Quelldatei nicht gefunden (" + quelle + ")");
                continue;
            }
            String text = Files.readString(quelle);
            if (!text.contains("ReactorLayout.untergrenze(")) {
                maengel.add(klasse + ": ruft ReactorLayout.untergrenze(...) nicht auf");
            }
            if (text.contains("Roots.isEmpty()) {") || text.contains("roots.isEmpty()) {")) {
                maengel.add(klasse + ": traegt wieder einen Zweig auf die leere Scanmenge — "
                        + "der stille Rueckweg ist zurueck");
            }
        }

        if (!maengel.isEmpty()) {
            fail("\n\n=== STILLER RUECKWEG IN EINEM DATEI-LINTER ===\n  ! "
                    + String.join("\n  ! ", maengel)
                    + "\n\nEin Linter, dessen Scanmenge leer sein darf, meldet 'alles in Ordnung', ohne "
                    + "etwas gelesen zu haben. Statt 'if (roots.isEmpty()) return;' gehoert dorthin "
                    + "ReactorLayout.untergrenze(roots, MINDESTENS_SCANWURZELN, <suffix>) mit einer "
                    + "gemessenen Zahl (Karte 1274, Befund 4).\n");
        }
    }
}
