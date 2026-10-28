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

    /** Karte 1608: Obergrenze fuer eine Indexdatei. */
    static final int MAX_INDEX_BYTES = 1024 * 1024;

    private final URI plaintext;
    private final URI central;
    private final HttpClient client;
    /** Karte 1618: Verzeichnis einer Registry (normalisiert) -> Authorization-Kopf; nur fuer Ziele darunter. */
    private final java.util.Map<String, String> anmeldungen = new java.util.concurrent.ConcurrentHashMap<>();

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

    /** Karte 1618: HTTP Basic fuer alles unter dem Verzeichnis von {@code index} ({@code benutzerPasswort} = a:b). */
    void anmeldung(URI index, String benutzerPasswort) {
        anmeldungen.put(index.normalize().resolve(".").toString(), "Basic "
                + java.util.Base64.getEncoder().encodeToString(benutzerPasswort.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private HttpRequest.Builder anfrage(URI ziel) {
        HttpRequest.Builder b = HttpRequest.newBuilder(ziel);
        String z = ziel.normalize().toString();
        anmeldungen.forEach((praefix, kopf) -> {
            if (z.startsWith(praefix)) {
                b.header("Authorization", kopf);
            }
        });
        return b;
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
        return laden(adresse(e), temp, "Modul '" + e.name() + "'");
    }

    /**
     * Karte 1608: laedt {@code ziel} nach {@code temp} (gleiche Regeln: nur 200, keine Weiterleitung,
     * {@link #MAX_BYTES}) und gibt den sha256 zurueck. Das Ziel stammt aus einer Registry; vertraut wird
     * ihm nicht, sondern nur der signierten Zeile, gegen deren sha256 der Aufrufer prueft.
     */
    String laden(URI ziel, Path temp, String was) throws IOException, InterruptedException {
        if ("file".equals(ziel.getScheme())) { // lokale Verzeichnis-Registry: gleiche Grenzen, kein Netz
            if (Files.size(Path.of(ziel)) > MAX_BYTES) {
                throw new IOException(was + ": groesser als " + MAX_BYTES + " Bytes");
            }
            Files.copy(Path.of(ziel), temp);
            return sha256(temp);
        }
        HttpRequest anfrage = anfrage(ziel).timeout(Duration.ofMinutes(5)).GET().build();
        HttpResponse<InputStream> antwort = client.send(anfrage, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream rein = antwort.body()) {
            if (antwort.statusCode() != 200) {
                throw new IOException(was + ": " + ziel + " antwortet " + antwort.statusCode()
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
                        throw new IOException(was + ": groesser als " + MAX_BYTES + " Bytes");
                    }
                    raus.write(puffer, 0, n);
                }
            }
            return HexFormat.of().formatHex(sha.digest());
        }
    }

    /** Karte 1608: eine Indexdatei (UTF-8, hoechstens {@link #MAX_INDEX_BYTES}), sonst wie {@link #laden}. */
    String text(URI ziel) throws IOException, InterruptedException {
        if ("file".equals(ziel.getScheme())) {
            if (Files.size(Path.of(ziel)) > MAX_INDEX_BYTES) {
                throw new IOException(ziel + ": Index groesser als " + MAX_INDEX_BYTES + " Bytes");
            }
            return Files.readString(Path.of(ziel), java.nio.charset.StandardCharsets.UTF_8);
        }
        HttpRequest anfrage = anfrage(ziel).timeout(Duration.ofSeconds(30)).GET().build();
        HttpResponse<InputStream> antwort = client.send(anfrage, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream rein = antwort.body()) {
            if (antwort.statusCode() != 200) {
                throw new IOException(ziel + " antwortet " + antwort.statusCode());
            }
            byte[] b = rein.readNBytes(MAX_INDEX_BYTES + 1);
            if (b.length > MAX_INDEX_BYTES) {
                throw new IOException(ziel + ": Index groesser als " + MAX_INDEX_BYTES + " Bytes");
            }
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
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
