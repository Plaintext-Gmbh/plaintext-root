/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Setzt den Seiten-Zugriffsschutz VOR dem {@code FacesServlet} durch (Karte 308, H3).
 *
 * <p><b>Warum ein Filter und nicht {@code preRenderView}:</b> {@code preRenderView} feuert in
 * RENDER_RESPONSE (Phase 6), Action-Methoden laufen aber in INVOKE_APPLICATION (Phase 5). Ein
 * AJAX-/POST-Postback auf eine gesperrte Seite hat die Backing-Bean-Action (z.B.
 * {@code RootEntityBackingBean.deleteEntity()}) also bereits <b>vollstaendig ausgefuehrt</b>, bevor
 * der Guard ueberhaupt lief; der Redirect verwarf danach nur noch die Antwort — die Schreiboperation
 * war committet. Dieser Filter greift vor jeder JSF-Phase, also auch vor RESTORE_VIEW.
 *
 * <p><b>Warum der Filter in die Spring-Security-Kette gehaengt wird</b> (siehe
 * {@code PlaintextSecurityConfig}, {@code addFilterAfter(..., AuthorizationFilter.class)}) und nicht
 * per {@code FilterRegistrationBean} registriert wird: {@code UrlRewriteConfig} registriert seinen
 * Rewrite-Filter mit {@code Ordered.HIGHEST_PRECEDENCE + 1}, also <i>vor</i> der
 * Spring-Security-Kette ({@code order = -100}), und schreibt {@code /x.html} per
 * {@code RequestDispatcher.forward()} auf {@code /x.xhtml} um, ohne {@code chain.doFilter()}
 * aufzurufen. Ein eigenstaendig registrierter Filter mit {@code DispatcherType.REQUEST} wuerde
 * darum bei {@code .html}-URLs niemals laufen. Die Security-Kette laeuft dagegen fuer <i>alle</i>
 * Dispatch-Typen ({@code SecurityFilterProperties.dispatcherTypes = EnumSet.allOf(...)}), also auch
 * beim FORWARD — und damit garantiert nach der Authentifizierung.
 *
 * <p>Der bestehende {@code preRenderView}-Guard in {@code includes/template.xhtml} bleibt als
 * zweite Schicht erhalten (Defense in Depth) und entscheidet ueber denselben Service, kommt also
 * nie zu einem anderen Ergebnis.
 */
@Slf4j
@RequiredArgsConstructor
public class PageAccessGuardFilter implements Filter {

    /** Verhindert doppelte Pruefung auf REQUEST- und FORWARD-Dispatch derselben Anfrage. */
    static final String ATTRIBUT_GEPRUEFT = PageAccessGuardFilter.class.getName() + ".checked";

    /**
     * Endungen, die auf dem {@code FacesServlet} landen ({@code joinfaces.faces-servlet.url-mappings}
     * plus der {@code .htm}-Rewrite aus {@code UrlRewriteConfig}). Nur diese Pfade werden geprueft;
     * REST-Endpunkte, statische Dateien und alles ohne Endung sind Sache von Spring Security.
     */
    private static final List<String> VIEW_ENDUNGEN = List.of(".xhtml", ".jsf", ".html", ".htm");

    /**
     * Technische Pfade, die zwar auf eine View-Endung enden, aber keine JSF-View sind. Insbesondere
     * die JSF-Ressourcen ({@code /jakarta.faces.resource/primefaces.js.xhtml}) — ohne diese
     * Ausnahme wuerde der Guard im STRICT-Modus alle PrimeFaces-Ressourcen sperren.
     * Deckungsgleich mit der Ausnahmeliste in {@code UrlRewriteConfig}.
     */
    private static final List<String> TECHNISCHE_PFADE = List.of(
            "/jakarta.faces.resource/",
            "/javax.faces.resource/",
            "/swagger",
            "/webjars",
            "/api-docs",
            "/v3/api-docs",
            "/actuator",
            "/oauth2/",
            "/login/oauth2/"
    );

    private final PageAccessGuardService pageAccessGuardService;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }
        if (!pageAccessGuardService.isEnabled()
                || httpRequest.getAttribute(ATTRIBUT_GEPRUEFT) != null) {
            chain.doFilter(request, response);
            return;
        }

        String pfad = pfadOhneContextPath(httpRequest);
        if (!istZuPruefen(pfad)) {
            chain.doFilter(request, response);
            return;
        }

        httpRequest.setAttribute(ATTRIBUT_GEPRUEFT, Boolean.TRUE);

        String viewId = viewId(pfad);
        if (pageAccessGuardService.hasAccessToView(viewId)) {
            chain.doFilter(request, response);
            return;
        }

        log.warn("SECURITY: blocked {} {} (view '{}') before the FacesServlet",
                httpRequest.getMethod(), pfad, viewId);
        verweigere(httpRequest, httpResponse);
    }

    /**
     * Antwort bei verweigertem Zugriff. Ein normaler GET wird auf die Access-Denied-Seite
     * umgeleitet (bisheriges Verhalten des {@code preRenderView}-Guards). Alles andere —
     * insbesondere POST und JSF-AJAX — bekommt 403: ein 302 auf einen Postback wuerde dem Client
     * vortaeuschen, die Aktion sei ausgefuehrt worden.
     */
    private void verweigere(HttpServletRequest request, HttpServletResponse response) throws IOException {
        boolean ajax = "partial/ajax".equals(request.getHeader("Faces-Request"))
                || "XMLHttpRequest".equals(request.getHeader("X-Requested-With"));
        boolean lesend = "GET".equalsIgnoreCase(request.getMethod()) || "HEAD".equalsIgnoreCase(request.getMethod());

        if (lesend && !ajax) {
            response.sendRedirect(request.getContextPath() + "/access-denied.html");
            return;
        }
        response.sendError(HttpServletResponse.SC_FORBIDDEN, "Zugriff auf diese Seite ist nicht erlaubt");
    }

    /** Request-URI ohne Context-Path, immer mit fuehrendem Slash. */
    private String pfadOhneContextPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return "/";
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return uri.isEmpty() ? "/" : uri;
    }

    /** Nur JSF-View-Pfade pruefen, keine technischen Pfade. */
    static boolean istZuPruefen(String pfad) {
        if (pfad == null || pfad.isBlank()) {
            return false;
        }
        String klein = pfad.toLowerCase(Locale.ROOT);
        boolean viewEndung = false;
        for (String endung : VIEW_ENDUNGEN) {
            if (klein.endsWith(endung)) {
                viewEndung = true;
                break;
            }
        }
        if (!viewEndung) {
            return false;
        }
        for (String technisch : TECHNISCHE_PFADE) {
            if (klein.startsWith(technisch) || klein.contains(technisch)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Pfad -> JSF-View-Id ({@code /x.html} -> {@code /x.xhtml}).
     *
     * <p>Das Praefix {@code /faces} wird abgeschnitten: das {@code FacesServlet} ist laut
     * {@code joinfaces.faces-servlet.url-mappings} auch auf {@code /faces/*} gemappt.
     * {@code /faces/mandatemenu.xhtml} rendert dieselbe View wie {@code /mandatemenu.html} — ohne
     * die Normalisierung waere das eine View-Id, zu der kein Menue passt, und im Modus REPORT
     * damit ein Weg um den Guard herum.
     */
    static String viewId(String pfad) {
        String bereinigt = pfad;
        if (bereinigt.regionMatches(true, 0, "/faces/", 0, "/faces/".length())) {
            bereinigt = bereinigt.substring("/faces".length());
        }
        for (String endung : VIEW_ENDUNGEN) {
            if (bereinigt.toLowerCase(Locale.ROOT).endsWith(endung)) {
                return bereinigt.substring(0, bereinigt.length() - endung.length()) + ".xhtml";
            }
        }
        return bereinigt;
    }
}
