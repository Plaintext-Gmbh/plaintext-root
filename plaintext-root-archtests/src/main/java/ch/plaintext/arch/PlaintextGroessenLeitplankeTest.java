/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Geteilte Groessen-Leitplanke (Zustandsbericht 29.08.2026, Massnahme 12): keine Java-Datei ueber
 * {@value #MAX_ZEILEN} Zeilen.
 *
 * <p><b>Warum eine Zeilengrenze und nicht eine Komplexitaetsmetrik.</b> Zeilen sind ein grobes
 * Mass — aber ein unbestechliches, und es kostet keinen zweiten Analysator. Die Grenze zielt nicht
 * auf „schoene" Klassen, sondern auf den einen Effekt, der im Alltag weh tut: eine Datei jenseits
 * von {@value #MAX_ZEILEN} Zeilen passt in keinen Review mehr, sammelt zwangslaeufig mehrere
 * Zustaendigkeiten und wird zum Merge-Konflikt-Magneten. Der Wert ist bewusst hoch angesetzt: er
 * ist eine Reissleine gegen unbegrenztes Wachstum, keine Stilvorgabe. Wer ihn erreicht, hat nicht
 * „zu lang" geschrieben, sondern mindestens zwei Klassen in eine gelegt.
 *
 * <p><b>Bestand am 30.08.2026.</b> root ist mit dieser Grenze ohne Ausnahme gruen (groesste Datei:
 * {@code ClaudeAutomationServiceTest} mit 1128 Zeilen). In der Familie reisst sie derzeit genau
 * eine Datei: {@code plaintext-guild-events/…/EventService.java} (2027 Zeilen). Dass root sauber
 * ist, ist der Grund, warum die Schwelle hier stehen darf, statt „auf spaeter" verschoben zu
 * werden — eine Leitplanke, die schon beim Einbau Ausnahmen braucht, wird nie wieder eng.
 *
 * <p><b>Geprueft werden {@code src/main/java} und {@code src/test/java}</b> jedes Moduls des
 * Reactors ({@link ReactorLayout}). Tests sind ausdruecklich mit drin: eine 2000-Zeilen-Testklasse
 * ist genauso unlesbar wie eine 2000-Zeilen-Produktivklasse, und sie waechst schneller.
 *
 * <p><b>Ausnahmen:</b> Allowlist des Reactors ({@code plaintext-arch-allowlist.txt}, Regel
 * {@value #ALLOWLIST_REGEL}, Pfad relativ zur Reactor-Wurzel, {@code *}/{@code **} erlaubt,
 * Begruendung Pflicht — {@link ArchAllowlist}). Die Schwelle selbst ist bewusst <em>keine</em>
 * Property: eine Ausnahme mit Begruendung ist nachvollziehbar, ein hochgedrehter Grenzwert nicht.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextGroessenLeitplankeTest {

    static final String ALLOWLIST_REGEL = "groesse-max-loc";

    /** Obergrenze in Zeilen je {@code .java}-Datei. */
    static final int MAX_ZEILEN = 1500;

    private static final List<String> JAVA_SUFFIXES = List.of("src/main/java", "src/test/java");

    @Test
    @DisplayName("Keine Java-Datei ueber " + MAX_ZEILEN + " Zeilen")
    void keineUeberlangenJavaDateien() throws IOException {
        List<Path> roots = new ArrayList<>();
        for (String suffix : JAVA_SUFFIXES) {
            roots.addAll(ReactorLayout.sourceRoots(suffix));
        }
        if (roots.isEmpty()) {
            return; // Reactor ohne Java-Quellen an dieser Stelle -> nichts zu pruefen
        }
        ArchAllowlist allowlist = ArchAllowlist.fuer(ALLOWLIST_REGEL);

        List<String> verstoesse = new ArrayList<>(allowlist.fehler());
        for (Path root : roots) {
            for (Treffer treffer : scan(root, MAX_ZEILEN)) {
                String rel = ReactorLayout.relativ(root.resolve(treffer.pfad()));
                if (!allowlist.erlaubt(rel)) {
                    verstoesse.add("%s -> %d Zeilen (Grenze %d)".formatted(rel, treffer.zeilen(), MAX_ZEILEN));
                }
            }
        }

        if (!verstoesse.isEmpty()) {
            StringBuilder msg = new StringBuilder(
                    "\n\n=== GROESSEN-LEITPLANKE (Zustandsbericht 29.08.2026, Massnahme 12) ===\n");
            verstoesse.stream().sorted().forEach(v -> msg.append("  ! ").append(v).append("\n"));
            msg.append("\nEine Datei ueber ").append(MAX_ZEILEN).append(" Zeilen traegt mehr als eine Zustaendigkeit.\n")
               .append("Herausloesen statt kuerzen: der zweite Verantwortungsbereich wird eine eigene Klasse.\n")
               .append("Begruendete Ausnahme: '").append(ALLOWLIST_REGEL).append(" <pfad>  # <Grund>' in ")
               .append(ArchAllowlist.DATEINAME).append(" — die Schwelle bleibt, wo sie ist.\n");
            fail(msg.toString());
        }
    }

    @Test
    @DisplayName("Scanner zaehlt Zeilen und meldet nur ueber der Grenze")
    void scannerMeldetNurUeberDerGrenze(@TempDir Path tmp) throws IOException {
        Path pkg = Files.createDirectories(tmp.resolve("ch/plaintext/x"));
        Files.writeString(pkg.resolve("Klein.java"), "a\nb\nc\n");
        Files.writeString(pkg.resolve("Genau.java"), "x\n".repeat(5));
        Files.writeString(pkg.resolve("Gross.java"), "x\n".repeat(6));
        Files.writeString(pkg.resolve("KeinJava.txt"), "x\n".repeat(99));

        List<Treffer> treffer = scan(tmp, 5);

        assertEquals(1, treffer.size(), () -> "Nur die Datei ueber der Grenze zaehlt: " + treffer);
        assertEquals("ch/plaintext/x/Gross.java", treffer.getFirst().pfad());
        assertEquals(6, treffer.getFirst().zeilen());
        assertTrue(scan(Path.of("gibt/es/nicht"), 5).isEmpty());
        assertTrue(scan(null, 5).isEmpty());
    }

    /** Eine ueberlange Datei: Pfad relativ zu {@code root} und ihre Zeilenzahl. */
    record Treffer(String pfad, long zeilen) {
    }

    /** Meldet jede {@code .java}-Datei unter {@code root} mit mehr als {@code grenze} Zeilen. */
    static List<Treffer> scan(Path root, int grenze) throws IOException {
        List<Treffer> treffer = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return treffer;
        }
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile)
                                  .filter(p -> p.getFileName().toString().endsWith(".java"))
                                  .sorted().toList()) {
                long zeilen;
                try (Stream<String> lines = Files.lines(file)) {
                    zeilen = lines.count();
                }
                if (zeilen > grenze) {
                    treffer.add(new Treffer(root.relativize(file).toString().replace('\\', '/'), zeilen));
                }
            }
        }
        return treffer;
    }
}
