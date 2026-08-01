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
 * Leitet den Aufruf eines unbekannten Pfades auf die Startseite um, statt die
 * Whitelabel-Errorpage zu zeigen (Karte 406).
 *
 * <p>Liegt in plaintext-root und wirkt dadurch in <em>allen</em> Ableitungen (app, schuetu, guild,
 * iot, fwtool) — der Fehler soll nicht in jeder App einzeln behoben werden muessen.</p>
 *
 * <p><strong>Warum ein {@link ErrorViewResolver} und kein eigener {@code ErrorController}:</strong>
 * Ein eigener {@code ErrorController} wuerde den {@code BasicErrorController} ersetzen; damit
 * verloeren API-Clients ihr JSON-Fehlerformat. Ein {@code ErrorViewResolver} wird nur fuer die
 * HTML-Variante befragt — wer {@code Accept: application/json} schickt, bekommt unveraendert
 * JSON.</p>
 *
 * <p><strong>Bewusst eng gefasst:</strong> Umgeleitet wird ausschliesslich bei {@code 404}. Echte
 * Serverfehler (5xx) bleiben sichtbar — wuerde man sie mit umleiten, verschwaenden Stoerungen
 * lautlos. Fuer sie liegt eine schlichte {@code static/error.html} bei, damit auch dort kein
 * Whitelabel nach aussen dringt.</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PlaintextErrorViewResolver implements ErrorViewResolver {

    /** Ziel der Umleitung. */
    static final String STARTSEITE = "/";

    /**
     * Pfade, die NICHT umgeleitet werden duerfen. Ein 404 muss dort ein 404 bleiben: Clients
     * erwarten JSON bzw. eine echte Fehlermeldung, und ein stiller Redirect auf HTML wuerde
     * Fehler unsichtbar machen — auch dann, wenn ein Browser sie mit {@code Accept: text/html}
     * aufruft und der Resolver deshalb ueberhaupt gefragt wird.
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
     * Pfade, die exakt so lauten und nicht umgeleitet werden. {@code /} und {@code /index.html}
     * sind das Redirect-Ziel selbst — eine Umleitung dorthin ergaebe eine Endlosschleife, falls
     * die Startseite ihrerseits 404 liefert.
     */
    private static final Set<String> AUSGENOMMENE_PFADE = Set.of(
            "/", "/index.html", "/error", "/favicon.ico");

    @Override
    public ModelAndView resolveErrorView(HttpServletRequest request, HttpStatus status,
                                         Map<String, Object> model) {
        if (status != HttpStatus.NOT_FOUND) {
            return null;   // 5xx und alles andere: Standardverhalten, Fehler bleibt sichtbar
        }
        String pfad = ermittlePfad(request);
        if (!istUmleitbar(pfad)) {
            return null;
        }
        log.debug("Unbekannter Pfad {} — Umleitung auf {}", pfad, STARTSEITE);
        return new ModelAndView(new RedirectView(STARTSEITE, true), Map.of());
    }

    /**
     * Der urspruenglich angefragte Pfad. Beim Fehler-Forward zeigt {@code getRequestURI()} auf
     * {@code /error}; der echte Pfad steht im Request-Attribut, das der Container setzt.
     */
    private String ermittlePfad(HttpServletRequest request) {
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        if (uri instanceof String s && !s.isBlank()) {
            return s;
        }
        String fallback = request.getRequestURI();
        return fallback == null ? "" : fallback;
    }

    /** Sichtbar fuer Tests. */
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
