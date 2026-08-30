/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Card 303, finding 2: the rate-limit key must not be determinable by the client.
 */
@DisplayName("ClientIpResolver")
class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver(ClientIpResolver.DEFAULT_TRUSTED_PROXIES);

    private static HttpServletRequest request(String remoteAddr, String xff) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        if (xff != null) {
            request.addHeader("X-Forwarded-For", xff);
        }
        return request;
    }

    @Test
    @DisplayName("Direkter Client ohne Proxy: XFF wird ignoriert")
    void directClientIgnoresForwardedHeader() {
        assertEquals("203.0.113.5", resolver.resolve(request("203.0.113.5", "1.2.3.4")));
    }

    @Test
    @DisplayName("Ein Proxy-Hop: die vom Proxy angehaengte Adresse gewinnt")
    void singleProxyHop() {
        assertEquals("203.0.113.9", resolver.resolve(request("192.168.1.224", "203.0.113.9")));
    }

    @Test
    @DisplayName("PROD-Topologie Cloudflare -> cloudflared -> nginx -> App")
    void productionTopology() {
        // The attacker appends "1.2.3.4" themselves; Cloudflare appends its real IP on the right,
        // nginx then the address of the tunnel container.
        assertEquals("198.51.100.7",
                resolver.resolve(request("192.168.208.1", "1.2.3.4, 198.51.100.7, 192.168.1.224")));
    }

    @Test
    @DisplayName("Beliebig viele gefaelschte Elemente aendern den Schluessel nicht")
    void spoofedPrefixIsIgnored() {
        String spoof = "8.8.8.8, 9.9.9.9, 1.1.1.1, 5.5.5.5";
        assertEquals("198.51.100.7",
                resolver.resolve(request("192.168.208.1", spoof + ", 198.51.100.7, 192.168.1.224")));
    }

    @Test
    @DisplayName("Muell im Header wird uebersprungen und nie zum Schluessel")
    void garbageElementsAreSkipped() {
        assertEquals("198.51.100.7",
                resolver.resolve(request("192.168.208.1",
                        "not-an-ip, 198.51.100.7, 192.168.1.224, ${jndi:x}")));
    }

    @Test
    @DisplayName("Kein XFF: Peer-Adresse")
    void noForwardedHeader() {
        assertEquals("192.168.1.224", resolver.resolve(request("192.168.1.224", null)));
    }

    @Test
    @DisplayName("Nur interne Hops (LAN-Zugriff): linkester Eintrag, damit nicht alle LAN-Nutzer "
            + "denselben Bucket teilen")
    void allHopsInternalUsesLeftmost() {
        assertEquals("192.168.1.55", resolver.resolve(request("192.168.208.1", "192.168.1.55")));
        assertEquals("10.1.2.3", resolver.resolve(request("192.168.208.1", "10.1.2.3, 192.168.1.224")));
    }

    @Test
    @DisplayName("Port-Angaben und Klammern werden abgeschnitten")
    void portsAndBracketsAreStripped() {
        assertEquals("203.0.113.9", resolver.resolve(request("192.168.1.224", "203.0.113.9:51234")));
        assertEquals("2001:db8:0:0:0:0:0:1",
                resolver.resolve(request("192.168.1.224", "[2001:db8::1]:443")));
    }

    @Test
    @DisplayName("IPv4-mapped IPv6 wird auf IPv4 normalisiert (sonst zwei Buckets pro Client)")
    void ipv4MappedIsNormalised() {
        assertEquals("203.0.113.9", resolver.resolve(request("::ffff:203.0.113.9", null)));
    }

    @Test
    @DisplayName("IPv6-Loopback und ULA gelten als vertrauenswuerdig")
    void ipv6TrustedRanges() {
        assertEquals("203.0.113.9", resolver.resolve(request("::1", "203.0.113.9")));
        assertEquals("203.0.113.9", resolver.resolve(request("fd00::1", "203.0.113.9")));
    }

    @Test
    @DisplayName("Engere Trusted-Liste: LAN-Adresse ist dann kein Proxy mehr")
    void narrowTrustedList() {
        ClientIpResolver narrow = new ClientIpResolver("127.0.0.1/32,192.168.208.0/20");
        assertEquals("192.168.1.224", narrow.resolve(request("192.168.208.1", "1.2.3.4, 192.168.1.224")));
    }

    @Test
    @DisplayName("Riesen-Header wird gedeckelt und liefert trotzdem einen stabilen Schluessel")
    void oversizedHeaderIsCapped() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            sb.append("1.2.3.").append(i % 250).append(", ");
        }
        sb.append("192.168.1.224");
        // Only the first 32 elements are considered; the 32nd (index 31) is the last
        // evaluable entry. What matters is: the result is deterministic and bounded.
        assertEquals("1.2.3.31", resolver.resolve(request("192.168.208.1", sb.toString())));
    }
}
