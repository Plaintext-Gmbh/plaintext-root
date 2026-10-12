/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Card 1522: the reactor import is shared per test JVM. A second call must hand back the same
 * instance, otherwise every rule holding it in a static field keeps its own copy of the reactor again.
 */
class GeteilterReaktorImportTest {

    @Test
    @DisplayName("Karte 1522: der Reaktor-Import wird je Test-JVM einmal gelesen und geteilt")
    void einImportFuerAlle() {
        assertSame(PlaintextLayeringTest.importiereReactorKlassen(), PlaintextLayeringTest.importiereReactorKlassen());
    }
}
