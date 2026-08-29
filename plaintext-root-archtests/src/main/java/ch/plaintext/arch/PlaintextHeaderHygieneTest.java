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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Geteilte Leitplanke fuer Datei-Header und {@code @author}-Angaben in ALLEN {@code src/main/java}
 * und {@code src/test/java} des jeweiligen Reactors (Zustandsbericht 29.08.2026, Paket R2).
 *
 * <p><b>Befund:</b> 27 root-Dateien (vor allem {@code plaintext-admin-apitoken}) trugen den Header
 * {@code Copyright (C) eMad, 2026.} — der Name einer frueheren Firmierung, den kein Leser dem
 * Projekt zuordnen kann. Dazu Platzhalter-Autoren aus Generatoren und Agenten-Laeufen
 * ({@code @author worker01}, {@code @author Author}, {@code @author Generated}) und ein
 * abgeschnittenes {@code @author info}. Solche Angaben sagen nichts und werden per Copy-Paste
 * weitergetragen — dieser Test haelt sie draussen, in root und in jedem Consumer.
 *
 * <p><b>Erlaubte Form:</b>
 * <ul>
 *   <li>Lizenz-Header: der MPL-2.0-Block (Standard) oder {@code Copyright (C) plaintext.ch, <Jahr>.}
 *       fuer Altbestand mit dokumentiertem Jahr.</li>
 *   <li>{@code @author info@plaintext.ch} (bevorzugt); {@code @author plaintext.ch} und
 *       {@code @author Plaintext GmbH} bleiben zulaessig. Persoenliche Kuerzel werden nicht
 *       beanstandet.</li>
 * </ul>
 *
 * <p><b>Warum ein Dateiscan statt ArchUnit?</b> Header und Javadoc sind kein Bytecode. Gescannt wird
 * ab der Reactor-Wurzel jedes Modul-{@code src/main/java} und {@code src/test/java}
 * ({@link ReactorLayout}); das Modul, das diesen Linter ausliefert, bleibt aussen vor, weil es die
 * verbotenen Tokens als Literale traegt.
 *
 * <p><b>Ausnahmen:</b> Allowlist des Reactors ({@code plaintext-arch-allowlist.txt}, Regel
 * {@code header-hygiene}, Pfad relativ zur Wurzel, {@code *}/{@code **} erlaubt, Begruendung
 * Pflicht — {@link ArchAllowlist}). root fuehrt keine Allowlist; Consumer mit Altbestand tragen
 * ihre Dateien dort ein, bis sie bereinigt sind.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextHeaderHygieneTest {

    static final String ALLOWLIST_REGEL = "header-hygiene";

    private static final List<String> JAVA_SUFFIXES = List.of("src/main/java", "src/test/java");

    /** {@code Copyright (C) eMad, 2026.} in jeder Schreibweise (auch {@code ©}, ohne {@code (C)}). */
    private static final Pattern EMAD_HEADER = Pattern.compile(
            "Copyright\\s*(?:\\(C\\)|©)?\\s*eMad\\b", Pattern.CASE_INSENSITIVE);

    /**
     * {@code @author} gefolgt von einem Platzhalter oder gar nichts — bis zum Zeilenende bzw. bis
     * zum schliessenden {@code *&#47;} eines einzeiligen Javadocs. {@code info} allein ist die
     * abgeschnittene Form von {@code info@plaintext.ch}.
     */
    private static final Pattern PLATZHALTER_AUTHOR = Pattern.compile(
            "@author\\s*(?::\\s*)?(Author|worker\\d+|Generated|info|TODO|unknown)?\\s*(?:\\*/)?\\s*$",
            Pattern.CASE_INSENSITIVE);

    @Test
    void keineFremdenHeaderUndKeinePlatzhalterAutoren() throws IOException {
        List<Path> roots = new ArrayList<>();
        for (String suffix : JAVA_SUFFIXES) {
            roots.addAll(ReactorLayout.sourceRoots(suffix));
        }
        if (roots.isEmpty()) {
            return; // Reactor ohne Java-Quellen an dieser Stelle -> nichts zu pruefen
        }
        ArchAllowlist allowlist = ArchAllowlist.fuer(ALLOWLIST_REGEL);

        List<String> violations = new ArrayList<>(allowlist.fehler());
        for (Path root : roots) {
            for (String hit : scan(root)) {
                String rel = ReactorLayout.relativ(root.resolve(hit.substring(0, hit.indexOf(':'))));
                if (!allowlist.erlaubt(rel)) {
                    violations.add(rel + hit.substring(hit.indexOf(':')));
                }
            }
        }

        if (!violations.isEmpty()) {
            StringBuilder msg = new StringBuilder("\n\n=== HEADER-/AUTOR-HYGIENE (Zustandsbericht 29.08.2026) ===\n");
            violations.forEach(v -> msg.append("  ! ").append(v).append("\n"));
            msg.append("\nErlaubt: MPL-2.0-Header oder 'Copyright (C) plaintext.ch, <Jahr>.' sowie\n")
               .append("'@author info@plaintext.ch' (bzw. plaintext.ch / Plaintext GmbH). Platzhalter wie\n")
               .append("worker01, Author, Generated oder ein abgeschnittenes 'info' bitte ersetzen.\n")
               .append("Begruendete Ausnahme: '").append(ALLOWLIST_REGEL).append(" <pfad>  # <Grund>' in ")
               .append(ArchAllowlist.DATEINAME).append(".\n");
            fail(msg.toString());
        }
    }

    @Test
    void scannerErkenntEmadUndPlatzhalterUndLaesstErlaubteFormenDurch(@TempDir Path tmp) throws IOException {
        Path pkg = Files.createDirectories(tmp.resolve("ch/plaintext/x"));
        Files.writeString(pkg.resolve("Emad.java"), """
                /*
                 * Copyright (C) eMad, 2026.
                 */
                package ch.plaintext.x;
                /** @author info@plaintext.ch */
                public class Emad {}
                """);
        Files.writeString(pkg.resolve("EmadAlt.java"), """
                package ch.plaintext.x;/*
                  Copyright (C) eMad, 2017.
                 */
                public class EmadAlt {}
                """);
        Files.writeString(pkg.resolve("Worker.java"), """
                package ch.plaintext.x;
                /**
                 * Irgendwas.
                 *
                 * @author worker01
                 * @since 2026
                 */
                public class Worker {}
                """);
        Files.writeString(pkg.resolve("Abgeschnitten.java"), """
                package ch.plaintext.x;
                /** @author info   */
                public class Abgeschnitten {}
                """);
        Files.writeString(pkg.resolve("Leer.java"), """
                package ch.plaintext.x;
                /** @author
                 */
                public class Leer {}
                """);
        Files.writeString(pkg.resolve("Generated.java"), """
                package ch.plaintext.x;
                /** @author Generated */
                public class Generated {}
                """);
        Files.writeString(pkg.resolve("Ok.java"), """
                /* This Source Code Form is subject to the terms of the Mozilla Public
                 * License, v. 2.0. */
                package ch.plaintext.x;
                /**
                 * @author info@plaintext.ch
                 * @since 2026
                 */
                public class Ok {}
                """);
        Files.writeString(pkg.resolve("OkAlt.java"), """
                /*
                 * Copyright (C) plaintext.ch, 2026.
                 */
                package ch.plaintext.x;
                /** @author plaintext.ch */
                public class OkAlt {}
                """);
        Files.writeString(pkg.resolve("OkGmbh.java"), """
                package ch.plaintext.x;
                /** @author Plaintext GmbH */
                public class OkGmbh {}
                """);
        Files.writeString(pkg.resolve("OkKuerzel.java"), """
                package ch.plaintext.x;
                /** @author mad */
                public class OkKuerzel {}
                """);

        List<String> hits = scan(tmp);

        List<String> erwartet = List.of("Emad.java", "EmadAlt.java", "Worker.java", "Abgeschnitten.java",
                "Leer.java", "Generated.java");
        for (String e : erwartet) {
            assertTrue(hits.stream().anyMatch(h -> h.contains(e)), e + " muss erkannt werden: " + hits);
        }
        assertTrue(hits.stream().noneMatch(h -> h.contains("/Ok")), "Ok*-Dateien duerfen nicht gemeldet werden: " + hits);
        assertEquals(erwartet.size(), hits.size(), "Genau ein Treffer je Verstoss-Datei erwartet: " + hits);
    }

    @Test
    void scanAufNichtVorhandenemPfadLiefertLeereListe() throws IOException {
        assertTrue(scan(Path.of("does/not/exist/xyz")).isEmpty());
        assertTrue(scan(null).isEmpty());
    }

    /**
     * Meldet jede {@code .java}-Datei unter {@code root} als {@code <relativer Pfad>:<Zeile> -> <Grund>}.
     * Pro Datei und Regel hoechstens ein Treffer — die Meldung ist die Arbeitsanweisung, keine Statistik.
     */
    static List<String> scan(Path root) throws IOException {
        List<String> hits = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return hits;
        }
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile)
                                  .filter(p -> p.getFileName().toString().endsWith(".java"))
                                  .sorted().toList()) {
                String rel = root.relativize(file).toString().replace('\\', '/');
                int lineNo = 0;
                boolean header = false;
                boolean author = false;
                for (String line : Files.readAllLines(file)) {
                    lineNo++;
                    if (!header && EMAD_HEADER.matcher(line).find()) {
                        hits.add(rel + ":" + lineNo + " -> Header 'Copyright (C) eMad' -> 'Copyright (C) plaintext.ch, <Jahr>.'");
                        header = true;
                    }
                    if (!author && line.contains("@author")) {
                        Matcher m = PLATZHALTER_AUTHOR.matcher(line.stripTrailing());
                        if (m.find()) {
                            String wert = m.group(1) == null ? "(leer)" : m.group(1);
                            hits.add(rel + ":" + lineNo + " -> Platzhalter '@author " + wert + "' -> '@author info@plaintext.ch'");
                            author = true;
                        }
                    }
                }
            }
        }
        return hits;
    }
}
