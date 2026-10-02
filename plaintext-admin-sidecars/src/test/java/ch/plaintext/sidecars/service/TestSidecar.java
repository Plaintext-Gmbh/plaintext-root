/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars.service;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Ein winziger Sidecar nach dem Protokoll, für Tests (Karte 1400). */
final class TestSidecar implements AutoCloseable {

    static final String TOKEN = "geheimer-test-token";

    static String beschreibung(String name, String status, String faehigkeiten) {
        return """
                {"protokoll":"plaintext-sidecar/1","name":"%s","titel":"Test","version":"1.0","status":"%s",
                 "statusText":"läuft","auth":{"art":"bearer"},
                 "teile":[{"name":"modell","status":"ok","text":"geladen"}],
                 "faehigkeiten":[%s],"doku":"https://example.invalid/doku"}
                """.formatted(name, status, faehigkeiten);
    }

    static final String INFO = """
            {"id":"bild.info","titel":"Bildinfo","beschreibung":"liest Masse","methode":"GET","pfad":"/info",
             "auth":true,"mcp":true,"seiteneffekt":"keiner"}""";
    static final String SENDEN = """
            {"id":"nachricht.senden.test","titel":"Senden","methode":"POST","pfad":"/senden",
             "auth":true,"mcp":true,"seiteneffekt":"aussen"}""";
    static final String INTERN = """
            {"id":"bild.intern","titel":"Intern","methode":"POST","pfad":"/intern",
             "auth":true,"mcp":false,"seiteneffekt":"keiner"}""";

    final HttpServer server;
    final List<String> aufrufe = new CopyOnWriteArrayList<>();
    volatile String antwort;

    TestSidecar(String name, String status) throws IOException {
        antwort = beschreibung(name, status, String.join(",", INFO, SENDEN, INTERN));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/.well-known/plaintext-sidecar", ex -> {
            String p = ex.getRequestURI().getPath();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            aufrufe.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " auth=" + (auth != null));
            if (p.endsWith("/auth")) {
                ex.sendResponseHeaders(("Bearer " + TOKEN).equals(auth) ? 200 : 401, -1);
                ex.close();
                return;
            }
            byte[] b = antwort.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/info", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            aufrufe.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " auth=" + (auth != null)
                    + (ex.getRequestHeaders().containsKey("Upgrade") ? " UPGRADE" : ""));
            if (!("Bearer " + TOKEN).equals(auth)) {
                ex.sendResponseHeaders(401, -1);
                ex.close();
                return;
            }
            byte[] b = "{\"breite\":640}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/umleitung", ex -> {
            ex.getResponseHeaders().add("Location", "http://example.invalid/");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
