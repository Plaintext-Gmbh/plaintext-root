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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Positive control for {@link ArchAllowlist#unbenutzt}: the freeze rule of card 1299 relies on it
 * to make the allowlist shrink with the code. If it reported nothing, orphaned entries would stay
 * and permit the edge again later — the list would only ever grow.
 */
class ArchAllowlistUnbenutztTest {

    @Test
    @DisplayName("Verwaiste Eintraege werden gemeldet, benutzte und fremde Regeln nicht")
    void meldetNurVerwaisteEintraegeDerEigenenRegel(@TempDir Path tmp) throws IOException {
        Path datei = tmp.resolve(ArchAllowlist.DATEINAME);
        Files.writeString(datei, """
                modulgrenze  ch.plaintext.a.A -> ch.plaintext.b.B   # Karte 1, noch da
                modulgrenze  ch.plaintext.a.Alt -> ch.plaintext.b.B # Karte 2, laengst behoben
                modulgrenze  ch.plaintext.c.* -> ch.plaintext.d.*   # Karte 3, Glob trifft
                layering-jsf-in-service  ch.plaintext.x.Y           # fremde Regel
                """);
        ArchAllowlist a = ArchAllowlist.fuer("modulgrenze", datei);

        assertTrue(a.fehler().isEmpty(), () -> "Formatfehler: " + a.fehler());
        assertEquals(3, a.anzahl());
        assertEquals(List.of("ch.plaintext.a.Alt -> ch.plaintext.b.B"),
                a.unbenutzt(List.of("ch.plaintext.a.A -> ch.plaintext.b.B", "ch.plaintext.c.C -> ch.plaintext.d.D")));
        assertEquals(3, a.unbenutzt(List.of()).size(), "ohne Kanten ist jeder Eintrag verwaist");
    }
}
