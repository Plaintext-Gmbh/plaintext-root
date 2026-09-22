/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Positivkontrolle zu Karte 1294 (aus 1274, Befund 4): belegt an einem bewusst
 * <b>verschachtelten</b> Testmodul, dass die Umstellung der Linter auf {@link ReactorLayout}
 * keine Geschmacksfrage war.
 *
 * <p>Warum dieser Test existiert: {@code PlaintextViewScopedBanTest} und
 * {@code PlaintextMobileFormLinterTest} trugen bis zum 22.09.2026 eine eigene <b>flache</b>
 * Pfadsuche ({@code Files.list(repoRoot)}). Am 20.09.2026 gemessen lieferten flache und rekursive
 * Suche in allen sechs Repos dieselbe Zahl — aber das ist ein Zufall der heute flachen
 * Modulstruktur und kein Beleg für Gleichwertigkeit. Ein Test, der nur zeigt, dass nach der
 * Umstellung alles grün ist, belegt genau nichts. Dieser hier zeigt den Unterschied: dieselbe
 * Verzeichnisstruktur, zwei Suchen, zwei Ergebnisse.
 *
 * <p>Die flache Variante steht absichtlich als Kopie im Test und nicht mehr im Produktivcode: sie
 * ist der historische Zustand, gegen den gemessen wird. Verschwindet sie hier, verschwindet der
 * Beleg.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class FlachGegenRekursivTest {

    private static final String SUFFIX = "src/main/resources/META-INF/resources";

    /** Der historische Zustand: nur die direkten Kinder der Reaktorwurzel. */
    private static List<Path> flach(Path repoRoot) throws IOException {
        List<Path> roots = new ArrayList<>();
        try (Stream<Path> modules = Files.list(repoRoot)) {
            modules.filter(Files::isDirectory)
                   .map(m -> m.resolve(SUFFIX))
                   .filter(Files::isDirectory)
                   .forEach(roots::add);
        }
        return roots;
    }

    @Test
    void dieRekursiveSucheFindetDasVerschachtelteModul_dieFlacheFandEsNicht(@TempDir Path reaktor)
            throws IOException {
        Path flachesModul = reaktor.resolve("modul-a/" + SUFFIX);
        Path tiefesModul = reaktor.resolve("gruppe/modul-b/" + SUFFIX);
        Files.createDirectories(flachesModul);
        Files.createDirectories(tiefesModul);

        List<Path> flacheTreffer = flach(reaktor);
        List<Path> rekursiveTreffer = new ArrayList<>();
        ReactorLayout.collect(reaktor, SUFFIX, rekursiveTreffer, 0);

        // Beide finden das Modul auf erster Ebene - ohne das waere der Vergleich wertlos,
        // weil eine Suche, die gar nichts findet, keinen Unterschied belegt.
        assertTrue(flacheTreffer.contains(flachesModul),
                "Positivkontrolle: die flache Suche muss das Modul auf erster Ebene finden, sonst "
                        + "misst dieser Test nur eine kaputte Suche. Gefunden: " + flacheTreffer);
        assertTrue(rekursiveTreffer.contains(flachesModul),
                "Die rekursive Suche muss das Modul auf erster Ebene ebenfalls finden: " + rekursiveTreffer);

        // Und hier laufen sie auseinander.
        assertFalse(flacheTreffer.contains(tiefesModul),
                "Der Befund: die flache Suche sieht gruppe/modul-b NICHT. Gefunden: " + flacheTreffer);
        assertTrue(rekursiveTreffer.contains(tiefesModul),
                "ReactorLayout muss gruppe/modul-b finden - genau deshalb ist die Umstellung keine "
                        + "Kosmetik. Gefunden: " + rekursiveTreffer);
    }

    /**
     * Die zweite Haelfte des Befundes: ein Linter mit flacher Suche ist ueber dem
     * verschachtelten Modul <b>gruen, weil er es nicht kennt</b> — nicht, weil es sauber ist.
     */
    @Test
    void einVerstossImVerschachteltenModulBleibtDerFlachenSucheVerborgen(@TempDir Path reaktor)
            throws IOException {
        Path tief = reaktor.resolve("gruppe/modul-b/" + SUFFIX);
        Files.createDirectories(tief);
        Files.writeString(tief.resolve("seite.xhtml"), "<h:form><input autofocus/></h:form>");
        Files.createDirectories(reaktor.resolve("modul-a/" + SUFFIX));

        assertFalse(flach(reaktor).stream().anyMatch(p -> p.startsWith(reaktor.resolve("gruppe"))),
                "Die flache Suche darf den Verstoss gar nicht erst zu Gesicht bekommen.");

        List<Path> rekursiv = new ArrayList<>();
        ReactorLayout.collect(reaktor, SUFFIX, rekursiv, 0);
        assertTrue(rekursiv.stream().anyMatch(p -> Files.isRegularFile(p.resolve("seite.xhtml"))),
                "ReactorLayout muss die Datei mit dem Verstoss erreichen: " + rekursiv);
    }
}
