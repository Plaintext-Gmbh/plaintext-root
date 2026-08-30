/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Schlanker Lesezugriff auf einen OpenBao-/Vault-KV-v2-Speicher — die zweite Quelle hinter den
 * {@code bao:}-Property-Referenzen (Karte 995, Folge aus 427/855).
 *
 * <h2>Warum ein eigener Client und nicht {@code HashiCorpVaultBackend}</h2>
 * <p>Jener liegt in {@code plaintext-admin-secrets}, und dieses Modul haengt von
 * {@code plaintext-root-common} ab — nicht umgekehrt. Ein Zugriff von hier waere ein Zyklus.
 * Ausserdem braucht die Property-Aufloesung deutlich weniger: <b>nur Lesen</b>, kein Schreiben,
 * keine Notizen, keine Konfiguration aus der Datenbank (die es beim Boot noch gar nicht gibt).</p>
 *
 * <h2>Bewusst nur lesend</h2>
 * <p>Diese Klasse kann nichts schreiben. Property-Aufloesung ist ein Lesevorgang; ein
 * Schreibpfad hier waere eine Angriffsflaeche ohne Nutzen.</p>
 *
 * <h2>Das Bootstrap-Geheimnis</h2>
 * <p>Der Token kommt <b>nicht</b> aus einer {@code bao:}-Referenz — das waere ein Zirkel. Er kommt
 * wie das Vaultwarden-Master-Passwort ueber die {@code *_FILE}-Konvention aus einer Datei
 * ({@link VaultwardenSecretFiles}, Karte 942) und steht damit nicht in der Container-Umgebung.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
final class OpenBaoClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String url;
    private final String token;
    private final String mount;
    private final HttpClient http;

    /** Letzte Fehlermeldung, fuer die Fail-fast-Meldung des Resolvers. */
    private volatile String letzterFehler;

    /** {@code true}, wenn der letzte Fehlschlag transient war (Netz/5xx) — steuert den Boot-Retry. */
    private volatile boolean letzterFehlerTransient;

    OpenBaoClient(String url, String token, String mount, int timeoutSeconds) {
        this.url = url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.token = token;
        this.mount = (mount == null || mount.isBlank()) ? "secret" : mount;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Liest ein Feld aus {@code <mount>/data/<pfad>}.
     *
     * @param pfad  Schluesselpfad im KV-Speicher, ohne Mount (z.B. {@code app-smtp})
     * @param feld  Feldname innerhalb des Eintrags (z.B. {@code value})
     * @return der Wert, oder leer wenn Eintrag oder Feld fehlen
     */
    Optional<String> lies(String pfad, String feld) {
        letzterFehler = null;
        letzterFehlerTransient = false;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            url + "/v1/" + mount + "/data/" + URLEncoder.encode(pfad, StandardCharsets.UTF_8)
                                    .replace("+", "%20")))
                    .header("X-Vault-Token", token)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .GET().build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());

            if (r.statusCode() == 404) {
                // KV v2 antwortet auf einen unbekannten Pfad mit 404 — das ist eine DEFINITIVE
                // Antwort, kein Fehler. Kein Retry, sonst wartet der Boot auf einen Tippfehler.
                letzterFehler = "Eintrag '" + pfad + "' existiert nicht (HTTP 404)";
                return Optional.empty();
            }
            if (r.statusCode() == 403) {
                letzterFehler = "Zugriff verweigert (HTTP 403) — Token oder Policy pruefen";
                return Optional.empty();
            }
            if (r.statusCode() == 503) {
                // Versiegelt. Beim Kaltstart der Normalfall, nicht die Ausnahme -> transient.
                letzterFehler = "OpenBao ist versiegelt oder nicht bereit (HTTP 503)";
                letzterFehlerTransient = true;
                return Optional.empty();
            }
            if (r.statusCode() != 200) {
                letzterFehler = "unerwartete Antwort HTTP " + r.statusCode();
                letzterFehlerTransient = r.statusCode() >= 500;
                return Optional.empty();
            }

            JsonNode daten = JSON.readTree(r.body()).path("data").path("data");
            JsonNode wert = daten.path(feld);
            if (wert.isMissingNode() || wert.isNull()) {
                letzterFehler = "Feld '" + feld + "' fehlt im Eintrag '" + pfad + "'";
                return Optional.empty();
            }
            String s = wert.asText();
            return s.isEmpty() ? Optional.empty() : Optional.of(s);

        } catch (Exception e) {
            // Netzfehler, Timeout, DNS: transient. Der Tresor kann beim Boot noch anlaufen.
            letzterFehler = e.getClass().getSimpleName() + ": " + e.getMessage();
            letzterFehlerTransient = true;
            log.debug("OpenBao-Lesefehler fuer '{}': {}", pfad, letzterFehler);
            return Optional.empty();
        }
    }

    /** Meldung des letzten Fehlschlags — geht in die Fail-fast-Ausnahme, enthaelt nie einen Wert. */
    String letzterFehler() {
        return letzterFehler == null ? "unbekannt" : letzterFehler;
    }

    /** War der letzte Fehlschlag transient (Netz/5xx/versiegelt)? Dann lohnt ein Boot-Retry. */
    boolean letzterFehlerWarTransient() {
        return letzterFehlerTransient;
    }
}
