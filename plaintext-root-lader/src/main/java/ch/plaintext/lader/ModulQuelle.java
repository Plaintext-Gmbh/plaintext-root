/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.lader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Holt Modul-Jars nur von fest einkompilierten Basis-URLs (Karte 1513).
 *
 * <p>Die Konfig im PV enthaelt nie eine URL: die Adresse entsteht aus Basis + Maven-Pfad
 * (Gruppe/Name/Version, eng geprueft in {@link ModulKonfig}). Eigene Artefakte ({@code ch.plaintext})
 * kommen von maven.plaintext.ch, alles andere von Maven Central. Weiterleitungen werden nicht verfolgt,
 * damit keine Antwort den Download auf ein anderes Ziel umlenken kann. Eine SSRF-Pruefung wie
 * {@code AusgehendesZiel} braucht es darum nicht: es gibt kein vom Benutzer bestimmtes Ziel, und der
 * Lader muss ohne root-common (nur JDK) laufen.
 */
final class ModulQuelle {

    static final URI PLAINTEXT = URI.create("https://maven.plaintext.ch/releases/");
    static final URI CENTRAL = URI.create("https://repo1.maven.org/maven2/");

    /** Obergrenze je Jar, damit eine falsche Antwort das PV nicht fuellt. */
    static final long MAX_BYTES = 300L * 1024 * 1024;

    private final URI plaintext;
    private final URI central;
    private final HttpClient client;

    ModulQuelle() {
        this(PLAINTEXT, CENTRAL);
    }

    /** Nur fuer Tests: andere feste Basen (z.B. ein lokaler Server). */
    ModulQuelle(URI plaintext, URI central) {
        this.plaintext = plaintext;
        this.central = central;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    URI adresse(ModulKonfig.Eintrag e) {
        URI basis = ModulKonfig.istPlaintext(e.gruppe()) ? plaintext : central;
        URI ziel = basis.resolve(e.mavenPfad());
        // Zweite Sperre neben dem Muster in ModulKonfig: das Ziel muss unter der Basis liegen.
        if (!ziel.normalize().toString().startsWith(basis.toString())
                || !ziel.getHost().equals(basis.getHost()) || ziel.getPort() != basis.getPort()) {
            throw new IllegalArgumentException("Modul '" + e.name() + "': Adresse ausserhalb der Quelle: " + ziel);
        }
        return ziel;
    }

    /**
     * Laedt das Jar nach {@code temp} und gibt dessen sha256 zurueck. Wirft bei jedem anderen Status als 200
     * (auch bei Weiterleitung) und bei Ueberschreiten von {@link #MAX_BYTES}.
     */
    String laden(ModulKonfig.Eintrag e, Path temp) throws IOException, InterruptedException {
        URI ziel = adresse(e);
        HttpRequest anfrage = HttpRequest.newBuilder(ziel).timeout(Duration.ofMinutes(5)).GET().build();
        HttpResponse<InputStream> antwort = client.send(anfrage, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream rein = antwort.body()) {
            if (antwort.statusCode() != 200) {
                throw new IOException("Modul '" + e.name() + "': " + ziel + " antwortet " + antwort.statusCode()
                        + (antwort.statusCode() / 100 == 3 ? " (Weiterleitung wird nicht verfolgt)" : ""));
            }
            MessageDigest sha = sha256();
            long gesamt = 0;
            try (DigestInputStream d = new DigestInputStream(rein, sha);
                 OutputStream raus = Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW)) {
                byte[] puffer = new byte[64 * 1024];
                int n;
                while ((n = d.read(puffer)) > 0) {
                    gesamt += n;
                    if (gesamt > MAX_BYTES) {
                        throw new IOException("Modul '" + e.name() + "': groesser als " + MAX_BYTES + " Bytes");
                    }
                    raus.write(puffer, 0, n);
                }
            }
            return HexFormat.of().formatHex(sha.digest());
        }
    }

    static String sha256(Path datei) throws IOException {
        MessageDigest sha = sha256();
        try (InputStream rein = new DigestInputStream(Files.newInputStream(datei), sha)) {
            rein.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
