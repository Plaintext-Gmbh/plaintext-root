/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;

/**
 * Safeguards the jar-name decomposition of {@link PlaintextOwaspSuppressionsTest}.
 *
 * <p>Since card 636 the decomposition replaces the expression
 * {@code ^(.*?)-(\d[^/\\]*?)(?:-(?:sources|javadoc))?\.jar$} (Sonar {@code java:S5852}: polynomial
 * runtime through two reluctant quantifiers). It is the silent part of the suppression test: if it
 * returns nothing, the test measures in the void and still reports green — exactly what its own
 * Javadoc warns about. That is why a table of real jar names stands here instead of "looks fine".</p>
 */
class PlaintextOwaspSuppressionsJarNameTest {

    @Test
    void zerlegtEchteJarNamen() {
        assertArrayEquals(new String[]{"plaintext-root-menu", "1.544.0"},
                PlaintextOwaspSuppressionsTest.zerlegeJarName("plaintext-root-menu-1.544.0.jar"));
        assertArrayEquals(new String[]{"spring-boot-starter-web", "4.1.0"},
                PlaintextOwaspSuppressionsTest.zerlegeJarName("spring-boot-starter-web-4.1.0.jar"));
        assertArrayEquals(new String[]{"jackson-databind", "2.20.1"},
                PlaintextOwaspSuppressionsTest.zerlegeJarName("jackson-databind-2.20.1.jar"));
        assertArrayEquals(new String[]{"lombok", "1.18.42"},
                PlaintextOwaspSuppressionsTest.zerlegeJarName("lombok-1.18.42.jar"));
    }

    @Test
    void trenntAmErstenBindestrichVorZiffer() {
        // 4j belongs to the artifactId part: the digit does not stand directly behind a hyphen.
        assertArrayEquals(new String[]{"ical4j", "4.1.1"},
                PlaintextOwaspSuppressionsTest.zerlegeJarName("ical4j-4.1.1.jar"));
        // Versions with a qualifier stay in the version in full.
        assertArrayEquals(new String[]{"plaintext-parent", "2.1503.0-SNAPSHOT"},
                PlaintextOwaspSuppressionsTest.zerlegeJarName("plaintext-parent-2.1503.0-SNAPSHOT.jar"));
    }

    @Test
    void schneidetKlassifiziererAb() {
        assertArrayEquals(new String[]{"plaintext-root-common", "1.544.0"},
                PlaintextOwaspSuppressionsTest.zerlegeJarName("plaintext-root-common-1.544.0-sources.jar"));
        assertArrayEquals(new String[]{"plaintext-root-common", "1.544.0"},
                PlaintextOwaspSuppressionsTest.zerlegeJarName("plaintext-root-common-1.544.0-javadoc.jar"));
    }

    @Test
    void liefertLeeresArrayOhneVersionOderOhneJarEndung() {
        // Empty array instead of null (java:S1168): the caller checks the length, not for null.
        assertEquals(0, PlaintextOwaspSuppressionsTest.zerlegeJarName("lombok.jar").length);
        assertEquals(0, PlaintextOwaspSuppressionsTest.zerlegeJarName("plaintext-root-menu-1.544.0.pom").length);
        assertEquals(0, PlaintextOwaspSuppressionsTest.zerlegeJarName(null).length);
    }

    @Test
    void bleibtBeiPathologischemNamenLinear() {
        // Exactly the kind of input on which the old pattern became quadratic: many "-digit" places
        // for the two reluctant groups, and a suffix that just barely does not match. Measured with the
        // old expression: 372 ms at n=2000, 1351 ms at n=4000, 4783 ms at n=8000.
        String bosartig = "a-1".repeat(8000) + ".ja";
        assertTimeout(Duration.ofSeconds(2),
                () -> assertEquals(0, PlaintextOwaspSuppressionsTest.zerlegeJarName(bosartig).length));
    }
}
