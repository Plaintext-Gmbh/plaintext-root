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
 * Determines the client IP that serves as the rate-limit key.
 *
 * <p>SECURITY (card 303, finding 2): previously the <em>first</em> element of
 * {@code X-Forwarded-For} was taken without any check. That element, however, comes from the client
 * itself — every request with {@code X-Forwarded-For: 1.2.3.<zufall>} got a fresh bucket and thereby
 * bypassed all limits. Ignoring the header altogether is just as wrong, though: behind the
 * reverse proxy all users would otherwise end up in the same bucket and would lock each other
 * out.
 *
 * <p>The correct approach is the standard procedure "from right to left up to the first
 * untrusted hop":
 * <ol>
 *   <li>If the real peer address ({@code getRemoteAddr()}) is <b>not</b> a trusted proxy,
 *       the request arrived at us directly — {@code X-Forwarded-For} is ignored
 *       completely, the peer is the client.</li>
 *   <li>Otherwise {@code X-Forwarded-For} is walked from right to left. Every proxy
 *       <em>appends</em> the peer address it saw on the right; everything an attacker can
 *       write themselves therefore necessarily stands <em>to the left</em> of the real value. The first
 *       entry from the right that is not a trusted proxy is the real client IP.</li>
 *   <li>If all entries are trusted (typically: access directly from the LAN), the
 *       leftmost entry is taken — otherwise all LAN users would share one bucket.</li>
 * </ol>
 *
 * <p>The concrete topology of plaintext-root: Cloudflare edge → {@code cloudflared} tunnel →
 * {@code plaintext-*-nginx} ({@code proxy_add_x_forwarded_for}) → app. Both internal hops lie
 * in private networks and are trusted by default; the real client IP appended by Cloudflare
 * is therefore the first untrusted entry from the right.
 */
@Slf4j
public class ClientIpResolver {

    /**
     * Default list of trusted proxy networks: loopback, RFC1918, link-local, IPv6 ULA.
     * Covers both the Docker network and the NAS/LAN address of the tunnel container.
     * Operators with a different topology should narrow the list via
     * {@code plaintext.rate-limit.trusted-proxies}.
     */
    public static final String DEFAULT_TRUSTED_PROXIES =
            "127.0.0.0/8,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,169.254.0.0/16,fc00::/7,fe80::/10";

    /** Upper limit for the number of evaluated XFF elements (protection against huge headers). */
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
     * @return rate-limit key (client IP as text), never {@code null}.
     */
    public String resolve(HttpServletRequest request) {
        byte[] peer = toAddress(request.getRemoteAddr());
        if (peer == null) {
            // Practically cannot happen; better a shared bucket than no limit at all.
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
                // Not an IP literal: something like this does not come from a real proxy. Do not use
                // as a key (otherwise the key space would be arbitrarily large).
                continue;
            }
            leftmostValid = candidate;
            if (!isTrustedProxy(candidate)) {
                return format(candidate);
            }
        }
        // All hops trusted -> access from the internal network.
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
     * Parses an IP literal. Returns {@code null} if the text is not a literal — deliberately
     * <b>no</b> name resolution is triggered (an XFF header must not be able to trigger a DNS
     * lookup).
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
            // "1.2.3.4:56789" — cut off the port.
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
        // A pure hex/decimal string without separators is not an IP literal, but would be
        // a valid 32-bit number for InetAddress.getByName ("123" -> 0.0.0.123).
        return value.indexOf('.') >= 0 || value.indexOf(':') >= 0;
    }

    /** Reduce {@code ::ffff:1.2.3.4} to {@code 1.2.3.4}, so that comparisons work. */
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

    /** A CIDR range, compared at the byte level. */
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
