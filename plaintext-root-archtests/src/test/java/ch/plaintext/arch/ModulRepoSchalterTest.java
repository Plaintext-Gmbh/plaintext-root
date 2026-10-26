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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 1604: der Schalter {@link ReactorLayout#MODUL_REPO} wirkt nur in einem Reactor ohne Anwendung.
 * Die Apps sollen mit den archtests so streng bleiben wie vorher; das haengt an genau zwei Dingen, die
 * hier festgehalten sind: ohne Schalter aendert sich nichts, und in einer App ist der Schalter rot.
 */
class ModulRepoSchalterTest {

    @TempDir
    Path reactor;

    @Test
    @DisplayName("ohne Schalter: kein Modul-Repo, auch wenn der Reactor keine Anwendung hat")
    void ohneSchalterAendertSichNichts() throws IOException {
        modul("plaintext-z-x");
        assertFalse(ReactorLayout.modulRepo(false, reactor));
    }

    @Test
    @DisplayName("Schalter in einem Reactor ohne application.yml: Modul-Repo")
    void schalterImModulRepo() throws IOException {
        modul("plaintext-z-x");
        // eine application.yml unter target/ oder im Testpfad ist keine ausgelieferte Anwendung
        Files.createDirectories(reactor.resolve("plaintext-z-x/target/classes/src/main/resources"));
        Files.writeString(reactor.resolve("plaintext-z-x/target/classes/src/main/resources/application.yml"), "a: 1\n");
        Files.createDirectories(reactor.resolve("plaintext-z-x/src/test/resources"));
        Files.writeString(reactor.resolve("plaintext-z-x/src/test/resources/application.yml"), "a: 1\n");
        assertTrue(ReactorLayout.modulRepo(true, reactor));
    }

    @Test
    @DisplayName("Gegenprobe: Schalter in einer App ist rot, statt die Untergrenzen zu senken")
    void schalterInEinerAppIstRot() throws IOException {
        modul("plaintext-z-x");
        Path webapp = modul("gruppe/plaintext-x-webapp");
        Files.writeString(webapp.resolve("src/main/resources/application.yml"), "plaintext: {}\n");

        AssertionError fehler = assertThrows(AssertionError.class, () -> ReactorLayout.modulRepo(true, reactor));
        assertTrue(fehler.getMessage().contains("gruppe/plaintext-x-webapp/src/main/resources/application.yml"),
                fehler.getMessage());
    }

    @Test
    @DisplayName("in diesem Lauf (root, ohne Schalter) bleiben die Untergrenzen die gemessenen")
    void untergrenzenOhneSchalterUnveraendert() {
        assertFalse(ReactorLayout.modulRepo());
        assertEquals(40, ReactorLayout.mindestens(40));
        assertEquals(2, ReactorLayout.mindestens(2));
    }

    private Path modul(String name) throws IOException {
        Path m = reactor.resolve(name);
        Files.createDirectories(m.resolve("src/main/resources"));
        Files.createDirectories(m.resolve("src/main/java"));
        return m;
    }
}
