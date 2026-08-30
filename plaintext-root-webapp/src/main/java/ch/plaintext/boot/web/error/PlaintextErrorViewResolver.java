/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web.error;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Redirects the call of an unknown path to the start page instead of showing the
 * whitelabel error page (card 406).
 *
 * <p>Lives in plaintext-root and therefore takes effect in <em>all</em> derivatives (app, schuetu, guild,
 * iot, fwtool) — the defect should not have to be fixed in every app separately.</p>
 *
 * <p><strong>Why an {@link ErrorViewResolver} and not an {@code ErrorController} of our own:</strong>
 * an own {@code ErrorController} would replace the {@code BasicErrorController}; API clients
 * would thereby lose their JSON error format. An {@code ErrorViewResolver} is only consulted for
 * the HTML variant — whoever sends {@code Accept: application/json} gets JSON unchanged.</p>
 *
 * <p><strong>Deliberately narrow:</strong> the redirect happens exclusively on {@code 404}. Real
 * server errors (5xx) stay visible — redirecting them as well would make malfunctions disappear
 * silently. For them a plain {@code static/error.html} is shipped, so that no whitelabel page
 * reaches the outside there either.</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PlaintextErrorViewResolver implements ErrorViewResolver {

    /** Target of the redirect. */
    static final String STARTSEITE = "/";

    /**
     * Paths that must NOT be redirected. A 404 has to stay a 404 there: clients
     * expect JSON resp. a real error message, and a silent redirect to HTML would
     * make errors invisible — even when a browser calls them with {@code Accept: text/html}
     * and the resolver is therefore consulted at all.
     */
    private static final List<String> AUSGENOMMENE_PRAEFIXE = List.of(
            "/api/",
            "/mcp/",
            "/actuator/",
            "/jakarta.faces.resource/",
            "/webjars/",
            "/resources/",
            "/static/",
            "/css/",
            "/js/",
            "/images/");

    /**
     * Paths that read exactly like this and are not redirected. {@code /} and {@code /index.html}
     * are the redirect target itself — a redirect there would produce an endless loop should
     * the start page return a 404 in turn.
     */
    private static final Set<String> AUSGENOMMENE_PFADE = Set.of(
            "/", "/index.html", "/error", "/favicon.ico");

    @Override
    public ModelAndView resolveErrorView(HttpServletRequest request, HttpStatus status,
                                         Map<String, Object> model) {
        if (status != HttpStatus.NOT_FOUND) {
            return null;   // 5xx and everything else: default behaviour, the error stays visible
        }
        String pfad = ermittlePfad(request);
        if (!istUmleitbar(pfad)) {
            return null;
        }
        log.debug("Unbekannter Pfad {} — Umleitung auf {}", pfad, STARTSEITE);
        return new ModelAndView(new RedirectView(STARTSEITE, true), Map.of());
    }

    /**
     * The originally requested path. On the error forward {@code getRequestURI()} points to
     * {@code /error}; the real path stands in the request attribute that the container sets.
     */
    private String ermittlePfad(HttpServletRequest request) {
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        if (uri instanceof String s && !s.isBlank()) {
            return s;
        }
        String fallback = request.getRequestURI();
        return fallback == null ? "" : fallback;
    }

    /** Visible for tests. */
    boolean istUmleitbar(String pfad) {
        if (pfad == null || pfad.isBlank()) {
            return false;
        }
        if (AUSGENOMMENE_PFADE.contains(pfad)) {
            return false;
        }
        return AUSGENOMMENE_PRAEFIXE.stream().noneMatch(pfad::startsWith);
    }
}
