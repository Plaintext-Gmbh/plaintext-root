/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.ajax;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.web.csrf.CsrfException;

import java.io.IOException;

/**
 * {@link AccessDeniedHandler}, der JSF-/PrimeFaces-Ajax-Requests eine verarbeitbare
 * XML-{@code partial-response} liefert statt eines JSON-403 (Karte 385).
 *
 * <p>Ein abgelaufenes CSRF-Token ({@link CsrfException}) — nach jedem Deploy und nach jedem
 * Re-Login in einem anderen Tab der Normalfall — fuehrt zum Redirect auf die Anmeldung. Eine
 * echte Autorisierungsverweigerung eines angemeldeten Nutzers wird dagegen als
 * {@code <error>} zurueckgegeben: den auf die Login-Seite zu schicken waere irrefuehrend, und
 * das Raedchen muss trotzdem stoppen.</p>
 *
 * <p>Nicht-Ajax-Requests reicht der Handler unveraendert an das Spring-Default-Verhalten
 * durch.</p>
 */
@Slf4j
public class JsfAjaxAwareAccessDeniedHandler implements AccessDeniedHandler {

    private final AccessDeniedHandler delegate = new AccessDeniedHandlerImpl();
    private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();
    private final String loginUrl;

    public JsfAjaxAwareAccessDeniedHandler(String loginUrl) {
        this.loginUrl = loginUrl;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException, ServletException {
        if (!JsfAjaxResponses.isJsfAjaxRequest(request)) {
            delegate.handle(request, response, accessDeniedException);
            return;
        }
        // LOGGING (Karte 385, Manager-Review): Vor diesem Fix hinterliess jede Ablehnung ein
        // HTTP 403 im Zugriffslog. Die Antwort ist jetzt ein HTTP 200 — ohne eigenen Log-Eintrag
        // waeren abgewiesene Requests, einschliesslich echter CSRF-Angriffsversuche, voellig
        // unsichtbar. Genau diese Unsichtbarkeit hat die Diagnose dieses Bugs vier Anlaeufe
        // gekostet. Bewusst WARN und nicht INFO: eine CSRF-Ablehnung ist entweder ein
        // Angriffsversuch oder ein Deploy-/Tab-Nebeneffekt — beides will man in Graylog finden,
        // und die Haeufigkeit ist ein direktes Mass fuer die Restwirkung dieses Bugs.
        // Bewusst NICHT geloggt: Token, Session-Id, Benutzername, Request-Parameter.
        if (accessDeniedException instanceof CsrfException) {
            log.warn("Ajax-Request abgewiesen (CSRF-Token fehlt oder ist ungueltig): {} {} "
                            + "— beantwortet mit JSF-partial-response, Redirect auf {}",
                    request.getMethod(), request.getRequestURI(), loginUrl);
            JsfAjaxResponses.sendPartialRedirect(response, request.getContextPath() + loginUrl);
            return;
        }
        if (!isAuthenticated()) {
            log.warn("Ajax-Request abgewiesen (keine gueltige Anmeldung, Session abgelaufen): {} {} "
                            + "— beantwortet mit JSF-partial-response, Redirect auf {}",
                    request.getMethod(), request.getRequestURI(), loginUrl);
            JsfAjaxResponses.sendPartialRedirect(response, request.getContextPath() + loginUrl);
            return;
        }
        log.warn("Ajax-Request abgewiesen (Autorisierung verweigert): {} {} "
                        + "— beantwortet mit JSF-partial-response mit <error>",
                request.getMethod(), request.getRequestURI());
        JsfAjaxResponses.sendPartialError(response, "AccessDenied",
                "Keine Berechtigung fuer diese Aktion.");
    }

    private boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && !trustResolver.isAnonymous(authentication);
    }
}
