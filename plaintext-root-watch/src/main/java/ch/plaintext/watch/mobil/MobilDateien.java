/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.mobil;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * The two static files of the framework, read once, addressed by their content (Karte 1355).
 *
 * <h2>Why the address carries a hash of the content</h2>
 *
 * <p>So that the phone can keep them for a year without ever asking again, and still sees a
 * change the moment it is deployed. Karte 1311 solved the same problem for the JSF resources
 * with the project version; that works, but it throws away the cache on every release even
 * when the file did not change. A hash of the content changes exactly when the content does.
 * On a 4G link the difference is one request of 150 ms per file and page — the file itself
 * is small, the round trip is not.</p>
 *
 * <p>{@code watch.css} is the very file the Facelet pages use, read from the same place. Two
 * copies of the stylesheet would drift apart within a week.</p>
 */
@Component
public class MobilDateien {

    /** Dateiname (Karte 1416, Sonar java:S1192). */
    private static final String MOBIL_JS = "mobil.js";

    /** Dateiname (Karte 1416, Sonar java:S1192). */
    private static final String WATCH_CSS = "watch.css";

    /** A file as it is served. */
    public record Datei(String name, String typ, byte[] inhalt, String marke) {

        // Records vergleichen Arrays nach Referenz; der Inhalt zaehlt (Karte 1416, Sonar java:S6218).
        @Override
        public boolean equals(Object o) {
            return o instanceof Datei d && java.util.Objects.equals(name, d.name) && java.util.Objects.equals(typ, d.typ)
                    && java.util.Arrays.equals(inhalt, d.inhalt) && java.util.Objects.equals(marke, d.marke);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(name, typ, java.util.Arrays.hashCode(inhalt), marke);
        }

        @Override
        public String toString() {
            return "Datei[" + name + ", " + typ + ", " + (inhalt == null ? 0 : inhalt.length) + " Bytes, " + marke + "]";
        }
    }

    private static final Map<String, String> QUELLEN = Map.of(
            WATCH_CSS, "META-INF/resources/watch/watch.css",
            MOBIL_JS, "META-INF/resources/watch/mobil.js");

    private static final Map<String, String> TYPEN = Map.of(
            WATCH_CSS, "text/css;charset=UTF-8",
            MOBIL_JS, "text/javascript;charset=UTF-8");

    private final Map<String, Datei> dateien;

    public MobilDateien() {
        this.dateien = Map.of(
                WATCH_CSS, lies(WATCH_CSS),
                MOBIL_JS, lies(MOBIL_JS));
    }

    public Optional<Datei> datei(String name) {
        return Optional.ofNullable(name).map(dateien::get);
    }

    /** Address of a file relative to the context path, with its content mark. */
    public String adresse(String name) {
        Datei d = dateien.get(name);
        return MobilWatchPage.PFAD + "_/" + name + "?v=" + (d == null ? "" : d.marke());
    }

    private static Datei lies(String name) {
        String pfad = QUELLEN.get(name);
        try (InputStream in = MobilDateien.class.getClassLoader().getResourceAsStream(pfad)) {
            if (in == null) {
                throw new IllegalStateException("Mobil-Framework: " + pfad + " fehlt im Klassenpfad");
            }
            byte[] inhalt = in.readAllBytes();
            return new Datei(name, TYPEN.get(name), inhalt, marke(inhalt));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String marke(byte[] inhalt) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(inhalt);
            return HexFormat.of().formatHex(h, 0, 6);
        } catch (NoSuchAlgorithmException _) {
            // SHA-256 ist in jeder JVM Pflicht; ohne sie waere die Marke die Laenge.
            return Integer.toHexString(new String(inhalt, StandardCharsets.UTF_8).hashCode());
        }
    }
}
