/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Beide Stellungen von {@code plaintext.security.csp.script-unsafe-inline} (Welle 4).
 *
 * <p>Der Schalter entscheidet, ob der Browser ein {@code <script>} ausfuehrt, das im Dokument
 * steht — also auch ein eingeschleustes. Genau deshalb wird hier nicht nur geprueft, dass das
 * Token verschwindet, sondern auch, dass die uebrige Policy dabei unveraendert bleibt: ein
 * Umbau, der versehentlich {@code img-src} oder {@code form-action} mitnimmt, macht entweder die
 * Karten kaputt oder reisst eine andere Tuer auf, und beides faellt im Betrieb erst spaet auf.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextCspPolicyTest {

    @Test
    void vorgabeIstBestandsverhalten() {
        assertTrue(new PlaintextSecurityProperties().getCsp().isScriptUnsafeInline(),
                "Vorgabe muss true bleiben: der Header wird app-weise umgelegt, nicht durch ein root-Release");
    }

    @Test
    void mitUnsafeInlineStehtDasTokenInScriptSrc() {
        String policy = PlaintextSecurityConfig.cspPolicy(true);

        assertTrue(policy.contains("script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net https://unpkg.com;"),
                "script-src muss das Token unveraendert fuehren: " + policy);
    }

    @Test
    void ohneUnsafeInlineFehltDasTokenInScriptSrc() {
        String policy = PlaintextSecurityConfig.cspPolicy(false);

        assertTrue(policy.contains("script-src 'self' https://cdn.jsdelivr.net https://unpkg.com;"),
                "script-src muss ohne Token und ohne doppelte Leerzeichen dastehen: " + policy);
        assertFalse(scriptSrc(policy).contains("'unsafe-inline'"),
                "in script-src darf kein 'unsafe-inline' mehr stehen: " + policy);
    }

    /**
     * Der Schalter fasst NUR {@code script-src} an. {@code style-src} behaelt sein
     * {@code 'unsafe-inline'} bewusst — die Views tragen Hunderte {@code style="…"}-Attribute,
     * das ist ein eigener Umbau.
     */
    @Test
    void alleUebrigenDirektivenBleibenGleich() {
        String[] mit = PlaintextSecurityConfig.cspPolicy(true).split("; ");
        String[] ohne = PlaintextSecurityConfig.cspPolicy(false).split("; ");

        assertEquals(mit.length, ohne.length, "Anzahl der Direktiven darf sich nicht aendern");
        for (int i = 0; i < mit.length; i++) {
            if (mit[i].startsWith("script-src")) {
                continue;
            }
            assertEquals(mit[i], ohne[i], "Direktive " + i + " darf sich nicht aendern");
        }
        assertTrue(PlaintextSecurityConfig.cspPolicy(false).contains("style-src 'self' 'unsafe-inline'"),
                "style-src bleibt vorerst wie es war");
    }

    @Test
    void unsafeEvalBleibtInBeidenStellungenDraussen() {
        assertFalse(PlaintextSecurityConfig.cspPolicy(true).contains("'unsafe-eval'"));
        assertFalse(PlaintextSecurityConfig.cspPolicy(false).contains("'unsafe-eval'"));
    }

    private static String scriptSrc(String policy) {
        for (String direktive : policy.split("; ")) {
            if (direktive.startsWith("script-src")) {
                return direktive;
            }
        }
        throw new AssertionError("keine script-src-Direktive in: " + policy);
    }
}
