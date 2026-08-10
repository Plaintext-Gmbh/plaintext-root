/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeout;

/**
 * Sichert die Jar-Namens-Zerlegung von {@link PlaintextOwaspSuppressionsTest}.
 *
 * <p>Die Zerlegung ersetzt seit Karte 636 den Ausdruck
 * {@code ^(.*?)-(\d[^/\\]*?)(?:-(?:sources|javadoc))?\.jar$} (Sonar {@code java:S5852}: polynomiale
 * Laufzeit durch zwei reluktante Quantoren). Sie ist der stille Teil des Suppression-Tests: liefert
 * sie nichts, misst der Test im Leeren und meldet trotzdem grün — genau davor warnt sein eigener
 * Javadoc. Deshalb steht hier eine Tabelle echter Jar-Namen statt „sieht gut aus".</p>
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
        // 4j gehoert zum artifactId-Teil: die Ziffer steht nicht direkt hinter einem Bindestrich.
        assertArrayEquals(new String[]{"ical4j", "4.1.1"},
                PlaintextOwaspSuppressionsTest.zerlegeJarName("ical4j-4.1.1.jar"));
        // Versionen mit Qualifier bleiben vollstaendig in der Version.
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
    void liefertNullOhneVersionOderOhneJarEndung() {
        assertNull(PlaintextOwaspSuppressionsTest.zerlegeJarName("lombok.jar"));
        assertNull(PlaintextOwaspSuppressionsTest.zerlegeJarName("plaintext-root-menu-1.544.0.pom"));
        assertNull(PlaintextOwaspSuppressionsTest.zerlegeJarName(null));
    }

    @Test
    void bleibtBeiPathologischemNamenLinear() {
        // Genau der Eingabetyp, an dem das alte Muster quadratisch wurde: viele "-Ziffer"-Stellen
        // fuer die beiden reluktanten Gruppen, und eine Endung, die knapp nicht passt. Mit dem
        // alten Ausdruck gemessen: 372 ms bei n=2000, 1351 ms bei n=4000, 4783 ms bei n=8000.
        String bosartig = "a-1".repeat(8000) + ".ja";
        assertTimeout(Duration.ofSeconds(2),
                () -> assertNull(PlaintextOwaspSuppressionsTest.zerlegeJarName(bosartig)));
    }
}
