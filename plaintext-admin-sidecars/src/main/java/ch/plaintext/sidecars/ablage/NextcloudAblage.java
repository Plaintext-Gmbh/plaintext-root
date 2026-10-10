/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars.ablage;

import ch.plaintext.modules.ModulApiUmsetzung;

import ch.plaintext.ablagen.AblageEintrag;
import ch.plaintext.ablagen.DateiAblage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * {@link DateiAblage} auf einer Nextcloud über WebDAV (Karte 1406).
 *
 * <p>Die Wurzel ist {@code <url>/remote.php/dav/files/<benutzer>/<pfad>/}, ausser die URL enthält
 * schon {@code /remote.php/}. Alle Pfade werden segmentweise kodiert; {@code ..}, {@code .}, leere
 * Teile und absolute Pfade werden abgewiesen, ein Zugriff bleibt immer unter der Wurzel. Keine
 * Weiterleitungen (sonst schickte ein Server die Anmeldung woandershin).</p>
 */
@ModulApiUmsetzung(beschreibung = "Stores, reads, lists, moves and deletes files and folders in a Nextcloud folder over WebDAV, without the caller knowing the server or the credentials.",
        seiteneffekte = ModulApiUmsetzung.Seiteneffekte.AUSSEN,
        hinweise = {"Paths stay below the root folder, dot segments are rejected", "No redirects are followed", "The app password is stored encrypted", "Reads at most 50 MB per file"},
        beispiele = {"schreibe(\"diagramme/a.drawio\", bytes)"})
public final class NextcloudAblage implements DateiAblage {

    /** Was in der Fehlermeldung fehlt (Karte 1416, Sonar java:S1192). */
    private static final String ORDNER = "Ordner";

    /** HTTP-Kopf (Karte 1416, Sonar java:S1192). */
    private static final String CONTENT_TYPE = "Content-Type";

    static final Duration ZEIT = Duration.ofSeconds(60);
    static final int MAX_LESEN = 50 * 1024 * 1024;
    private static final String PROPFIND = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>
            """;

    private final String name;
    private final URI wurzel;
    private final String auth;
    private final HttpClient http;

    /**
     * @param url      Nextcloud-Adresse (bereits geprüft) oder WebDAV-URL
     * @param pfad     Ordner in der Nextcloud, z. B. {@code Projekte/drawio}
     * @param passwort App-Passwort im Klartext (nur im Speicher, nie geloggt)
     */
    public NextcloudAblage(String name, String url, String benutzer, String passwort, String pfad, HttpClient http) throws IOException {
        this.name = name;
        this.http = http;
        String basis = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        if (!basis.contains("/remote.php/")) {
            basis = basis + "/remote.php/dav/files/" + URLEncoder.encode(benutzer, StandardCharsets.UTF_8).replace("+", "%20");
        }
        this.wurzel = URI.create(basis + "/" + kodiere(pfad == null ? "" : pfad, true));
        this.auth = "Basic " + Base64.getEncoder().encodeToString((benutzer + ":" + (passwort == null ? "" : passwort))
                .getBytes(StandardCharsets.UTF_8));
    }

    public static HttpClient standardClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public String name() {
        return name;
    }

    /** @return die WebDAV-Wurzel (für die Anzeige, ohne Zugangsdaten) */
    public URI wurzel() {
        return wurzel;
    }

    /** Kodiert einen relativen Pfad; Ergebnis endet mit {@code /}, wenn nicht leer. */
    static String kodiere(String pfad, boolean leerErlaubt) throws IOException {
        if (pfad == null || pfad.contains("\\") || pfad.chars().anyMatch(c -> c < 0x20)) {
            throw new IOException("Ungültiger Pfad.");
        }
        String p = pfad.strip();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.isEmpty()) {
            if (leerErlaubt) {
                return "";
            }
            throw new IOException("Ungültiger Pfad.");
        }
        StringBuilder sb = new StringBuilder();
        for (String teil : p.split("/", -1)) {
            if (teil.isEmpty() || teil.equals("..") || teil.equals(".")) {
                throw new IOException("Ungültiger Pfad.");
            }
            sb.append(URLEncoder.encode(teil, StandardCharsets.UTF_8).replace("+", "%20")).append('/');
        }
        return sb.toString();
    }

    /** Relativer Dateipfad → URI ohne abschliessenden Schrägstrich; absolute Pfade sind nicht erlaubt. */
    URI datei(String pfad) throws IOException {
        if (pfad == null || pfad.startsWith("/")) {
            throw new IOException("Ungültiger Pfad.");
        }
        String k = kodiere(pfad, false);
        return URI.create(wurzel + k.substring(0, k.length() - 1));
    }

    private HttpRequest.Builder anfrage(URI u) {
        return HttpRequest.newBuilder(u).timeout(ZEIT).header("Authorization", auth);
    }

    private <T> HttpResponse<T> sende(HttpRequest r, HttpResponse.BodyHandler<T> h) throws IOException {
        try {
            return http.send(r, h);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Unterbrochen.", e);
        }
    }

    static IOException fehler(int status, String was) {
        return new IOException(switch (status) {
            case 401 -> "Anmeldung abgelehnt (Benutzer oder App-Passwort falsch).";
            case 403 -> "Zugriff verweigert.";
            case 404 -> was + " nicht gefunden.";
            case 301, 302, 303, 307, 308 -> "Der Server leitet weiter; bitte die Adresse direkt angeben.";
            case 507 -> "Kein Speicherplatz mehr in der Nextcloud.";
            default -> was + ": HTTP " + status + ".";
        });
    }

    /**
     * Prüft Anmeldung und Ordner.
     *
     * @return Meldung für die Oberfläche
     */
    public String pruefe() throws IOException {
        int n = Math.max(0, propfind(wurzel).size() - 1);
        return "Verbindung in Ordnung, " + n + " Einträge im Ordner.";
    }

    @Override
    public void schreibe(String pfad, byte[] daten, String inhaltTyp) throws IOException {
        URI ziel = datei(pfad);
        ordnerAnlegen(kodiere(pfad, false), false);
        HttpResponse<Void> r = sende(anfrage(ziel).header(CONTENT_TYPE, inhaltTyp == null ? "application/octet-stream" : inhaltTyp)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(daten)).build(), HttpResponse.BodyHandlers.discarding());
        if (r.statusCode() != 201 && r.statusCode() != 204) {
            throw fehler(r.statusCode(), "Speichern");
        }
    }

    @Override
    public byte[] lies(String pfad) throws IOException {
        HttpResponse<InputStream> r = sende(anfrage(datei(pfad)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = r.body()) {
            if (r.statusCode() != 200) {
                throw fehler(r.statusCode(), "Datei");
            }
            byte[] b = in.readNBytes(MAX_LESEN + 1);
            if (b.length > MAX_LESEN) {
                throw new IOException("Datei grösser als 50 MB.");
            }
            return b;
        }
    }

    @Override
    public boolean existiert(String pfad) throws IOException {
        HttpResponse<Void> r = sende(anfrage(datei(pfad)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
        if (r.statusCode() == 404) {
            return false;
        }
        if (r.statusCode() == 200) {
            return true;
        }
        throw fehler(r.statusCode(), "Datei");
    }

    @Override
    public List<AblageEintrag> liste(String ordner) throws IOException {
        URI u = URI.create(wurzel + kodiere(ordner == null ? "" : ordner, true));
        String basis = wurzel.getPath();
        String selbst = u.getPath();
        List<AblageEintrag> l = new ArrayList<>();
        for (WebDavAntwort.Eintrag e : propfind(u)) {
            String p = e.pfad();
            if (p == null || !p.startsWith(basis) || p.equals(selbst) || (p + "/").equals(selbst)) {
                continue;
            }
            String rel = p.substring(basis.length());
            if (rel.endsWith("/")) {
                rel = rel.substring(0, rel.length() - 1);
            }
            if (!rel.isEmpty()) {
                l.add(new AblageEintrag(rel, e.ordner(), e.groesse(), e.geaendert()));
            }
        }
        return l;
    }

    @Override
    public void loesche(String pfad) throws IOException {
        HttpResponse<Void> r = sende(anfrage(datei(pfad)).DELETE().build(), HttpResponse.BodyHandlers.discarding());
        if (r.statusCode() != 204 && r.statusCode() != 200) {
            throw fehler(r.statusCode(), "Löschen");
        }
    }

    /** Antworten eines PROPFIND mit Tiefe 1: der Ordner selbst und seine direkten Einträge. */
    private List<WebDavAntwort.Eintrag> propfind(URI u) throws IOException {
        HttpResponse<String> r = sende(anfrage(u).header("Depth", "1").header(CONTENT_TYPE, "application/xml; charset=utf-8")
                .method("PROPFIND", HttpRequest.BodyPublishers.ofString(PROPFIND)).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 207) {
            throw fehler(r.statusCode(), ORDNER);
        }
        return WebDavAntwort.lies(r.body());
    }

    /**
     * MKCOL je Segment eines kodierten Pfads ({@code a/b/c/}); 405 = gibt es schon.
     *
     * @param letztes auch das letzte Segment anlegen (sonst nur die Elternordner)
     */
    private void ordnerAnlegen(String kodiert, boolean letztes) throws IOException {
        String[] teile = kodiert.substring(0, kodiert.length() - 1).split("/");
        StringBuilder rel = new StringBuilder();
        for (int i = 0; i < teile.length - (letztes ? 0 : 1); i++) {
            rel.append(teile[i]).append('/');
            HttpResponse<Void> m = sende(anfrage(URI.create(wurzel + rel.toString())).method("MKCOL", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
            if (m.statusCode() != 201 && m.statusCode() != 405) {
                throw fehler(m.statusCode(), ORDNER);
            }
        }
    }

    @Override
    public void legeOrdnerAn(String pfad) throws IOException {
        datei(pfad);
        ordnerAnlegen(kodiere(pfad, false), true);
    }

    @Override
    public void verschiebe(String von, String nach) throws IOException {
        URI quelle = datei(von);
        URI ziel = datei(nach);
        String kv = kodiere(von, false);
        String kn = kodiere(nach, false);
        if (kn.startsWith(kv)) {
            throw new IOException("Ein Ordner lässt sich nicht in sich selbst verschieben.");
        }
        ordnerAnlegen(kn, false);
        HttpResponse<Void> r = sende(anfrage(quelle).header("Destination", ziel.toString()).header("Overwrite", "F")
                .method("MOVE", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
        if (r.statusCode() == 412) {
            throw new IOException("Das Ziel gibt es schon.");
        }
        if (r.statusCode() != 201 && r.statusCode() != 204) {
            throw fehler(r.statusCode(), "Verschieben");
        }
    }

    @Override
    public void loescheOrdner(String pfad, boolean rekursiv) throws IOException {
        URI u = URI.create(datei(pfad) + "/");
        List<WebDavAntwort.Eintrag> l = propfind(u);
        boolean ordner = l.stream().anyMatch(e -> e.ordner() && (u.getPath().equals(e.pfad()) || u.getPath().equals(e.pfad() + "/")));
        if (!ordner) {
            throw new IOException("Kein Ordner.");
        }
        if (!rekursiv && l.size() > 1) {
            throw new DirectoryNotEmptyException(pfad);
        }
        HttpResponse<Void> r = sende(anfrage(u).DELETE().build(), HttpResponse.BodyHandlers.discarding());
        if (r.statusCode() != 204 && r.statusCode() != 200) {
            throw fehler(r.statusCode(), "Löschen");
        }
    }
}
