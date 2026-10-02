/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars.service;

import ch.plaintext.sidecars.SidecarVerbindung;
import ch.plaintext.sidecars.entity.AuthZustand;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * HTTP-Seite des Plaintext-Sidecar-Protokolls (Karte 1400): Beschreibung holen, Token prüfen und
 * eine deklarierte Fähigkeit aufrufen.
 *
 * <p>Keine Weiterleitungen (eine Antwort darf die Registry nicht an eine andere Adresse schicken),
 * kurze Zeitlimits und eine Obergrenze für gelesene Bytes: ein Sidecar ist ein Container im eigenen
 * Netz, aber die Übersicht darf auch an einem kaputten nicht hängen bleiben.</p>
 */
@Component
public class SidecarProtokollClient {

    static final String BESCHREIBUNG = "/.well-known/plaintext-sidecar";
    static final String AUTH = "/.well-known/plaintext-sidecar/auth";
    static final Duration ABFRAGE_ZEIT = Duration.ofSeconds(3);
    static final Duration AUFRUF_ZEIT = Duration.ofSeconds(60);
    static final int MAX_BESCHREIBUNG = 256 * 1024;
    static final int MAX_ANTWORT = 64 * 1024;

    /**
     * HTTP/1.1 erzwungen: mit der Vorgabe HTTP/2 schickt der JDK-Client bei {@code http://} einen
     * h2c-Upgrade, und Python-Server (uvicorn/gunicorn) lesen dann einen leeren Body (PROD 02.10.2026,
     * Fotos-Sidecar: «Kein Bild im Body»).
     */
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(ABFRAGE_ZEIT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /**
     * Ergebnis einer Abfrage.
     *
     * @param beschreibung gültige Beschreibung oder {@code null}
     * @param roh          Rohtext der Beschreibung (nur wenn gültig)
     * @param fehler       Grund, wenn nicht erreichbar oder ungültig
     * @param ms           Antwortzeit der Beschreibung
     * @param auth         Zustand des Tokens
     */
    public record Abfrage(SidecarBeschreibung beschreibung, String roh, String fehler, int ms, AuthZustand auth) {

        public boolean erreichbar() {
            return beschreibung != null;
        }
    }

    /**
     * Antwort eines Fähigkeits-Aufrufs.
     *
     * @param text Inhalt bei Text oder JSON (gekürzt auf {@value #MAX_ANTWORT} Bytes), sonst {@code null}
     */
    public record Antwort(int status, String inhaltTyp, String text, long bytes, boolean gekuerzt) {
    }

    /** Holt Beschreibung und, wenn nötig, den Token-Zustand. */
    public Abfrage frage(String basisUrl, String token) {
        long start = System.nanoTime();
        String roh;
        int ms;
        try {
            HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(basisUrl + BESCHREIBUNG))
                    .timeout(ABFRAGE_ZEIT).header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            ms = (int) ((System.nanoTime() - start) / 1_000_000);
            try (InputStream in = r.body()) {
                if (r.statusCode() != 200) {
                    return new Abfrage(null, null, "Antwort " + r.statusCode() + " statt 200.", ms, AuthZustand.UNBEKANNT);
                }
                byte[] b = in.readNBytes(MAX_BESCHREIBUNG + 1);
                if (b.length > MAX_BESCHREIBUNG) {
                    return new Abfrage(null, null, "Beschreibung grösser als 256 KB.", ms, AuthZustand.UNBEKANNT);
                }
                roh = new String(b, StandardCharsets.UTF_8);
            }
        } catch (IOException | IllegalArgumentException e) {
            return new Abfrage(null, null, "Nicht erreichbar: " + kurz(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
                    (int) ((System.nanoTime() - start) / 1_000_000), AuthZustand.UNBEKANNT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Abfrage(null, null, "Abfrage unterbrochen.", 0, AuthZustand.UNBEKANNT);
        }
        SidecarBeschreibung b;
        try {
            b = SidecarBeschreibung.lies(roh);
        } catch (SidecarBeschreibung.Ungueltig e) {
            return new Abfrage(null, null, "Kein gültiges Sidecar-Protokoll: " + e.getMessage(), ms, AuthZustand.UNBEKANNT);
        }
        return new Abfrage(b, roh, null, ms, pruefeToken(basisUrl, b, token));
    }

    AuthZustand pruefeToken(String basisUrl, SidecarBeschreibung b, String token) {
        if (b.ohneAuth()) {
            return AuthZustand.NICHT_NOETIG;
        }
        if (token == null || token.isEmpty()) {
            return AuthZustand.KEIN_TOKEN;
        }
        try {
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(basisUrl + AUTH))
                    .timeout(ABFRAGE_ZEIT).header("Authorization", "Bearer " + token).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return switch (r.statusCode()) {
                case 200 -> AuthZustand.GUELTIG;
                case 401, 403 -> AuthZustand.UNGUELTIG;
                default -> AuthZustand.UNBEKANNT;
            };
        } catch (IOException e) {
            return AuthZustand.UNBEKANNT;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AuthZustand.UNBEKANNT;
        }
    }

    /**
     * Ruft eine deklarierte Fähigkeit auf. Methode und Pfad kommen ausschliesslich aus der
     * Beschreibung des Sidecars, nie vom Aufrufer.
     *
     * @param parameter Query-Parameter (dürfen leer sein)
     * @param jsonBody  Body als JSON oder {@code null}
     */
    public Antwort rufe(SidecarVerbindung v, SidecarBeschreibung.Faehigkeit f, Map<String, String> parameter, String jsonBody)
            throws IOException, InterruptedException {
        String query = parameter == null || parameter.isEmpty() ? "" : "?" + parameter.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest.BodyPublisher body = jsonBody == null || jsonBody.isEmpty()
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8);
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(v.basisUrl() + f.pfad() + query))
                .timeout(AUFRUF_ZEIT).method(f.methode(), body);
        if (jsonBody != null && !jsonBody.isEmpty()) {
            rb.header("Content-Type", "application/json");
        }
        if (f.auth() && v.authorization() != null) {
            rb.header("Authorization", v.authorization());
        }
        HttpResponse<InputStream> r = http.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
        String typ = r.headers().firstValue("Content-Type").orElse("");
        try (InputStream in = r.body()) {
            if (istText(typ)) {
                byte[] b = in.readNBytes(MAX_ANTWORT + 1);
                boolean gekuerzt = b.length > MAX_ANTWORT;
                int n = Math.min(b.length, MAX_ANTWORT);
                long rest = gekuerzt ? in.transferTo(java.io.OutputStream.nullOutputStream()) : 0;
                return new Antwort(r.statusCode(), typ, new String(b, 0, n, StandardCharsets.UTF_8), b.length + rest, gekuerzt);
            }
            long bytes = in.transferTo(java.io.OutputStream.nullOutputStream());
            return new Antwort(r.statusCode(), typ, null, bytes, false);
        }
    }

    static boolean istText(String typ) {
        String t = typ == null ? "" : typ.toLowerCase(java.util.Locale.ROOT);
        return t.startsWith("text/") || t.contains("json") || t.contains("xml");
    }

    private static String kurz(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
