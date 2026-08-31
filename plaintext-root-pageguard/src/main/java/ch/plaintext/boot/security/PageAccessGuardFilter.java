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
 * Enforces page access protection BEFORE the {@code FacesServlet} (card 308, H3).
 *
 * <p><b>Why a filter and not {@code preRenderView}:</b> {@code preRenderView} fires in
 * RENDER_RESPONSE (phase 6), but action methods run in INVOKE_APPLICATION (phase 5). For an
 * AJAX/POST postback to a blocked page the backing bean action (e.g.
 * {@code RootEntityBackingBean.deleteEntity()}) had therefore already been <b>fully executed</b>
 * before the guard ran at all; the redirect afterwards only discarded the response — the write
 * operation was committed. This filter runs before every JSF phase, so before RESTORE_VIEW too.
 *
 * <p><b>Why the filter is hooked into the Spring Security chain</b> (see
 * {@code PlaintextSecurityConfig}, {@code addFilterAfter(..., AuthorizationFilter.class)}) instead
 * of being registered via a {@code FilterRegistrationBean}: {@code UrlRewriteConfig} registers its
 * rewrite filter with {@code Ordered.HIGHEST_PRECEDENCE + 1}, that is <i>before</i> the
 * Spring Security chain ({@code order = -100}), and rewrites {@code /x.html} to {@code /x.xhtml}
 * via {@code RequestDispatcher.forward()} without calling {@code chain.doFilter()}. A
 * separately registered filter with {@code DispatcherType.REQUEST} would therefore never run for
 * {@code .html} URLs. The security chain, by contrast, runs for <i>all</i> dispatch types
 * ({@code SecurityFilterProperties.dispatcherTypes = EnumSet.allOf(...)}), so on FORWARD as well —
 * and thus guaranteed after authentication.
 *
 * <p>The existing {@code preRenderView} guard in {@code includes/template.xhtml} remains as a
 * second layer (defense in depth) and decides via the same service, so it can never arrive at a
 * different result.
 */
@Slf4j
@RequiredArgsConstructor
public class PageAccessGuardFilter implements Filter {

    /** Prevents a double check on the REQUEST and FORWARD dispatch of the same request. */
    static final String ATTRIBUT_GEPRUEFT = PageAccessGuardFilter.class.getName() + ".checked";

    /**
     * Extensions that end up on the {@code FacesServlet} ({@code joinfaces.faces-servlet.url-mappings}
     * plus the {@code .htm} rewrite from {@code UrlRewriteConfig}). Only these paths are checked;
     * REST endpoints, static files and everything without an extension are Spring Security's job.
     */
    private static final List<String> VIEW_ENDUNGEN = List.of(".xhtml", ".jsf", ".html", ".htm");

    /**
     * Technical paths that do end in a view extension but are not a JSF view. In particular the
     * JSF resources ({@code /jakarta.faces.resource/primefaces.js.xhtml}) — without this exception
     * the guard would block all PrimeFaces resources in STRICT mode.
     * Identical to the exception list in {@code UrlRewriteConfig}.
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
     * Response when access is denied. A plain GET is redirected to the access denied page (the
     * previous behaviour of the {@code preRenderView} guard). Everything else — POST and JSF AJAX
     * in particular — gets a 403: a 302 on a postback would lead the client to believe the action
     * had been carried out.
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

    /** Request URI without the context path, always with a leading slash. */
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

    /** Only check JSF view paths, no technical paths. */
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
     * Path -> JSF view id ({@code /x.html} -> {@code /x.xhtml}).
     *
     * <p>The prefix {@code /faces} is stripped: according to
     * {@code joinfaces.faces-servlet.url-mappings} the {@code FacesServlet} is also mapped to
     * {@code /faces/*}. {@code /faces/mandatemenu.xhtml} renders the same view as
     * {@code /mandatemenu.html} — without this normalization that would be a view id no menu
     * matches, and thus a way around the guard in REPORT mode.
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
