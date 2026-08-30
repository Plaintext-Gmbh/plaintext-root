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
 * Shared guardrail for file headers and {@code @author} entries in ALL {@code src/main/java}
 * and {@code src/test/java} of the respective reactor (status report 29.08.2026, package R2).
 *
 * <p><b>Finding:</b> 27 root files (above all {@code plaintext-admin-apitoken}) carried the header
 * {@code Copyright (C) eMad, 2026.} — the name of an earlier company that no reader can associate
 * with the project. On top of that placeholder authors from generators and agent runs
 * ({@code @author worker01}, {@code @author Author}, {@code @author Generated}) and a truncated
 * {@code @author info}. Such entries say nothing and get carried on by copy-paste — this test
 * keeps them out, in root and in every consumer.
 *
 * <p><b>Permitted form:</b>
 * <ul>
 *   <li>License header: the MPL-2.0 block (standard) or {@code Copyright (C) plaintext.ch, <Jahr>.}
 *       for legacy code with a documented year.</li>
 *   <li>{@code @author info@plaintext.ch} (preferred); {@code @author plaintext.ch} and
 *       {@code @author Plaintext GmbH} remain admissible. Personal initials are not
 *       objected to.</li>
 * </ul>
 *
 * <p><b>Why a file scan instead of ArchUnit?</b> Headers and Javadoc are not bytecode. Scanned is,
 * from the reactor root, every module's {@code src/main/java} and {@code src/test/java}
 * ({@link ReactorLayout}); the module that ships this linter stays out, because it carries the
 * forbidden tokens as literals.
 *
 * <p><b>Exceptions:</b> the reactor's allowlist ({@code plaintext-arch-allowlist.txt}, rule
 * {@code header-hygiene}, path relative to the root, {@code *}/{@code **} allowed, justification
 * mandatory — {@link ArchAllowlist}). root keeps no allowlist; consumers with legacy code enter
 * their files there until they have been cleaned up.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextHeaderHygieneTest {

    static final String ALLOWLIST_REGEL = "header-hygiene";

    private static final List<String> JAVA_SUFFIXES = List.of("src/main/java", "src/test/java");

    /** {@code Copyright (C) eMad, 2026.} in every spelling (also {@code ©}, without {@code (C)}). */
    private static final Pattern EMAD_HEADER = Pattern.compile(
            "Copyright\\s*(?:\\(C\\)|©)?\\s*eMad\\b", Pattern.CASE_INSENSITIVE);

    /**
     * {@code @author} followed by a placeholder or by nothing at all — up to the end of the line resp.
     * up to the closing {@code *&#47;} of a single-line Javadoc. {@code info} on its own is the
     * truncated form of {@code info@plaintext.ch}.
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
            return; // reactor without Java sources at this point -> nothing to check
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
     * Reports every {@code .java} file below {@code root} as {@code <relativer Pfad>:<Zeile> -> <Grund>}.
     * At most one hit per file and rule — the message is the work instruction, not a statistic.
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
