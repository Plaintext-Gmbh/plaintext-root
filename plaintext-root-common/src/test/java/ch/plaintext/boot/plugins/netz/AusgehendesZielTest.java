/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.netz;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 1362 (HC1/HC2): interne Ziele werden abgewiesen, öffentliche und freigegebene durchgelassen.
 * Ohne Netz: IP-Literale lösen lokal auf, Hostnamen über einen Test-Auflöser.
 */
class AusgehendesZielTest {

    /** Test-Auflöser: kennt genau zwei Namen, alles andere ist unbekannt. */
    private static final AusgehendesZiel.Aufloeser TEST_DNS = host -> switch (host) {
        case "kalender.example.org" -> new InetAddress[]{InetAddress.getByName("93.184.215.14")};
        case "intern.example.org" -> new InetAddress[]{
                InetAddress.getByName("93.184.215.14"), InetAddress.getByName("10.1.2.3")};
        default -> {
            if (host.matches("[0-9.]+") || host.contains(":")) {
                yield new InetAddress[]{InetAddress.getByName(host)};
            }
            throw new UnknownHostException(host);
        }
    };

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.8.9.10", "0.0.0.0", "10.0.0.1", "172.16.5.4", "172.31.255.255",
            "192.168.1.224", "169.254.169.254", "100.64.0.1", "100.127.255.254", "224.0.0.1",
            "255.255.255.255", "198.18.0.1", "192.0.0.8", "::1", "::", "fe80::1", "fd00::1", "fc12::5",
            "::ffff:127.0.0.1", "::ffff:192.168.1.1"})
    void interneAdressenSindGesperrt(String ip) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AusgehendesZiel.pruefeHost(ip, Set.of(), TEST_DNS));
        assertTrue(e.getMessage().contains("interne Adresse"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "93.184.215.14", "172.32.0.1", "100.128.0.1", "2a00:1450:4001::1"})
    void oeffentlicheAdressenSindErlaubt(String ip) {
        // Positivkontrolle: ohne sie wäre eine Prüfung, die ALLES sperrt, ebenfalls grün.
        assertDoesNotThrow(() -> AusgehendesZiel.pruefeHost(ip, Set.of(), TEST_DNS));
    }

    @Test
    void hostMitEinerInternenAdresseUnterMehrerenIstGesperrt() {
        assertThrows(IllegalArgumentException.class,
                () -> AusgehendesZiel.pruefeHost("intern.example.org", Set.of(), TEST_DNS));
        assertDoesNotThrow(() -> AusgehendesZiel.pruefeHost("kalender.example.org", Set.of(), TEST_DNS));
    }

    @Test
    void localhostPerNameIstGesperrt() {
        // Echter Auflöser: "localhost" löst auf jeder Maschine lokal auf, ohne Netz.
        assertThrows(IllegalArgumentException.class, () -> AusgehendesZiel.pruefeHost("localhost", Set.of()));
    }

    @Test
    void nichtAufloesbarIstGesperrt() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AusgehendesZiel.pruefeHost("gibt-es-nicht.example.org", Set.of(), TEST_DNS));
        assertTrue(e.getMessage().contains("nicht auflösbar"));
    }

    @Test
    void allowlistGibtInterneZieleFrei() {
        Set<String> erlaubt = AusgehendesZiel.allowlist(" 192.168.1.224 , Mail-Dev.lan ,");
        assertEquals(Set.of("192.168.1.224", "mail-dev.lan"), erlaubt);
        assertDoesNotThrow(() -> AusgehendesZiel.pruefeHost("192.168.1.224", erlaubt, TEST_DNS));
        assertDoesNotThrow(() -> AusgehendesZiel.pruefeHost("MAIL-DEV.lan", erlaubt, TEST_DNS));
        // Die Freigabe gilt nur für genau diesen Host, nicht für das Netz.
        assertThrows(IllegalArgumentException.class,
                () -> AusgehendesZiel.pruefeHost("192.168.1.225", erlaubt, TEST_DNS));
    }

    @Test
    void allowlistLeerOderNull() {
        assertTrue(AusgehendesZiel.allowlist(null).isEmpty());
        assertTrue(AusgehendesZiel.allowlist("  ").isEmpty());
    }

    @Test
    void urlNurHttpUndHttps() {
        URI ok = AusgehendesZiel.pruefeUrl("https://kalender.example.org/dav/cal/", Set.of(), TEST_DNS);
        assertEquals("kalender.example.org", ok.getHost());
        assertDoesNotThrow(() -> AusgehendesZiel.pruefeUrl("http://93.184.215.14/x.ics", Set.of(), TEST_DNS));
        for (String url : new String[]{"file:///etc/passwd", "ftp://93.184.215.14/x", "gopher://93.184.215.14/",
                "jar:http://93.184.215.14/x!/", "93.184.215.14/x.ics"}) {
            assertThrows(IllegalArgumentException.class, () -> AusgehendesZiel.pruefeUrl(url, Set.of(), TEST_DNS), url);
        }
    }

    @Test
    void urlMitInternemZielOderZugangsdatenIstGesperrt() {
        for (String url : new String[]{"http://127.0.0.1:8080/actuator", "http://169.254.169.254/latest/meta-data/",
                "http://[::1]/", "https://192.168.1.224:1156/api/Server", "http://0x7f000001/",
                "https://user:pw@kalender.example.org/", "http:///nur-pfad", "", " "}) {
            assertThrows(IllegalArgumentException.class, () -> AusgehendesZiel.pruefeUrl(url, Set.of(), TEST_DNS), url);
        }
        assertThrows(IllegalArgumentException.class, () -> AusgehendesZiel.pruefeUrl(null, Set.of(), TEST_DNS));
    }

    @Test
    void ipv6LiteralInKlammernWirdGeprueft() {
        assertDoesNotThrow(() -> AusgehendesZiel.pruefeUrl("https://[2a00:1450:4001::1]/cal", Set.of(), TEST_DNS));
        assertThrows(IllegalArgumentException.class,
                () -> AusgehendesZiel.pruefeUrl("https://[fd00::1]/cal", Set.of(), TEST_DNS));
    }
}
