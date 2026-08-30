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
 * {@link AccessDeniedHandler} that delivers a processable XML {@code partial-response} to
 * JSF/PrimeFaces Ajax requests instead of a JSON 403 (card 385).
 *
 * <p>An expired CSRF token ({@link CsrfException}) — the normal case after every deploy and after every
 * re-login in another tab — leads to a redirect to the login page. A
 * genuine authorization denial of a logged-in user, in contrast, is returned as an
 * {@code <error>}: sending that user to the login page would be misleading, and
 * the spinner has to stop regardless.</p>
 *
 * <p>Non-Ajax requests are passed through unchanged by the handler to the Spring default
 * behaviour.</p>
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
        // LOGGING (card 385, manager review): before this fix every denial left an
        // HTTP 403 in the access log. The response is now an HTTP 200 — without a log entry of our own
        // rejected requests, including genuine CSRF attack attempts, would be completely
        // invisible. Exactly this invisibility made the diagnosis of this bug take four attempts.
        // Deliberately WARN and not INFO: a CSRF denial is either an
        // attack attempt or a deploy/tab side effect — one wants to find both in Graylog,
        // and the frequency is a direct measure of the residual effect of this bug.
        // Deliberately NOT logged: token, session id, user name, request parameters.
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
