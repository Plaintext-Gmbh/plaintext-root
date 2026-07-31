/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.ajax;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * Hilfsmethoden, um auf einen JSF-/PrimeFaces-Ajax-Request eine <b>verarbeitbare</b> Antwort zu
 * schicken.
 *
 * <p>Hintergrund (Karte 385): Bei ungueltigem CSRF-Token oder abgelaufener Session beantwortet
 * Spring Security einen Ajax-POST mit HTTP 403/401 und einem JSON-Body. PrimeFaces erwartet auf
 * einen Ajax-Request zwingend eine XML-{@code partial-response}; ein JSON-Body ist unverarbeitbar,
 * es meldet nichts und der Ladeindikator dreht endlos. Statt dessen wird hier eine gueltige
 * {@code partial-response} mit {@code <redirect>} erzeugt — PrimeFaces fuehrt den Redirect selbst
 * aus, der Nutzer landet auf der Anmeldung.</p>
 */
public final class JsfAjaxResponses {

    /** Header, den JSF/PrimeFaces auf jedem Ajax-Request mitschickt. */
    public static final String FACES_REQUEST_HEADER = "Faces-Request";
    public static final String FACES_PARTIAL_AJAX = "partial/ajax";

    private JsfAjaxResponses() {
    }

    /**
     * @return {@code true}, wenn der Request von der JSF-Ajax-Engine stammt und deshalb eine
     * XML-{@code partial-response} erwartet.
     */
    public static boolean isJsfAjaxRequest(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String facesRequest = request.getHeader(FACES_REQUEST_HEADER);
        return facesRequest != null && facesRequest.toLowerCase().startsWith(FACES_PARTIAL_AJAX);
    }

    /**
     * Schreibt eine gueltige JSF-{@code partial-response} mit {@code <redirect url="..."/>} und
     * HTTP 200. Nur 200 wird von der Ajax-Engine ueberhaupt geparst.
     */
    public static void sendPartialRedirect(HttpServletResponse response, String url) throws IOException {
        prepare(response);
        try (PrintWriter out = response.getWriter()) {
            out.print("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<partial-response><redirect url=\"" + escapeXml(url) + "\"/></partial-response>");
            out.flush();
        }
    }

    /**
     * Schreibt eine gueltige JSF-{@code partial-response} mit {@code <error>}. Ebenfalls HTTP 200,
     * damit PrimeFaces den Body parst, seinen Fehler-Handler ausloest und den Ladeindikator
     * beendet — statt still haengen zu bleiben.
     */
    public static void sendPartialError(HttpServletResponse response, String name, String message) throws IOException {
        prepare(response);
        try (PrintWriter out = response.getWriter()) {
            out.print("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<partial-response><error>"
                    + "<error-name>" + escapeXml(name) + "</error-name>"
                    + "<error-message><![CDATA[" + message.replace("]]>", "]] >") + "]]></error-message>"
                    + "</error></partial-response>");
            out.flush();
        }
    }

    private static void prepare(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/xml");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
