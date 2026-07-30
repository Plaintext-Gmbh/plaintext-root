/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Ermittelt die Client-IP, die als Rate-Limit-Schluessel dient.
 *
 * <p>SECURITY (Karte 303, Befund 2): Frueher wurde ungeprueft das <em>erste</em> Element von
 * {@code X-Forwarded-For} genommen. Dieses Element stammt aber vom Client selbst — jeder Request
 * mit {@code X-Forwarded-For: 1.2.3.<zufall>} bekam einen frischen Bucket und umging damit
 * saemtliche Limits. Ein pauschales Ignorieren des Headers ist aber genauso falsch: hinter dem
 * Reverse-Proxy landeten sonst alle Nutzer im selben Bucket und wuerden sich gegenseitig
 * aussperren.
 *
 * <p>Korrekt ist das Standardverfahren „von rechts nach links bis zum ersten nicht
 * vertrauenswuerdigen Hop":
 * <ol>
 *   <li>Ist die echte Peer-Adresse ({@code getRemoteAddr()}) <b>kein</b> vertrauenswuerdiger Proxy,
 *       ist der Request direkt bei uns angekommen — {@code X-Forwarded-For} wird komplett
 *       ignoriert, der Peer ist der Client.</li>
 *   <li>Sonst wird {@code X-Forwarded-For} von rechts nach links durchlaufen. Jeder Proxy
 *       <em>haengt</em> die von ihm gesehene Peer-Adresse rechts an; alles was ein Angreifer
 *       selbst schreiben kann, steht deshalb zwingend <em>links</em> vom echten Wert. Der erste
 *       Eintrag von rechts, der kein vertrauenswuerdiger Proxy ist, ist die echte Client-IP.</li>
 *   <li>Sind alle Eintraege vertrauenswuerdig (typisch: Zugriff direkt aus dem LAN), wird der
 *       linkeste Eintrag genommen — sonst teilten sich alle LAN-Nutzer einen Bucket.</li>
 * </ol>
 *
 * <p>Konkrete Topologie von plaintext-root: Cloudflare-Edge → {@code cloudflared}-Tunnel →
 * {@code plaintext-*-nginx} ({@code proxy_add_x_forwarded_for}) → App. Beide internen Hops liegen
 * in privaten Netzen und sind per Default vertrauenswuerdig; die von Cloudflare angehaengte echte
 * Client-IP ist damit der erste nicht vertrauenswuerdige Eintrag von rechts.
 */
@Slf4j
public class ClientIpResolver {

    /**
     * Default-Liste vertrauenswuerdiger Proxy-Netze: Loopback, RFC1918, Link-Local, IPv6-ULA.
     * Deckt sowohl das Docker-Netz als auch die NAS-/LAN-Adresse des Tunnel-Containers ab.
     * Betreiber mit anderer Topologie sollten die Liste ueber
     * {@code plaintext.rate-limit.trusted-proxies} enger fassen.
     */
    public static final String DEFAULT_TRUSTED_PROXIES =
            "127.0.0.0/8,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,169.254.0.0/16,fc00::/7,fe80::/10";

    /** Obergrenze fuer die Anzahl ausgewerteter XFF-Elemente (Schutz vor Riesen-Headern). */
    private static final int MAX_XFF_ELEMENTS = 32;

    private final List<CidrRange> trustedProxies;

    public ClientIpResolver(String trustedProxyList) {
        this.trustedProxies = parseRanges(trustedProxyList);
    }

    private static List<CidrRange> parseRanges(String list) {
        List<CidrRange> ranges = new ArrayList<>();
        if (list == null || list.isBlank()) {
            return ranges;
        }
        for (String raw : list.split(",")) {
            String cidr = raw.trim();
            if (cidr.isEmpty()) {
                continue;
            }
            CidrRange range = CidrRange.parse(cidr);
            if (range == null) {
                log.warn("Ignoriere unparsbaren Trusted-Proxy-Eintrag: {}", cidr);
            } else {
                ranges.add(range);
            }
        }
        return ranges;
    }

    /**
     * @return Rate-Limit-Schluessel (Client-IP als Text), nie {@code null}.
     */
    public String resolve(HttpServletRequest request) {
        byte[] peer = toAddress(request.getRemoteAddr());
        if (peer == null) {
            // Kann praktisch nicht passieren; lieber ein gemeinsamer Bucket als gar kein Limit.
            return "unknown";
        }
        String peerText = format(peer);
        if (!isTrustedProxy(peer)) {
            return peerText;
        }

        String header = request.getHeader("X-Forwarded-For");
        if (header == null || header.isBlank()) {
            return peerText;
        }

        String[] elements = header.split(",", MAX_XFF_ELEMENTS + 1);
        int last = Math.min(elements.length, MAX_XFF_ELEMENTS) - 1;
        byte[] leftmostValid = null;
        for (int i = last; i >= 0; i--) {
            byte[] candidate = toAddress(elements[i]);
            if (candidate == null) {
                // Kein IP-Literal: von einem echten Proxy stammt so etwas nicht. Nicht als
                // Schluessel verwenden (sonst waere der Schluesselraum beliebig gross).
                continue;
            }
            leftmostValid = candidate;
            if (!isTrustedProxy(candidate)) {
                return format(candidate);
            }
        }
        // Alle Hops vertrauenswuerdig -> Zugriff aus dem internen Netz.
        return leftmostValid != null ? format(leftmostValid) : peerText;
    }

    boolean isTrustedProxy(byte[] address) {
        for (CidrRange range : trustedProxies) {
            if (range.contains(address)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parst ein IP-Literal. Gibt {@code null} zurueck, wenn der Text kein Literal ist — es wird
     * bewusst <b>keine</b> Namensaufloesung ausgeloest (ein XFF-Header darf keinen DNS-Lookup
     * anstossen koennen).
     */
    static byte[] toAddress(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end < 0) {
                return null;
            }
            value = value.substring(1, end);
        } else if (value.chars().filter(c -> c == ':').count() == 1) {
            // "1.2.3.4:56789" — Port abschneiden.
            value = value.substring(0, value.indexOf(':'));
        }
        int percent = value.indexOf('%');
        if (percent >= 0) {
            value = value.substring(0, percent);
        }
        if (value.isEmpty() || value.length() > 45 || !isLiteral(value)) {
            return null;
        }
        try {
            return unwrapV4Mapped(InetAddress.getByName(value).getAddress());
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static boolean isLiteral(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                    || c == '.' || c == ':';
            if (!ok) {
                return false;
            }
        }
        // Ein reiner Hex-/Dezimalstring ohne Trenner ist kein IP-Literal, waere fuer
        // InetAddress.getByName aber eine gueltige 32-Bit-Zahl ("123" -> 0.0.0.123).
        return value.indexOf('.') >= 0 || value.indexOf(':') >= 0;
    }

    /** {@code ::ffff:1.2.3.4} auf {@code 1.2.3.4} zurueckfuehren, damit Vergleiche greifen. */
    private static byte[] unwrapV4Mapped(byte[] address) {
        if (address.length != 16) {
            return address;
        }
        for (int i = 0; i < 10; i++) {
            if (address[i] != 0) {
                return address;
            }
        }
        if (address[10] == (byte) 0xff && address[11] == (byte) 0xff) {
            return Arrays.copyOfRange(address, 12, 16);
        }
        return address;
    }

    private static String format(byte[] address) {
        try {
            return InetAddress.getByAddress(address).getHostAddress();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }

    /** Ein CIDR-Bereich, verglichen auf Byte-Ebene. */
    record CidrRange(byte[] prefix, int bits) {

        static CidrRange parse(String cidr) {
            int slash = cidr.indexOf('/');
            String host = slash < 0 ? cidr : cidr.substring(0, slash);
            byte[] address = toAddress(host);
            if (address == null) {
                return null;
            }
            int bits = address.length * 8;
            if (slash >= 0) {
                try {
                    bits = Integer.parseInt(cidr.substring(slash + 1).trim());
                } catch (NumberFormatException e) {
                    return null;
                }
                if (bits < 0 || bits > address.length * 8) {
                    return null;
                }
            }
            return new CidrRange(address, bits);
        }

        boolean contains(byte[] address) {
            if (address.length != prefix.length) {
                return false;
            }
            int fullBytes = bits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != prefix[i]) {
                    return false;
                }
            }
            int remaining = bits % 8;
            if (remaining == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remaining)) & 0xFF;
            return (address[fullBytes] & mask) == (prefix[fullBytes] & mask);
        }
    }
}
