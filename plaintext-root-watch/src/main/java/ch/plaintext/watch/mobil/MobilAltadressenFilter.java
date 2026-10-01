/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.mobil;

import ch.plaintext.watch.page.WatchPage;
import ch.plaintext.watch.page.WatchPageRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Keeps the old addresses of the watch pages working after they moved onto the mobile
 * framework (card 1387).
 *
 * <p>Daniel, 01.10.2026: „Das neue design für die watch passt gut bitte alles umstellen". Every
 * watch page used to be a Facelet, addressed as {@code /watch/<id>.html}. Those addresses live
 * on outside the application: icons on a phone's home screen, bookmarks, a link in a note. When a
 * page becomes a {@link MobilWatchPage}, its Facelet is gone; without this filter each of those
 * addresses would end on a 404.</p>
 *
 * <h2>What it does</h2>
 *
 * <p>A request for {@code /watch/<id>.html} or {@code /watch/<id>.xhtml} whose path is the
 * {@link MobilWatchPage#frueheresView()} of a registered page is answered with a redirect to that
 * page's {@link MobilWatchPage#view()} — {@code 302} for a {@code GET}/{@code HEAD},
 * {@code 303} for anything else (a postback from a page still open from before the release must
 * not be repeated, it has to become a plain {@code GET}). Every other request passes untouched.</p>
 *
 * <h2>Why a filter, and why this early</h2>
 *
 * <p>{@code UrlRewriteConfig.HtmlToXhtmlRewriteFilter} (order {@code HIGHEST_PRECEDENCE + 30})
 * forwards every {@code .html} to the Facelet of the same name before Spring MVC or the security
 * chain sees it; a controller mapped to {@code /watch/zeit.html} would never be reached. This
 * filter therefore sits just ahead of it ({@link #ORDER}).</p>
 *
 * <h2>Why that is not a hole</h2>
 *
 * <ul>
 *   <li>The target comes from the registry, never from the request: there is nothing a caller
 *       can set to be sent elsewhere, so this is no open redirect. The query string is dropped.</li>
 *   <li>It answers before sign-in, but the answer is the same for everybody and tells nothing a
 *       caller does not already know (the page ids are in the source). Sign-in, the check of the
 *       phone-link token and the role rule all run on the target, under {@code /watch/m/},
 *       exactly as for a direct call.</li>
 *   <li>Only the one former address of a page that actually is a {@code MobilWatchPage} is
 *       redirected. A Facelet watch page of a module that has not moved yet (guild still carries
 *       one from {@code plaintext-z-kalenderhost}) is not touched.</li>
 * </ul>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
public class MobilAltadressenFilter extends OncePerRequestFilter {

    /** Just ahead of the {@code .html} rewrite at {@code HIGHEST_PRECEDENCE + 30}. */
    public static final int ORDER = org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 20;

    private static final String WATCH = "/watch/";

    private final ObjectProvider<WatchPageRegistry> registry;

    /** Former view (always {@code .xhtml}) -> new address; built on first use. */
    private volatile Map<String, String> ziele;

    public MobilAltadressenFilter(ObjectProvider<WatchPageRegistry> registry) {
        this.registry = registry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String pfad = pfad(request);
        return !pfad.startsWith(WATCH) || pfad.startsWith(MobilWatchPage.PFAD)
                || !(pfad.endsWith(".html") || pfad.endsWith(".xhtml"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String pfad = pfad(request);
        String alsView = pfad.endsWith(".html") ? pfad.substring(0, pfad.length() - ".html".length()) + ".xhtml" : pfad;
        String ziel = ziele().get(alsView);
        if (ziel == null) {
            chain.doFilter(request, response);
            return;
        }
        String methode = request.getMethod();
        boolean lesend = "GET".equals(methode) || "HEAD".equals(methode);
        log.debug("Watch: alte Adresse {} -> {}", pfad, ziel);
        response.setStatus(lesend ? HttpServletResponse.SC_FOUND : HttpServletResponse.SC_SEE_OTHER);
        response.setHeader(HttpHeaders.LOCATION, request.getContextPath() + ziel);
        // Nicht zwischenspeichern: kehrt eine Seite je zur Facelet zurueck, darf kein Telefon die
        // Umleitung behalten haben.
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    private Map<String, String> ziele() {
        Map<String, String> z = ziele;
        if (z == null) {
            Map<String, String> neu = new HashMap<>();
            WatchPageRegistry r = registry.getIfAvailable();
            if (r != null) {
                for (WatchPage p : r.alle()) {
                    if (p instanceof MobilWatchPage m && m.frueheresView() != null) {
                        neu.put(m.frueheresView(), m.view());
                    }
                }
            }
            z = Map.copyOf(neu);
            ziele = z;
            log.info("Watch: alte Adressen umgeleitet: {}", z);
        }
        return z;
    }

    private static String pfad(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String kontext = request.getContextPath();
        return (kontext != null && !kontext.isEmpty() && uri.startsWith(kontext)) ? uri.substring(kontext.length()) : uri;
    }
}
