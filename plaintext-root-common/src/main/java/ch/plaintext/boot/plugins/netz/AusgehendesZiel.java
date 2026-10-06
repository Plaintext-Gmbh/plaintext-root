/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.netz;

import ch.plaintext.arch.StabileApi;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Karte 1406: aus plaintext-app-interfaces ({@code ch.plaintext.security.AusgehendesZiel}, Karte 1362)
 * nach Root gehoben, damit auch Root-Module (Speicher-Ablagen) ausgehende Ziele prüfen. Eigenes Paket,
 * weil dieselbe Klasse unter demselben Namen sonst zweimal auf dem Klassenpfad der App läge; die
 * App-Kopie wird abgelöst, sobald die App darauf umgestellt ist.
 *
 * <p>Karte 1362 (HC1/HC2): Prüfung eines ausgehenden, vom Benutzer bestimmten Ziels, bevor die App
 * dorthin eine Verbindung aufbaut (SSRF-Schutz).
 *
 * <p>Ein Mandant legt die Remote-URL eines Kalenders bzw. den IMAP-/SMTP-Host eines Mailkontos selbst
 * fest. Ohne Prüfung kann er die App so gegen Adressen im Container-, Docker- oder Heimnetz richten
 * (127.0.0.1, 192.168.x, 169.254.169.254, …) und aus den Fehlermeldungen und Laufzeiten ableiten, was
 * dort läuft. Erlaubt sind deshalb nur Hosts, deren <b>sämtliche</b> aufgelösten Adressen öffentlich
 * sind. Interne Ziele, die bewusst gebraucht werden (die LAN-Mailsenke der guild), stehen in der
 * Allowlist {@code plaintext.ausgehend.erlaubte-hosts} (kommagetrennt, exakter Hostname bzw. IP).</p>
 *
 * <p>Bewusst nicht abgedeckt: DNS-Rebinding zwischen Prüfung und Verbindungsaufbau. Das Ziel wird hier
 * aufgelöst und vom HTTP-/Mail-Client erneut; ein Angreifer mit eigener DNS-Zone könnte dazwischen die
 * Antwort wechseln. Für die heutige Lage (nur eigene Admins, Karte 1362 „latent niedrig") ist das
 * angemessen; ein vollständiger Schutz bräuchte einen eigenen Resolver im Client.</p>
 */
@StabileApi("SSRF-Pruefung ausgehender Ziele fuer Consumer-Apps (Karte 1362/1406/1409)")
public final class AusgehendesZiel {

    /** Name der Allowlist-Eigenschaft, damit Fehlermeldungen und Konfiguration denselben Schlüssel nennen. */
    public static final String EIGENSCHAFT_ERLAUBTE_HOSTS = "plaintext.ausgehend.erlaubte-hosts";

    /** Namensauflösung, im Test ersetzbar. */
    @FunctionalInterface
    public interface Aufloeser {
        InetAddress[] aufloesen(String host) throws UnknownHostException;
    }

    private static final Aufloeser DNS = InetAddress::getAllByName;

    private AusgehendesZiel() {
    }

    /**
     * Zerlegt den Wert der Allowlist-Eigenschaft (kommagetrennt, Leerzeichen egal, Gross-/Kleinschreibung egal).
     *
     * @param wert Eigenschaftswert oder {@code null}
     * @return Menge der erlaubten Hosts, nie {@code null}
     */
    public static Set<String> allowlist(String wert) {
        if (wert == null || wert.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(wert.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Prüft eine URL: nur {@code http}/{@code https}, mit Host, und der Host muss {@link #pruefeHost} bestehen.
     *
     * @return die geparste URI
     * @throws IllegalArgumentException wenn das Ziel nicht erlaubt ist
     */
    public static URI pruefeUrl(String url, Collection<String> erlaubteHosts) {
        return pruefeUrl(url, erlaubteHosts, DNS);
    }

    /** Wie {@link #pruefeUrl(String, Collection)}, mit eigener Namensauflösung (Tests). */
    public static URI pruefeUrl(String url, Collection<String> erlaubteHosts, Aufloeser aufloeser) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Keine URL angegeben.");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException _) {
            throw new IllegalArgumentException("Ungültige URL.");
        }
        String schema = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!schema.equals("http") && !schema.equals("https")) {
            throw new IllegalArgumentException("Nur http- und https-Adressen sind erlaubt.");
        }
        if (uri.getRawUserInfo() != null) {
            // user:pass@host verschleiert das Ziel in Logs und Oberflächen; Zugangsdaten gehören in die Felder.
            throw new IllegalArgumentException("Zugangsdaten in der URL sind nicht erlaubt.");
        }
        pruefeHost(uri.getHost(), erlaubteHosts, aufloeser);
        return uri;
    }

    /**
     * Prüft einen Hostnamen oder eine IP-Adresse.
     *
     * @throws IllegalArgumentException wenn der Host fehlt, nicht auflösbar ist oder auf eine nicht
     *                                  öffentliche Adresse zeigt und nicht in der Allowlist steht
     */
    public static void pruefeHost(String host, Collection<String> erlaubteHosts) {
        pruefeHost(host, erlaubteHosts, DNS);
    }

    /** Wie {@link #pruefeHost(String, Collection)}, mit eigener Namensauflösung (Tests). */
    public static void pruefeHost(String host, Collection<String> erlaubteHosts, Aufloeser aufloeser) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Kein Host angegeben.");
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        if (erlaubteHosts != null && erlaubteHosts.contains(h)) {
            return;
        }
        InetAddress[] adressen;
        try {
            adressen = aufloeser.aufloesen(h);
        } catch (UnknownHostException _) {
            throw new IllegalArgumentException("Host '" + h + "' ist nicht auflösbar.");
        }
        if (adressen == null || adressen.length == 0) {
            throw new IllegalArgumentException("Host '" + h + "' ist nicht auflösbar.");
        }
        for (InetAddress a : adressen) {
            if (!istOeffentlich(a)) {
                throw new IllegalArgumentException("Host '" + h + "' zeigt auf eine interne Adresse. "
                        + "Interne Ziele müssen in " + EIGENSCHAFT_ERLAUBTE_HOSTS + " freigegeben werden.");
            }
        }
    }

    /**
     * {@code true}, wenn die Adresse im öffentlichen Internet liegt — also weder Loopback, Any-Local,
     * Link-Local (inkl. 169.254.169.254), privat (10/8, 172.16/12, 192.168/16), Carrier-Grade-NAT
     * (100.64/10), Multicast, IPv6-ULA (fc00::/7) noch einer der reservierten IPv4-Blöcke ist.
     */
    public static boolean istOeffentlich(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            return istOeffentlichV4(b);
        }
        if (a instanceof Inet6Address) {
            return istOeffentlichV6(b);
        }
        return false;
    }

    /** Reservierte IPv4-Bloecke ausserhalb der InetAddress-Pruefungen (Karte 1416, Sonar java:S3776). */
    private static boolean istOeffentlichV4(byte[] b) {
        int o1 = b[0] & 0xff;
        int o2 = b[1] & 0xff;
        int o3 = b[2] & 0xff;
        if (o1 == 0) {
            return false;                                   // 0.0.0.0/8
        }
        if (o1 == 100 && o2 >= 64 && o2 <= 127) {
            return false;                                   // 100.64.0.0/10 CGNAT
        }
        if (o1 == 192 && o2 == 0 && o3 == 0) {
            return false;                                   // 192.0.0.0/24 IETF
        }
        if (o1 == 198 && (o2 == 18 || o2 == 19)) {
            return false;                                   // 198.18.0.0/15 Benchmark
        }
        return o1 < 240;                                    // 240.0.0.0/4 reserviert, Broadcast
    }

    /** IPv6-ULA und veraltetes Site-Local. IPv4-kompatible/-gemappte Adressen liefert Java bereits als Inet4Address. */
    private static boolean istOeffentlichV6(byte[] b) {
        int o1 = b[0] & 0xff;
        if ((o1 & 0xfe) == 0xfc) {
            return false;                                   // fc00::/7 ULA
        }
        return !((o1 == 0xfe) && ((b[1] & 0xc0) == 0xc0));  // fec0::/10 Site-Local (veraltet)
    }
}
