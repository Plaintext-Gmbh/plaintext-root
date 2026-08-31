/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Both positions of {@code plaintext.security.csp.script-unsafe-inline} (wave 4).
 *
 * <p>The switch decides whether the browser executes a {@code <script>} that stands in the
 * document — that is, an injected one as well. Precisely for that reason this test does not only
 * check that the token disappears, but also that the rest of the policy stays unchanged in the
 * process: a rework that accidentally takes {@code img-src} or {@code form-action} along either
 * breaks the maps or opens another door, and both only become apparent late in production.
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
     * The switch touches ONLY {@code script-src}. {@code style-src} deliberately keeps its
     * {@code 'unsafe-inline'} — the views carry hundreds of {@code style="…"} attributes,
     * that is a rework of its own.
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
