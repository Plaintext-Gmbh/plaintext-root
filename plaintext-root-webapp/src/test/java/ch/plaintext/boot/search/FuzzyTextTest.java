/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FuzzyTextTest {

    @Test
    void normalisiert() {
        assertEquals("menu diagnose", FuzzyText.normalize("  Menü-Diagnose "));
        assertEquals("strasse", FuzzyText.normalize("Straße"));
        assertEquals("api tokens root", FuzzyText.normalize("API Tokens (Root)"));
        assertEquals("", FuzzyText.normalize(null));
        assertEquals("", FuzzyText.normalize("--"));
    }

    @Test
    void teilstringDistanz() {
        assertEquals(0, FuzzyText.substringDistance("reise", "wanderreisen"));
        assertEquals(1, FuzzyText.substringDistance("wandereise", "wanderreisen"));
        assertEquals(1, FuzzyText.substringDistance("kontkate", "kontakte"), "Vertauschung zaehlt 1");
        assertEquals(1, FuzzyText.substringDistance("darvda", "darvidaschoko"));
        assertEquals(3, FuzzyText.substringDistance("abc", ""));
    }

    @Test
    void erlaubteFehlerNachLaenge() {
        assertEquals(0, FuzzyText.allowedErrors(3));
        assertEquals(1, FuzzyText.allowedErrors(4));
        assertEquals(1, FuzzyText.allowedErrors(6));
        assertEquals(2, FuzzyText.allowedErrors(7));
        assertEquals(2, FuzzyText.allowedErrors(10));
        assertEquals(3, FuzzyText.allowedErrors(11));
    }

    @Test
    void fuzzyFehlerMitGrenze() {
        assertEquals(1, FuzzyText.fuzzyErrors("wandereise", "wanderreisen"));
        assertEquals(-1, FuzzyText.fuzzyErrors("qxqxqx", "wanderreisen"));
        assertEquals(-1, FuzzyText.fuzzyErrors("wanderreise", ""));
        assertEquals(-1, FuzzyText.fuzzyErrors("rex", "reisen"), "3 Zeichen: nur woertlich");
        assertEquals(0, FuzzyText.fuzzyErrors("rei", "reisen"));
    }
}
