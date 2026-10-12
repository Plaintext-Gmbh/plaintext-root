/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.geo;

import ch.plaintext.arch.StabileApi;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Liefert Höhenwerte für Trackpunkte – BEST-EFFORT. Primär über die <b>swisstopo</b>-Height-API
 * (swissALTI3D, ~1–2 m genau in der Schweiz; benötigt LV95-Koordinaten, daher WGS84→LV95-Umrechnung)
 * pro Punkt, parallel in Batches. Fällt swisstopo aus (ausserhalb CH, Timeout, Egress gesperrt),
 * wird auf <b>opentopodata</b> (SRTM30m, weltweit) zurückgegriffen. Bei komplettem Misserfolg eine
 * leere Liste (der Aufrufer behält dann die vorhandenen Höhen).
 *
 * <p><b>Warum abstrakt und hier (Karte 1301).</b> Diese Strecke stand bis 22.09.2026 zweimal im
 * Produktionscode: {@code BielerElevationService} (plaintext-z-bielerlauftage) und
 * {@code WanderElevationService} (plaintext-z-wanderungen), 75 gemeinsame Zeilen, Jaccard 0,97.
 * Das Javadoc der zweiten nannte den Grund: „bewusst pro Modul dupliziert, damit das
 * Wanderungen-Modul ohne Querabhängigkeit eigenständig bleibt." Der Grund trägt – nur verlangt er
 * keine Kopie: beide Module hängen ohnehin schon an {@code plaintext-app-interfaces}, und die
 * Ablage hier lässt sie weiterhin nichts voneinander wissen. Die Fachmodule behalten je eine
 * eigene {@code @Service}-Ableitung (eigener Bean, eigener Trackpunkt-Typ, eigene Lebensdauer);
 * geteilt wird nur die Strecke zu den beiden Höhen-APIs.</p>
 *
 * <p>Der Punkt-Typ bleibt Sache des Moduls – verlangt wird nur {@link ILatLon}.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
@StabileApi("Basis fuer Hoehendienste (Karte 1504, aus app-interfaces)")
public abstract class AbstractHoehenService {

    /** swisstopo: pro Anfrage 1 Punkt; parallel in Batches. */
    private static final int SWISS_BATCH = 20;
    private static final String SWISS_HEIGHT = "https://api3.geo.admin.ch/rest/services/height?easting=";

    /** opentopodata: max. 100 Locations/Anfrage, ~1 Anfrage/Sekunde (öffentlich). */
    private static final int OTD_BATCH = 100;
    private static final String OTD_API = "https://api.opentopodata.org/v1/srtm30m?locations=";

    private final ObjectMapper objectMapper;

    protected AbstractHoehenService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Basisadresse der swisstopo-Height-API, bis einschliesslich {@code ?easting=}.
     *
     * <p>Als überschreibbare Methode statt als Konstante, damit ein Test beide Strecken — die
     * primäre und den Fallback — gegen einen lokalen HTTP-Server messen kann. Ohne diese Naht
     * liesse sich am Verhalten dieser Klasse nur der Leer-Guard prüfen, und der Fallback bliebe
     * eine Behauptung.</p>
     */
    protected String swisstopoBasisAdresse() {
        return SWISS_HEIGHT;
    }

    /** Basisadresse der opentopodata-API, bis einschliesslich {@code ?locations=}. Siehe oben. */
    protected String opentopodataBasisAdresse() {
        return OTD_API;
    }

    /**
     * @return Höhen (Meter) in gleicher Reihenfolge wie {@code pts}, oder leere Liste bei Fehler.
     */
    public List<Double> fetch(List<? extends ILatLon> pts) {
        if (pts == null || pts.isEmpty()) {
            return List.of();
        }
        List<Double> swiss = fetchSwisstopo(pts);
        if (swiss.size() == pts.size()) {
            log.info("Höhen via swisstopo (swissALTI3D) für {} Punkte", pts.size());
            return swiss;
        }
        log.info("swisstopo-Höhe unvollständig -> Fallback opentopodata");
        return fetchOpentopodata(pts);
    }

    /** swisstopo Height-API pro Punkt (LV95), parallel in Batches. */
    private List<Double> fetchSwisstopo(List<? extends ILatLon> pts) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        Double[] out = new Double[pts.size()];
        try {
            for (int i = 0; i < pts.size(); i += SWISS_BATCH) {
                int end = Math.min(i + SWISS_BATCH, pts.size());
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (int j = i; j < end; j++) {
                    final int idx = j;
                    double[] en = wgs84ToLv95(pts.get(idx).lat(), pts.get(idx).lon());
                    HttpRequest req = HttpRequest.newBuilder(URI.create(
                                    String.format(Locale.ROOT, "%s%.2f&northing=%.2f", swisstopoBasisAdresse(), en[0], en[1])))
                            .timeout(Duration.ofSeconds(10)).GET().build();
                    futures.add(client.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                            .thenAccept(resp -> uebernehmeHoehe(resp, out, idx))
                            .exceptionally(ex -> null));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("swisstopo-Höhe unterbrochen: {}", ex.getMessage());
            return List.of();
        } catch (Exception ex) {
            log.warn("swisstopo-Höhe fehlgeschlagen (best-effort): {}", ex.getMessage());
            return List.of();
        }
        List<Double> res = new ArrayList<>(pts.size());
        for (Double d : out) {
            if (d == null) {
                return List.of(); // unvollständig (z. B. Punkt ausserhalb CH) -> Fallback
            }
            res.add(d);
        }
        return res;
    }

    /**
     * Uebernimmt die Hoehe aus einer swisstopo-Antwort in {@code out[idx]}. Ein einzelner Punkt,
     * der fehlschlaegt, bleibt {@code null} und fuehrt spaeter zum Fallback.
     */
    private void uebernehmeHoehe(HttpResponse<String> resp, Double[] out, int idx) {
        if (resp.statusCode() != 200) {
            return;
        }
        try {
            JsonNode h = objectMapper.readTree(resp.body()).path("height");
            if (!h.isMissingNode() && !h.isNull()) {
                out[idx] = Double.parseDouble(h.asString());
            }
        } catch (Exception _) {
            // einzelner Punkt fehlgeschlagen -> bleibt null -> Fallback
        }
    }

    /**
     * Näherungsformel WGS84 → LV95 (EPSG:2056) gemäss swisstopo (Genauigkeit ~1 m). Eingabe in
     * Dezimalgrad, Ausgabe {@code [easting, northing]} in Metern.
     */
    private double[] wgs84ToLv95(double lat, double lon) {
        double phi = (lat * 3600.0 - 169028.66) / 10000.0;
        double lam = (lon * 3600.0 - 26782.5) / 10000.0;
        double e = 2600072.37 + 211455.93 * lam - 10938.51 * lam * phi
                - 0.36 * lam * phi * phi - 44.54 * lam * lam * lam;
        double n = 1200147.07 + 308807.95 * phi + 3745.25 * lam * lam + 76.63 * phi * phi
                - 194.56 * lam * lam * phi + 119.79 * phi * phi * phi;
        return new double[]{e, n};
    }

    /** Fallback: opentopodata SRTM30m (weltweit), Batches à 100, Rate-Limit beachtet. */
    private List<Double> fetchOpentopodata(List<? extends ILatLon> pts) {
        List<Double> out = new ArrayList<>(pts.size());
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        try {
            for (int i = 0; i < pts.size(); i += OTD_BATCH) {
                List<? extends ILatLon> batch = pts.subList(i, Math.min(i + OTD_BATCH, pts.size()));
                String locs = batch.stream()
                        .map(p -> String.format(Locale.ROOT, "%.6f,%.6f", p.lat(), p.lon()))
                        .collect(Collectors.joining("|"));
                HttpRequest req = HttpRequest.newBuilder(
                                URI.create(opentopodataBasisAdresse() + URLEncoder.encode(locs, StandardCharsets.UTF_8)))
                        .timeout(Duration.ofSeconds(12)).GET().build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    log.warn("opentopodata Status {} – Abbruch", resp.statusCode());
                    return List.of();
                }
                JsonNode results = objectMapper.readTree(resp.body()).path("results");
                for (JsonNode r : results) {
                    JsonNode ele = r.path("elevation");
                    out.add(ele.isNumber() ? ele.asDouble() : 0.0);
                }
                if (i + OTD_BATCH < pts.size()) {
                    Thread.sleep(1100);
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("opentopodata unterbrochen: {}", ex.getMessage());
            return List.of();
        } catch (Exception ex) {
            log.warn("opentopodata fehlgeschlagen (best-effort): {}", ex.getMessage());
            return List.of();
        }
        return out.size() == pts.size() ? out : List.of();
    }
}
