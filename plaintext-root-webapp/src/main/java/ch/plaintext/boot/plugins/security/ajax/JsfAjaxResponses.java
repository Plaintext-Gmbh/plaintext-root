/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.ajax;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * Helper methods for sending a <b>processable</b> response to a JSF/PrimeFaces Ajax request.
 *
 * <p>Background (card 385): on an invalid CSRF token or an expired session,
 * Spring Security answers an Ajax POST with HTTP 403/401 and a JSON body. PrimeFaces mandatorily
 * expects an XML {@code partial-response} to an Ajax request; a JSON body is unprocessable,
 * it reports nothing and the loading indicator spins forever. Instead a valid
 * {@code partial-response} with a {@code <redirect>} is produced here — PrimeFaces performs the
 * redirect itself and the user lands on the login page.</p>
 */
public final class JsfAjaxResponses {

    /** Header that JSF/PrimeFaces sends along with every Ajax request. */
    public static final String FACES_REQUEST_HEADER = "Faces-Request";
    public static final String FACES_PARTIAL_AJAX = "partial/ajax";

    private JsfAjaxResponses() {
    }

    /**
     * @return {@code true} if the request comes from the JSF Ajax engine and therefore expects an
     * XML {@code partial-response}.
     */
    public static boolean isJsfAjaxRequest(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String facesRequest = request.getHeader(FACES_REQUEST_HEADER);
        return facesRequest != null && facesRequest.toLowerCase().startsWith(FACES_PARTIAL_AJAX);
    }

    /**
     * Writes a valid JSF {@code partial-response} with {@code <redirect url="..."/>} and
     * HTTP 200. Only 200 is parsed by the Ajax engine at all.
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
     * Writes a valid JSF {@code partial-response} with an {@code <error>}. Likewise HTTP 200,
     * so that PrimeFaces parses the body, triggers its error handler and terminates the loading
     * indicator — instead of hanging silently.
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
