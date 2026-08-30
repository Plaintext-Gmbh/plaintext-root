/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.ajax;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * {@link AuthenticationEntryPoint} that answers a JSF/PrimeFaces Ajax request with a valid
 * XML {@code partial-response} including a {@code <redirect>} (card 385). All other
 * requests are passed on to the regular entry point (form login redirect).
 *
 * <p>Without this the Ajax engine receives, on an expired session, an HTML redirect to the
 * login page resp. a JSON error and can process neither — the loading indicator
 * spins forever.</p>
 */
@Slf4j
public class JsfAjaxAwareAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final AuthenticationEntryPoint delegate;
    private final String loginUrl;

    public JsfAjaxAwareAuthenticationEntryPoint(AuthenticationEntryPoint delegate, String loginUrl) {
        this.delegate = delegate;
        this.loginUrl = loginUrl;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException, ServletException {
        if (JsfAjaxResponses.isJsfAjaxRequest(request)) {
            // LOGGING (card 385, manager review): see JsfAjaxAwareAccessDeniedHandler. Here
            // deliberately INFO instead of WARN: this branch is the normal case of an expired session resp.
            // one lost after a blue/green deploy. As WARN it would flood the log on
            // every deploy and make the genuine CSRF denials invisible in it — exactly
            // the effect this fix is meant to remove. The visibility is retained, the
            // urgency is a different one.
            // Deliberately NOT logged: token, session id, user name, request parameters.
            log.info("Ajax-Request ohne gueltige Authentifizierung (Session abgelaufen): {} {} "
                            + "— beantwortet mit JSF-partial-response, Redirect auf {}",
                    request.getMethod(), request.getRequestURI(), loginUrl);
            JsfAjaxResponses.sendPartialRedirect(response, request.getContextPath() + loginUrl);
            return;
        }
        delegate.commence(request, response, authException);
    }
}
