/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.geo;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Die geteilte Höhenstrecke aus Karte 1301 — geprüft gegen einen <b>lokalen</b> HTTP-Server statt
 * gegen swisstopo und opentopodata. Ohne diese Naht (die beiden überschreibbaren Basisadressen)
 * liesse sich hier nur der Leer-Guard prüfen, und der Fallback bliebe eine Behauptung.
 *
 * <p>Vor der Zusammenlegung stand diese Strecke zweimal im Produktionscode, und in beiden Kopien
 * war von ihr genau dreierlei geprüft: null, leere Liste und die LV95-Formel. Der Fallback — der
 * Teil, der im Betrieb tatsächlich greift — war in keiner der beiden Kopien gemessen.</p>
 */
@DisplayName("AbstractHoehenService: swisstopo, Fallback, Guards")
class AbstractHoehenServiceTest {

    private HttpServer server;
    private String basis;
    private final AtomicInteger swisstopoAufrufe = new AtomicInteger();
    private final AtomicInteger otdAufrufe = new AtomicInteger();

    /** Was der Testserver auf /height antwortet: Status + Rumpf. */
    private volatile int swisstopoStatus = 200;
    private volatile String swisstopoRumpf = "{\"height\":\"1234.5\"}";
    private volatile int otdStatus = 200;
    private volatile String otdRumpf = "{\"results\":[{\"elevation\":800.0},{\"elevation\":900.0}]}";

    private record Punkt(double lat, double lon) implements ILatLon {
    }

    /** Ableitung wie in den Fachmodulen — nur die Adressen zeigen auf den Testserver. */
    private final class TestHoehenService extends AbstractHoehenService {
        private TestHoehenService() {
            super(JsonMapper.builder().build());
        }

        @Override
        protected String swisstopoBasisAdresse() {
            return basis + "/height?easting=";
        }

        @Override
        protected String opentopodataBasisAdresse() {
            return basis + "/otd?locations=";
        }
    }

    @BeforeEach
    void serverStarten() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/height", exchange -> {
            swisstopoAufrufe.incrementAndGet();
            antworte(exchange, swisstopoStatus, swisstopoRumpf);
        });
        server.createContext("/otd", exchange -> {
            otdAufrufe.incrementAndGet();
            antworte(exchange, otdStatus, otdRumpf);
        });
        server.start();
        basis = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void antworte(com.sun.net.httpserver.HttpExchange exchange, int status, String rumpf)
            throws IOException {
        byte[] b = rumpf.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, b.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(b);
        }
    }

    @AfterEach
    void serverStoppen() {
        server.stop(0);
    }

    // ---------------------------------------------------------------- Guards

    @Test
    @DisplayName("null und leere Liste gehen ohne Netzzugriff zurueck")
    void guardsOhneNetz() {
        AbstractHoehenService service = new TestHoehenService();

        assertTrue(service.fetch(null).isEmpty());
        assertTrue(service.fetch(List.of()).isEmpty());
        assertEquals(0, swisstopoAufrufe.get(), "kein Punkt, kein Aufruf");
        assertEquals(0, otdAufrufe.get());
    }

    // ------------------------------------------------------------- swisstopo

    @Test
    @DisplayName("swisstopo liefert vollstaendig -> kein Fallback")
    void swisstopoVollstaendig() {
        AbstractHoehenService service = new TestHoehenService();

        List<Double> hoehen = service.fetch(List.of(new Punkt(46.95, 7.45), new Punkt(46.96, 7.46)));

        assertEquals(List.of(1234.5, 1234.5), hoehen);
        assertEquals(2, swisstopoAufrufe.get(), "ein Aufruf je Punkt");
        assertEquals(0, otdAufrufe.get(), "vollstaendig -> der Fallback darf nicht laufen");
    }

    // --------------------------------------------------------------- Fallback

    @Test
    @DisplayName("swisstopo antwortet mit Fehler -> opentopodata uebernimmt")
    void fallbackBeiFehlerstatus() {
        swisstopoStatus = 503;
        swisstopoRumpf = "kaputt";
        AbstractHoehenService service = new TestHoehenService();

        List<Double> hoehen = service.fetch(List.of(new Punkt(46.95, 7.45), new Punkt(46.96, 7.46)));

        assertEquals(List.of(800.0, 900.0), hoehen);
        assertEquals(2, swisstopoAufrufe.get());
        assertEquals(1, otdAufrufe.get(), "ein Sammelaufruf fuer beide Punkte");
    }

    @Test
    @DisplayName("swisstopo ohne height-Feld (Punkt ausserhalb CH) -> opentopodata uebernimmt")
    void fallbackBeiFehlendemFeld() {
        swisstopoRumpf = "{}";
        AbstractHoehenService service = new TestHoehenService();

        List<Double> hoehen = service.fetch(List.of(new Punkt(0.0, 0.0), new Punkt(0.1, 0.1)));

        assertEquals(List.of(800.0, 900.0), hoehen);
        assertEquals(1, otdAufrufe.get());
    }

    @Test
    @DisplayName("auch der Fallback faellt aus -> leere Liste, der Aufrufer behaelt seine Hoehen")
    void beideAusgefallen() {
        swisstopoStatus = 500;
        otdStatus = 500;
        AbstractHoehenService service = new TestHoehenService();

        assertTrue(service.fetch(List.of(new Punkt(46.95, 7.45))).isEmpty());
        assertEquals(1, otdAufrufe.get());
    }

    @Test
    @DisplayName("Fallback liefert zu wenige Werte -> leere Liste statt verschobener Hoehen")
    void fallbackUnvollstaendig() {
        swisstopoStatus = 500;
        otdRumpf = "{\"results\":[{\"elevation\":800.0}]}";
        AbstractHoehenService service = new TestHoehenService();

        assertTrue(service.fetch(List.of(new Punkt(46.95, 7.45), new Punkt(46.96, 7.46))).isEmpty(),
                "eine Hoehe fuer zwei Punkte waere eine falsche Zuordnung, keine Teilmenge");
    }

    @Test
    @DisplayName("nicht-numerische elevation wird zu 0.0, nicht zum Abbruch")
    void fallbackMitUnbrauchbaremWert() {
        swisstopoStatus = 500;
        otdRumpf = "{\"results\":[{\"elevation\":null},{\"elevation\":900.0}]}";
        AbstractHoehenService service = new TestHoehenService();

        assertEquals(List.of(0.0, 900.0),
                service.fetch(List.of(new Punkt(46.95, 7.45), new Punkt(46.96, 7.46))));
    }

    // ------------------------------------------------------------------ LV95

    @Test
    @DisplayName("WGS84 -> LV95 am Bezugspunkt und im Schweizer Bereich")
    void lv95Naeherung() throws Exception {
        AbstractHoehenService service = new TestHoehenService();
        Method m = AbstractHoehenService.class.getDeclaredMethod("wgs84ToLv95", double.class, double.class);
        m.setAccessible(true);

        double[] bezug = (double[]) m.invoke(service, 169028.66 / 3600.0, 26782.5 / 3600.0);
        assertEquals(2600072.37, bezug[0], 0.5, "Easting am Bezugspunkt");
        assertEquals(1200147.07, bezug[1], 0.5, "Northing am Bezugspunkt");

        double[] bern = (double[]) m.invoke(service, 46.9480, 7.4474);
        assertTrue(bern[0] > 2_480_000 && bern[0] < 2_840_000, "Easting plausibel, war " + bern[0]);
        assertTrue(bern[1] > 1_070_000 && bern[1] < 1_300_000, "Northing plausibel, war " + bern[1]);
    }
}
