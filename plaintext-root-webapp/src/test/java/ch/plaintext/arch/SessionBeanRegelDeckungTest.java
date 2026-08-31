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
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Status report 29.08.2026, stage 2: every session-scoped bean of the root reactor must actually be
 * seen by the shared rule {@code PlaintextSessionBeanSerialisierbarTest}.
 *
 * <p><b>Finding.</b> The rule imports the base package {@code ch.plaintext} via ArchUnit — but
 * only what lies on the classpath of the module it runs in. In root that is
 * {@code plaintext-root-webapp}, and that does not depend on {@code plaintext-admin-requirements}.
 * Three session beans there held non-transient repositories/services without root
 * noticing; it came to light in plaintext-app, where all root jars are on the classpath. The
 * base package was never the problem — visibility was.
 *
 * <p><b>What the test checks.</b> For every class under {@code <modul>/src/main/java} that
 * carries {@code @Scope("session")} (or {@code @SessionScope}): either it is loadable from here
 * (then the run in the webapp checks it), or the module runs the shared arch rules
 * itself (Surefire {@code <dependenciesToScan>} on {@code plaintext-root-archtests}, model
 * {@code plaintext-admin-requirements/pom.xml}). A module that fulfils neither is a
 * blind spot — and exactly that must not arise again.
 *
 * <p>Positive control: at least one session bean must be found AND loadable; otherwise
 * the test checks nothing (the same trade-off as in the shared rule).
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class SessionBeanRegelDeckungTest {

    private static final Pattern SESSION_SCOPE = Pattern.compile(
            "^\\s*@(?:Scope\\(\\s*(?:value\\s*=\\s*)?\"session\"|SessionScope\\b)", Pattern.MULTILINE);

    private static final Pattern FAEHRT_ARCH_REGELN = Pattern.compile(
            "<dependenciesToScan>[\\s\\S]*?plaintext-root-archtests[\\s\\S]*?</dependenciesToScan>");

    @Test
    @DisplayName("Jede session-scoped Bean im Reactor liegt im Sichtfeld der Serialisierbarkeits-Regel")
    void jedeSessionBeanWirdGeprueft() throws IOException {
        Path reactor = reactorWurzel();
        List<String> blind = new ArrayList<>();
        int gefunden = 0;
        int ladbar = 0;

        try (Stream<Path> module = Files.list(reactor)) {
            for (Path modul : module.filter(Files::isDirectory).sorted().toList()) {
                Path quellen = modul.resolve("src/main/java");
                if (!Files.isDirectory(quellen) || liefertDieArchRegeln(modul)) {
                    continue;
                }
                boolean faehrtSelbst = FAEHRT_ARCH_REGELN.matcher(Files.readString(modul.resolve("pom.xml"))).find();
                for (String klasse : sessionBeans(quellen)) {
                    gefunden++;
                    if (istLadbar(klasse)) {
                        ladbar++;
                    } else if (!faehrtSelbst) {
                        blind.add(modul.getFileName() + " : " + klasse);
                    }
                }
            }
        }

        int gefundenGesamt = gefunden;
        int ladbarGesamt = ladbar;
        assertTrue(gefundenGesamt >= 1 && ladbarGesamt >= 1,
                () -> "Positivkontrolle fehlgeschlagen (gefunden=" + gefundenGesamt + ", ladbar=" + ladbarGesamt
                        + ") — Reactor-Wurzel oder Classpath stimmen nicht: " + reactor);
        assertTrue(blind.isEmpty(),
                () -> blind.size() + " session-scoped Bean(s), die weder die webapp sieht noch das eigene Modul "
                        + "prueft. Entweder das Modul in plaintext-root-webapp einhaengen oder die geteilten "
                        + "Arch-Regeln im Modul selbst fahren (Surefire <dependenciesToScan> auf "
                        + "plaintext-root-archtests, Vorbild plaintext-admin-requirements/pom.xml).\n  "
                        + String.join("\n  ", blind));
    }

    /** Fully qualified names of all classes under {@code quellen} that are session-scoped. */
    private static List<String> sessionBeans(Path quellen) throws IOException {
        List<String> klassen = new ArrayList<>();
        try (Stream<Path> dateien = Files.walk(quellen)) {
            for (Path datei : dateien.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                if (SESSION_SCOPE.matcher(Files.readString(datei)).find()) {
                    String rel = quellen.relativize(datei).toString().replace('\\', '/');
                    klassen.add(rel.substring(0, rel.length() - ".java".length()).replace('/', '.'));
                }
            }
        }
        return klassen;
    }

    private static boolean istLadbar(String klasse) {
        try {
            Class.forName(klasse, false, SessionBeanRegelDeckungTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** The module that ships the rules carries the annotation as a literal in its sources. */
    private static boolean liefertDieArchRegeln(Path modul) {
        return Files.isRegularFile(modul.resolve("src/main/java/ch/plaintext/arch/ReactorLayout.java"));
    }

    /** First directory upwards from {@code user.dir} whose {@code pom.xml} carries {@code <modules>}. */
    private static Path reactorWurzel() throws IOException {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path pom = dir.resolve("pom.xml");
            if (Files.isRegularFile(pom) && Files.readString(pom).contains("<modules>")) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Keine Reactor-Wurzel (pom.xml mit <modules>) oberhalb von "
                + System.getProperty("user.dir"));
    }
}
