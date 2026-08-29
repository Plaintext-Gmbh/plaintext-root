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
 * Zustandsbericht 29.08.2026, Etappe 2: Jede session-scoped Bean des root-Reactors muss von der
 * geteilten Regel {@code PlaintextSessionBeanSerialisierbarTest} tatsaechlich gesehen werden.
 *
 * <p><b>Befund.</b> Die Regel importiert per ArchUnit das Basispaket {@code ch.plaintext} — aber
 * nur, was auf dem Classpath des Moduls liegt, in dem sie laeuft. In root ist das
 * {@code plaintext-root-webapp}, und die haengt nicht an {@code plaintext-admin-requirements}.
 * Drei Session-Beans dort hielten nicht-transiente Repositories/Dienste, ohne dass root es
 * merkte; aufgefallen ist es in plaintext-app, wo alle root-Jars auf dem Classpath liegen. Das
 * Basispaket war nie das Problem — die Sichtbarkeit war es.
 *
 * <p><b>Was der Test prueft.</b> Fuer jede Klasse unter {@code <modul>/src/main/java}, die
 * {@code @Scope("session")} (oder {@code @SessionScope}) traegt: entweder ist sie von hier aus
 * ladbar (dann prueft sie der Lauf in der webapp), oder das Modul faehrt die geteilten Arch-Regeln
 * selbst (Surefire {@code <dependenciesToScan>} auf {@code plaintext-root-archtests}, Vorbild
 * {@code plaintext-admin-requirements/pom.xml}). Ein Modul, das beides nicht erfuellt, ist ein
 * blinder Fleck — und genau der soll nicht wieder entstehen.
 *
 * <p>Positivkontrolle: mindestens eine Session-Bean muss gefunden UND ladbar sein; sonst
 * prueft der Test nichts (dieselbe Abwaegung wie in der geteilten Regel).
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

    /** Vollqualifizierte Namen aller Klassen unter {@code quellen}, die session-scoped sind. */
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

    /** Das Modul, das die Regeln ausliefert, traegt die Annotation als Literal in seinen Quellen. */
    private static boolean liefertDieArchRegeln(Path modul) {
        return Files.isRegularFile(modul.resolve("src/main/java/ch/plaintext/arch/ReactorLayout.java"));
    }

    /** Erstes Verzeichnis ab {@code user.dir} nach oben, dessen {@code pom.xml} {@code <modules>} traegt. */
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
