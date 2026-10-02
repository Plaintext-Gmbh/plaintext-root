/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars.ablage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Ein kleiner WebDAV-Server im Speicher, wie Nextcloud ihn unter /remote.php/dav/files/<benutzer>/ spricht (Karte 1406). */
public final class TestWebDav implements AutoCloseable {

    public static final String BENUTZER = "anna";
    public static final String PASSWORT = "app-passwort-test";

    final HttpServer server;
    /** Pfad (dekodiert, absolut) → Inhalt; Ordner enden mit / und haben null. */
    public final Map<String, byte[]> dateien = new TreeMap<>();
    public final List<String> aufrufe = new CopyOnWriteArrayList<>();

    public TestWebDav() throws IOException {
        dateien.put("/remote.php/dav/files/anna/", null);
        dateien.put("/remote.php/dav/files/anna/Projekte/", null);
        dateien.put("/remote.php/dav/files/anna/Projekte/drawio/", null);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::behandle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void behandle(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        String m = ex.getRequestMethod();
        aufrufe.add(m + " " + ex.getRequestURI().getRawPath());
        String soll = "Basic " + Base64.getEncoder().encodeToString((BENUTZER + ":" + PASSWORT).getBytes(StandardCharsets.UTF_8));
        if (!soll.equals(ex.getRequestHeaders().getFirst("Authorization"))) {
            antworte(ex, 401, null);
            return;
        }
        byte[] body = ex.getRequestBody().readAllBytes();
        switch (m) {
            case "PROPFIND" -> {
                String ordner = p.endsWith("/") ? p : p + "/";
                if (!dateien.containsKey(ordner)) {
                    antworte(ex, 404, null);
                    return;
                }
                StringBuilder x = new StringBuilder("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">");
                for (Map.Entry<String, byte[]> e : dateien.entrySet()) {
                    String k = e.getKey();
                    String rest = k.startsWith(ordner) ? k.substring(ordner.length()) : null;
                    boolean direkt = rest != null && (rest.isEmpty() || rest.indexOf('/') == rest.length() - 1 || rest.indexOf('/') < 0);
                    if (direkt) {
                        x.append("<d:response><d:href>").append(k.replace(" ", "%20")).append("</d:href><d:propstat><d:prop>")
                                .append(e.getValue() == null ? "<d:resourcetype><d:collection/></d:resourcetype>"
                                        : "<d:resourcetype/><d:getcontentlength>" + e.getValue().length + "</d:getcontentlength>")
                                .append("</d:prop></d:propstat></d:response>");
                    }
                }
                antworte(ex, 207, x.append("</d:multistatus>").toString().getBytes(StandardCharsets.UTF_8));
            }
            case "MKCOL" -> {
                String o = p.endsWith("/") ? p : p + "/";
                antworte(ex, dateien.containsKey(o) ? 405 : 201, null);
                dateien.putIfAbsent(o, null);
            }
            case "PUT" -> {
                String eltern = p.substring(0, p.lastIndexOf('/') + 1);
                if (!dateien.containsKey(eltern)) {
                    antworte(ex, 409, null);
                    return;
                }
                boolean neu = !dateien.containsKey(p);
                dateien.put(p, body);
                antworte(ex, neu ? 201 : 204, null);
            }
            case "GET", "HEAD" -> {
                byte[] d = dateien.get(p);
                if (d == null) {
                    antworte(ex, 404, null);
                } else {
                    antworte(ex, 200, "HEAD".equals(m) ? null : d);
                }
            }
            case "DELETE" -> antworte(ex, dateien.remove(p) != null ? 204 : 404, null);
            default -> antworte(ex, 405, null);
        }
    }

    private static void antworte(HttpExchange ex, int status, byte[] b) throws IOException {
        ex.sendResponseHeaders(status, b == null ? -1 : b.length);
        if (b != null) {
            ex.getResponseBody().write(b);
        }
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
