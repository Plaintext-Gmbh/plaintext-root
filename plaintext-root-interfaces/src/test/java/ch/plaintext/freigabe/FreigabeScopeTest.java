/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.freigabe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests der zentralen Sichtbarkeits-Regel {@link FreigabeScope#erlaubt}.
 */
class FreigabeScopeTest {

    @Test
    void keineErlaubtNie() {
        assertFalse(FreigabeScope.KEINE.erlaubt("m", "u", "m", "u"));
    }

    @Test
    void mandatErlaubtAlleImSelbenMandat() {
        assertTrue(FreigabeScope.MANDAT.erlaubt("m", "ersteller", "m", "jemand-anderes"));
        assertFalse(FreigabeScope.MANDAT.erlaubt("m", "ersteller", "anderer-mandat", "x")); // Mandantengrenze
    }

    @Test
    void persoenlichNurErsteller() {
        assertTrue(FreigabeScope.PERSOENLICH.erlaubt("m", "ich", "m", "ich"));
        assertFalse(FreigabeScope.PERSOENLICH.erlaubt("m", "ich", "m", "andere"));
        assertFalse(FreigabeScope.PERSOENLICH.erlaubt("m", "ich", "anderer-mandat", "ich")); // Mandantengrenze
    }
}
